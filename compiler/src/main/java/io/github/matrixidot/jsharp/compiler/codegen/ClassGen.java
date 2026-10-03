package io.github.matrixidot.jsharp.compiler.codegen;

import io.github.matrixidot.jsharp.compiler.LanguageInfo;
import io.github.matrixidot.jsharp.compiler.bound.BClass;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symtab;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import io.github.matrixidot.jsharp.compiler.types.Types;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.attribute.EnclosingMethodAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.attribute.PermittedSubclassesAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Emits one class file per lowered class with the JDK ClassFile API: flags, fields (with constant
 * values and generic signatures), methods (with code, parameter names, nullness type annotations
 * and J# metadata), and the attributes the JVM and Java tooling expect: SourceFile, Signature,
 * InnerClasses, NestHost/NestMembers, PermittedSubclasses, Record, EnclosingMethod.
 */
public final class ClassGen {
  private static final ClassDesc NULLABLE = ClassDesc.of("org.jspecify.annotations.Nullable");
  private static final ClassDesc METADATA = ClassDesc.of("jsharp.lang.Metadata");
  private static final ClassDesc EXTENSION = ClassDesc.of("jsharp.lang.Extension");
  private static final ClassDesc OPERATOR = ClassDesc.of("jsharp.lang.Operator");
  private static final ClassDesc DEFAULT_VALUE = ClassDesc.of("jsharp.lang.DefaultValue");

  private final Types types;
  private final Symtab syms;
  private final boolean debug;
  private final Map<ClassSymbol, List<ClassSymbol>> nestMembers;
  private final ClassFile cf;

  /**
   * @param nestMembers nested (incl. local/anonymous) classes of each top-level class
   */
  public ClassGen(Types types, boolean debug, Map<ClassSymbol, List<ClassSymbol>> nestMembers) {
    this.types = types;
    this.syms = types.syms();
    this.debug = debug;
    this.nestMembers = nestMembers;
    this.cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver()));
  }

  /**
   * Resolves class hierarchy questions from the symbol table (including classes being compiled).
   */
  private ClassHierarchyResolver resolver() {
    ClassHierarchyResolver fallback = ClassHierarchyResolver.defaultResolver();
    return desc -> {
      String d = desc.descriptorString();
      if (d.startsWith("L") && d.endsWith(";")) {
        ClassSymbol c = syms.lookup(d.substring(1, d.length() - 1));
        if (c != null) {
          if (c.isInterface()) {
            return ClassHierarchyResolver.ClassHierarchyInfo.ofInterface();
          }
          Type.ClassType sup = c.superclass();
          if (sup == null && !c.binaryName().equals("java/lang/Object")) {
            return ClassHierarchyResolver.ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object);
          }
          return ClassHierarchyResolver.ClassHierarchyInfo.ofClass(
              sup == null ? null : Descs.of(sup.sym()));
        }
      }
      return fallback.getClassInfo(desc);
    };
  }

  public byte[] generate(BClass bc) {
    ClassSymbol c = bc.sym();
    return cf.build(Descs.of(c), clb -> buildClass(clb, bc));
  }

  /** Verifies generated bytes with the ClassFile API verifier; returns problems (empty if fine). */
  public List<String> verify(byte[] bytes) {
    return cf.verify(bytes).stream().map(Throwable::getMessage).toList();
  }

  private void buildClass(ClassBuilder clb, BClass bc) {
    ClassSymbol c = bc.sym();
    clb.withVersion(ClassFile.JAVA_25_VERSION, 0);
    clb.withFlags(classFlags(c));
    Type.ClassType sup = c.superclass();
    if (c.isInterface()) {
      clb.withSuperclass(ConstantDescs.CD_Object);
    } else {
      clb.withSuperclass(sup == null ? ConstantDescs.CD_Object : Descs.of(sup.sym()));
    }
    List<ClassDesc> ifaces = new ArrayList<>();
    for (Type.ClassType i : c.interfaces()) {
      ifaces.add(Descs.of(i.sym()));
    }
    clb.withInterfaceSymbols(ifaces);
    if (c.sourceFileName() != null) {
      clb.with(SourceFileAttribute.of(c.sourceFileName()));
    }
    if (needsClassSignature(c)) {
      StringBuilder sb = new StringBuilder();
      Descs.appendTypeParams(sb, c.typeParams());
      sb.append(Descs.signature(c.isInterface() || sup == null ? syms.objectType() : sup));
      for (Type.ClassType i : c.interfaces()) {
        sb.append(Descs.signature(i));
      }
      clb.with(SignatureAttribute.of(ClassSignature.parseFrom(sb.toString())));
    }
    List<AnnotationElement> meta = new ArrayList<>();
    meta.add(AnnotationElement.ofString("version", LanguageInfo.VERSION));
    if (c.has(Flags.MODULE)) {
      meta.add(AnnotationElement.ofBoolean("module", true));
    }
    clb.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(METADATA, meta)));
    innerClasses(clb, c);
    nest(clb, c);
    if (c.has(Flags.SEALED) && !c.permitted().isEmpty()) {
      List<ClassDesc> ps = new ArrayList<>();
      for (ClassSymbol p : c.permitted()) {
        ps.add(Descs.of(p));
      }
      clb.with(PermittedSubclassesAttribute.ofSymbols(ps));
    }
    if (c.has(Flags.LOCAL) || c.has(Flags.ANONYMOUS)) {
      clb.with(
          EnclosingMethodAttribute.of(Descs.of(c.outer()), Optional.empty(), Optional.empty()));
    }
    if (c.isRecord()) {
      List<RecordComponentInfo> comps = new ArrayList<>();
      for (FieldSymbol f : c.recordComponents()) {
        List<java.lang.classfile.Attribute<?>> attrs = new ArrayList<>();
        if (Descs.isGeneric(f.type())) {
          attrs.add(SignatureAttribute.of(Signature.parseFrom(Descs.signature(f.type()))));
        }
        comps.add(RecordComponentInfo.of(f.name(), Descs.of(f.type()), attrs));
      }
      clb.with(RecordAttribute.of(comps));
    }
    for (FieldSymbol f : c.fields()) {
      field(clb, c, f);
    }
    for (BClass.Method m : bc.methods()) {
      method(clb, c, m);
    }
  }

  private static boolean needsClassSignature(ClassSymbol c) {
    if (!c.typeParams().isEmpty()) {
      return true;
    }
    if (c.superclass() != null && !c.superclass().args().isEmpty()) {
      return true;
    }
    for (Type.ClassType i : c.interfaces()) {
      if (!i.args().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private static int classFlags(ClassSymbol c) {
    long f = c.flags();
    int out = 0;
    // Class-file access is public or package; nested privacy lives in InnerClasses.
    if (Flags.is(f, Flags.PUBLIC) || Flags.is(f, Flags.PROTECTED)) {
      out |= ClassFile.ACC_PUBLIC;
    }
    if (c.isInterface()) {
      out |= ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT;
    } else {
      out |= ClassFile.ACC_SUPER;
      if (Flags.is(f, Flags.FINAL)) {
        out |= ClassFile.ACC_FINAL;
      }
      if (Flags.is(f, Flags.ABSTRACT)) {
        out |= ClassFile.ACC_ABSTRACT;
      }
    }
    if (c.isEnum()) {
      out |= ClassFile.ACC_ENUM;
    }
    if (Flags.is(f, Flags.SYNTHETIC)) {
      out |= ClassFile.ACC_SYNTHETIC;
    }
    return out;
  }

  private static int innerFlags(ClassSymbol c) {
    long f = c.flags();
    int out =
        (int)
            (f
                & (Flags.PUBLIC
                    | Flags.PRIVATE
                    | Flags.PROTECTED
                    | Flags.FINAL
                    | Flags.ABSTRACT
                    | Flags.INTERFACE
                    | Flags.ENUM
                    | Flags.SYNTHETIC));
    if (Flags.is(f, Flags.STATIC) && !c.has(Flags.LOCAL) && !c.has(Flags.ANONYMOUS)) {
      out |= ClassFile.ACC_STATIC;
    }
    return out;
  }

  private void innerClasses(ClassBuilder clb, ClassSymbol c) {
    Set<ClassSymbol> entries = new LinkedHashSet<>();
    for (ClassSymbol x = c; x != null && x.outer() != null; x = x.outer()) {
      entries.add(x);
    }
    entries.addAll(c.memberTypes());
    for (ClassSymbol n : nestMembers.getOrDefault(c.outermost(), List.of())) {
      if (n.outer() == c && (n.has(Flags.LOCAL) || n.has(Flags.ANONYMOUS))) {
        entries.add(n);
      }
    }
    if (entries.isEmpty()) {
      return;
    }
    List<InnerClassInfo> infos = new ArrayList<>();
    for (ClassSymbol n : entries) {
      boolean local = n.has(Flags.LOCAL) || n.has(Flags.ANONYMOUS);
      infos.add(
          InnerClassInfo.of(
              Descs.of(n),
              local ? Optional.empty() : Optional.of(Descs.of(n.outer())),
              n.has(Flags.ANONYMOUS) ? Optional.empty() : Optional.of(n.name()),
              innerFlags(n)));
    }
    clb.with(InnerClassesAttribute.of(infos));
  }

  private void nest(ClassBuilder clb, ClassSymbol c) {
    if (c.outer() != null) {
      clb.with(NestHostAttribute.of(Descs.of(c.outermost())));
      return;
    }
    List<ClassSymbol> members = nestMembers.getOrDefault(c, List.of());
    if (!members.isEmpty()) {
      List<ClassDesc> ds = new ArrayList<>();
      for (ClassSymbol m : members) {
        ds.add(Descs.of(m));
      }
      clb.with(NestMembersAttribute.ofSymbols(ds));
    }
  }

  // ------------------------------------------------------------------ fields

  private void field(ClassBuilder clb, ClassSymbol c, FieldSymbol f) {
    int flags = (int) (f.flags() & Flags.JVM_MASK);
    if (c.has(Flags.MODULE) && Flags.is(f.flags(), Flags.PRIVATE)) {
      flags &= ~ClassFile.ACC_PRIVATE; // file-private top-level values
    }
    Type t = f.type() == null ? syms.objectType() : f.type();
    int finalFlags = flags;
    clb.withField(
        f.name(),
        Descs.of(t),
        fb -> {
          fb.withFlags(finalFlags);
          if (Descs.isGeneric(t)) {
            fb.with(SignatureAttribute.of(Signature.parseFrom(Descs.signature(t))));
          }
          if (f.constantValue() != null && f.isStatic() && f.has(Flags.FINAL)) {
            fb.with(ConstantValueAttribute.of(constantDesc(f.constantValue())));
          }
          if (t.isReference() && t.nullness() == Nullness.NULLABLE) {
            fb.with(
                RuntimeVisibleTypeAnnotationsAttribute.of(
                    TypeAnnotation.of(
                        TypeAnnotation.TargetInfo.ofField(), List.of(), Annotation.of(NULLABLE))));
          }
        });
  }

  private static ConstantDesc constantDesc(Object v) {
    return switch (v) {
      case Boolean b -> b ? 1 : 0;
      case Character ch -> (int) ch;
      case Byte b -> (int) b;
      case Short s -> (int) s;
      case Integer i -> i;
      case Long l -> l;
      case Float fl -> fl;
      case Double d -> d;
      default -> v.toString();
    };
  }

  // ------------------------------------------------------------------ methods

  private int methodFlags(ClassSymbol c, MethodSymbol m) {
    long f = m.flags();
    int out =
        (int)
            (f
                & (Flags.PUBLIC
                    | Flags.PRIVATE
                    | Flags.PROTECTED
                    | Flags.STATIC
                    | Flags.FINAL
                    | Flags.SYNCHRONIZED
                    | Flags.NATIVE
                    | Flags.ABSTRACT
                    | Flags.SYNTHETIC));
    if (Flags.is(f, Flags.BRIDGE)) {
      out |= ClassFile.ACC_BRIDGE;
    }
    if (m.isVarargs()) {
      out |= ClassFile.ACC_VARARGS;
    }
    if (c.has(Flags.MODULE) && Flags.is(f, Flags.PRIVATE)) {
      out &= ~ClassFile.ACC_PRIVATE; // file-private top-level functions
    }
    if (c.isInterface()) {
      out &= ~ClassFile.ACC_FINAL;
      if ((out & ClassFile.ACC_PRIVATE) == 0) {
        out |= ClassFile.ACC_PUBLIC;
        out &= ~ClassFile.ACC_PROTECTED;
      }
    }
    if (m.name().equals("<clinit>")) {
      out = ClassFile.ACC_STATIC;
    }
    if ((out & ClassFile.ACC_ABSTRACT) != 0) {
      out &= ~(ClassFile.ACC_FINAL | ClassFile.ACC_SYNCHRONIZED);
    }
    return out;
  }

  private void method(ClassBuilder clb, ClassSymbol c, BClass.Method bm) {
    MethodSymbol m = bm.sym();
    int flags = methodFlags(c, m);
    MethodTypeDesc desc = Descs.of(m);
    SourceFile file = c.outermost().unit() != null ? c.outermost().unit().file() : null;
    clb.withMethod(
        m.jvmName(),
        desc,
        flags,
        mb -> {
          // Synthetic methods (lambda bodies, bridges) get no Signature, like javac: theirs could
          // mention type variables of the enclosing generic method.
          if (!m.name().equals("<clinit>")
              && !m.has(Flags.SYNTHETIC)
              && Descs.methodNeedsSignature(m)) {
            mb.with(SignatureAttribute.of(MethodSignature.parseFrom(Descs.methodSignature(m))));
          }
          if (!m.params().isEmpty() && !m.name().equals("<clinit>")) {
            List<MethodParameterInfo> infos = new ArrayList<>();
            for (MethodSymbol.Param p : m.params()) {
              boolean synthetic =
                  p.name().startsWith("$")
                      || p.name().startsWith("this$")
                      || p.name().startsWith("val$");
              infos.add(
                  MethodParameterInfo.ofParameter(
                      Optional.of(p.name().replace('$', '_')),
                      synthetic ? ClassFile.ACC_SYNTHETIC : 0));
            }
            mb.with(MethodParametersAttribute.of(infos));
          }
          List<Annotation> methodAnns = new ArrayList<>();
          if (m.isExtension()) {
            methodAnns.add(Annotation.of(EXTENSION));
          }
          if (m.has(Flags.OPERATOR)) {
            String symbol =
                io.github.matrixidot.jsharp.compiler.ast.Operators.symbolOf(
                    m.name(), m.params().size());
            methodAnns.add(
                Annotation.of(
                    OPERATOR, AnnotationElement.ofString("value", String.valueOf(symbol))));
          }
          if (!methodAnns.isEmpty()) {
            mb.with(RuntimeVisibleAnnotationsAttribute.of(methodAnns));
          }
          nullnessAnnotations(mb, m);
          defaultValues(mb, m);
          if (Flags.is(flags, Flags.ABSTRACT) || Flags.is(flags, Flags.NATIVE)) {
            return;
          }
          if (bm.body() == null && isRecordObjectMethod(c, m)) {
            mb.withCode(cb -> recordObjectMethod(cb, c, m));
            return;
          }
          if (bm.body() == null) {
            throw new IllegalStateException(
                "method without body: "
                    + c.binaryName()
                    + "."
                    + m.jvmName()
                    + desc.descriptorString());
          }
          mb.withCode(
              cb -> {
                Type ret =
                    m.isConstructor() || m.name().equals("<clinit>")
                        ? PrimType.VOID
                        : m.returnType();
                if (m.jvmReturnType() != null) {
                  ret = m.jvmReturnType();
                }
                CodeGen g = new CodeGen(cb, types, c, file, ret, m.isStatic(), bm.params(), debug);
                g.body(bm.body(), bm.span() == null ? Span.NONE : bm.span());
                g.finish();
              });
        });
  }

  private void nullnessAnnotations(java.lang.classfile.MethodBuilder mb, MethodSymbol m) {
    List<TypeAnnotation> anns = new ArrayList<>();
    if (!m.isConstructor()
        && m.returnType() != null
        && m.returnType().isReference()
        && m.returnType().nullness() == Nullness.NULLABLE) {
      anns.add(
          TypeAnnotation.of(
              TypeAnnotation.TargetInfo.ofMethodReturn(), List.of(), Annotation.of(NULLABLE)));
    }
    for (int i = 0; i < m.params().size(); i++) {
      Type t = m.params().get(i).type();
      if (t != null && t.isReference() && t.nullness() == Nullness.NULLABLE) {
        anns.add(
            TypeAnnotation.of(
                TypeAnnotation.TargetInfo.ofMethodFormalParameter(i),
                List.of(),
                Annotation.of(NULLABLE)));
      }
    }
    if (!anns.isEmpty()) {
      mb.with(RuntimeVisibleTypeAnnotationsAttribute.of(anns));
    }
  }

  /**
   * {@code @DefaultValue} parameter annotations so J# callers in other compilations see defaults.
   */
  private void defaultValues(java.lang.classfile.MethodBuilder mb, MethodSymbol m) {
    boolean any = m.params().stream().anyMatch(MethodSymbol.Param::hasDefault);
    if (!any) {
      return;
    }
    List<List<Annotation>> perParam = new ArrayList<>();
    for (MethodSymbol.Param p : m.params()) {
      if (!p.hasDefault()) {
        perParam.add(List.of());
        continue;
      }
      Object v = p.defaultValue();
      if (v == null) {
        perParam.add(List.of(Annotation.of(DEFAULT_VALUE)));
        continue;
      }
      AnnotationValue av =
          switch (v) {
            case Integer i -> AnnotationValue.ofInt(i);
            case Long l -> AnnotationValue.ofLong(l);
            case Double d -> AnnotationValue.ofDouble(d);
            case Float f -> AnnotationValue.ofFloat(f);
            case Boolean b -> AnnotationValue.ofBoolean(b);
            case Character ch -> AnnotationValue.ofChar(ch);
            case Byte b -> AnnotationValue.ofByte(b);
            case Short s -> AnnotationValue.ofShort(s);
            default -> AnnotationValue.ofString(v.toString());
          };
      perParam.add(List.of(Annotation.of(DEFAULT_VALUE, AnnotationElement.of("value", av))));
    }
    mb.with(RuntimeInvisibleParameterAnnotationsAttribute.of(perParam));
  }

  // ------------------------------------------------------------------ records

  private static boolean isRecordObjectMethod(ClassSymbol c, MethodSymbol m) {
    return c.isRecord()
        && m.has(Flags.RECORD)
        && m.has(Flags.GENERATED)
        && (m.name().equals("toString")
            || m.name().equals("hashCode")
            || m.name().equals("equals"));
  }

  private static final DirectMethodHandleDesc OBJECT_METHODS =
      MethodHandleDesc.ofMethod(
          DirectMethodHandleDesc.Kind.STATIC,
          ClassDesc.of("java.lang.runtime.ObjectMethods"),
          "bootstrap",
          MethodTypeDesc.of(
              ConstantDescs.CD_Object,
              ConstantDescs.CD_MethodHandles_Lookup,
              ConstantDescs.CD_String,
              ClassDesc.of("java.lang.invoke.TypeDescriptor"),
              ConstantDescs.CD_Class,
              ConstantDescs.CD_String,
              ConstantDescs.CD_MethodHandle.arrayType()));

  /** toString/hashCode/equals of a record via {@code ObjectMethods} (like javac). */
  private void recordObjectMethod(
      java.lang.classfile.CodeBuilder cb, ClassSymbol c, MethodSymbol m) {
    ClassDesc self = Descs.of(c);
    List<ConstantDesc> args = new ArrayList<>();
    args.add(self);
    StringBuilder names = new StringBuilder();
    for (FieldSymbol f : c.recordComponents()) {
      if (!names.isEmpty()) {
        names.append(';');
      }
      names.append(f.name());
    }
    args.add(names.toString());
    for (FieldSymbol f : c.recordComponents()) {
      args.add(
          MethodHandleDesc.ofField(
              DirectMethodHandleDesc.Kind.GETTER, self, f.name(), Descs.of(f.type())));
    }
    cb.aload(0);
    MethodTypeDesc type;
    switch (m.name()) {
      case "equals" -> {
        cb.aload(1);
        type = MethodTypeDesc.of(ConstantDescs.CD_boolean, self, ConstantDescs.CD_Object);
      }
      case "hashCode" -> type = MethodTypeDesc.of(ConstantDescs.CD_int, self);
      default -> type = MethodTypeDesc.of(ConstantDescs.CD_String, self);
    }
    cb.invokedynamic(
        DynamicCallSiteDesc.of(OBJECT_METHODS, m.name(), type, args.toArray(ConstantDesc[]::new)));
    switch (m.name()) {
      case "equals" -> cb.ireturn();
      case "hashCode" -> cb.ireturn();
      default -> cb.areturn();
    }
  }

  /** Unused: kept for future parameter-variable debugging. */
  static String describe(VarSymbol v) {
    return v.name();
  }
}

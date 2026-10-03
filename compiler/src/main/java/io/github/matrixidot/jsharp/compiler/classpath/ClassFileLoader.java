package io.github.matrixidot.jsharp.compiler.classpath;

import io.github.matrixidot.jsharp.compiler.ast.TypeParam;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symtab;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.constantpool.ClassEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds {@link ClassSymbol}s from class files with the JDK ClassFile API, lazily: the header on
 * first use of the symbol, members on first member lookup. Reads generic {@code Signature}
 * attributes, nullness annotations (declaration and type-use), records, sealed hierarchies, inner
 * class structure, parameter names and J# metadata.
 */
public final class ClassFileLoader implements ClassSymbol.Completer {
  static final String JSHARP_METADATA = "Ljsharp/lang/Metadata;";
  static final String JSHARP_EXTENSION = "Ljsharp/lang/Extension;";
  static final String JSHARP_DEFAULT = "Ljsharp/lang/DefaultValue;";

  private final Symtab syms;
  private final Map<ClassSymbol, ClassModel> models = new HashMap<>();

  public ClassFileLoader(Symtab syms) {
    this.syms = syms;
  }

  /** Creates an (incomplete) symbol for a class file. */
  public ClassSymbol create(String internalName, byte[] bytes) {
    ClassModel m = ClassFile.of().parse(bytes);
    int slash = internalName.lastIndexOf('/');
    String pkg = slash < 0 ? "" : internalName.substring(0, slash).replace('/', '.');
    String simple = internalName.substring(slash + 1);
    for (InnerClassInfo info : innerClasses(m)) {
      if (info.innerClass().asInternalName().equals(internalName)) {
        simple = info.innerName().map(n -> n.stringValue()).orElse(simple);
      }
    }
    ClassSymbol c = new ClassSymbol(internalName, simple, pkg, this);
    models.put(c, m);
    return c;
  }

  private static List<InnerClassInfo> innerClasses(ClassModel m) {
    return m.findAttribute(Attributes.innerClasses()).map(a -> a.classes()).orElse(List.of());
  }

  // ------------------------------------------------------------------ header

  @Override
  public void completeHeader(ClassSymbol c) {
    ClassModel m = models.get(c);
    if (m == null) {
      return;
    }
    long flags = m.flags().flagsMask();
    for (InnerClassInfo info : innerClasses(m)) {
      String inner = info.innerClass().asInternalName();
      if (inner.equals(c.binaryName())) {
        flags = info.flagsMask() | (flags & Flags.SYNTHETIC);
        if (info.outerClass().isPresent()) {
          c.setOuter(syms.lookup(info.outerClass().get().asInternalName()));
        } else {
          flags |= Flags.LOCAL;
        }
      } else if (info.outerClass().isPresent()
          && info.outerClass().get().asInternalName().equals(c.binaryName())
          && info.innerName().isPresent()) {
        ClassSymbol member = syms.lookup(inner);
        if (member != null) {
          c.addMemberType(member);
        }
      }
    }
    for (Annotation a : classAnnotations(m)) {
      String d = a.className().stringValue();
      switch (d) {
        case JSHARP_METADATA -> {
          flags |= Flags.JSHARP;
          for (var e : a.elements()) {
            if (e.name().stringValue().equals("module")
                && e.value() instanceof java.lang.classfile.AnnotationValue.OfBoolean b
                && b.booleanValue()) {
              flags |= Flags.MODULE;
            }
          }
        }
        case "Ljava/lang/FunctionalInterface;" -> flags |= Flags.FUNCTIONAL;
        case "Ljava/lang/Deprecated;" -> flags |= Flags.DEPRECATED;
        default -> {}
      }
    }
    if (m.findAttribute(Attributes.record()).isPresent()) {
      flags |= Flags.RECORD;
    }
    var permitted = m.findAttribute(Attributes.permittedSubclasses());
    if (permitted.isPresent()) {
      flags |= Flags.SEALED;
    }
    c.setFlags(flags);

    Map<String, TypeVarSymbol> scope = new HashMap<>(outerTypeVars(c));
    var sig = m.findAttribute(Attributes.signature());
    if (sig.isPresent()) {
      ClassSignature cs = sig.get().asClassSignature();
      List<TypeVarSymbol> tvs = declareTypeParams(cs.typeParameters(), c, scope);
      c.setTypeParams(tvs);
      resolveBounds(cs.typeParameters(), tvs, scope);
      ClassType sup = (ClassType) toTypeOrNull(cs.superclassSignature(), scope, Nullness.NON_NULL);
      List<ClassType> ifaces = new ArrayList<>();
      for (Signature.ClassTypeSig s : cs.superinterfaceSignatures()) {
        if (toTypeOrNull(s, scope, Nullness.NON_NULL) instanceof ClassType ct) {
          ifaces.add(ct);
        }
      }
      setSupers(c, sup, ifaces);
    } else {
      ClassType sup =
          m.superclass().map(e -> classTypeOf(e.asInternalName(), Nullness.NON_NULL)).orElse(null);
      List<ClassType> ifaces = new ArrayList<>();
      for (ClassEntry e : m.interfaces()) {
        ClassType t = classTypeOf(e.asInternalName(), Nullness.NON_NULL);
        if (t != null) {
          ifaces.add(t);
        }
      }
      setSupers(c, sup, ifaces);
    }
    if (permitted.isPresent()) {
      List<ClassSymbol> ps = new ArrayList<>();
      for (ClassEntry e : permitted.get().permittedSubclasses()) {
        ClassSymbol p = syms.lookup(e.asInternalName());
        if (p != null) {
          ps.add(p);
        }
      }
      c.setPermitted(ps);
    }
  }

  private static void setSupers(ClassSymbol c, ClassType sup, List<ClassType> ifaces) {
    // J# models interfaces without a superclass (the JVM records Object).
    c.setSuperclass(Flags.is(c.rawFlags(), Flags.INTERFACE) ? null : sup);
    c.setInterfaces(ifaces);
  }

  private static List<Annotation> classAnnotations(ClassModel m) {
    List<Annotation> out = new ArrayList<>();
    m.findAttribute(Attributes.runtimeVisibleAnnotations())
        .ifPresent(a -> out.addAll(a.annotations()));
    m.findAttribute(Attributes.runtimeInvisibleAnnotations())
        .ifPresent(a -> out.addAll(a.annotations()));
    return out;
  }

  /** Type variables of enclosing classes visible in a non-static nested class. */
  private Map<String, TypeVarSymbol> outerTypeVars(ClassSymbol c) {
    Map<String, TypeVarSymbol> out = new HashMap<>();
    List<ClassSymbol> chain = new ArrayList<>();
    ClassSymbol cur = c;
    while (cur.outer() != null && !Flags.is(cur.rawFlags(), Flags.STATIC)) {
      cur = cur.outer();
      chain.add(cur);
    }
    for (int i = chain.size() - 1; i >= 0; i--) {
      for (TypeVarSymbol tv : chain.get(i).typeParams()) {
        out.put(tv.name(), tv);
      }
    }
    return out;
  }

  private List<TypeVarSymbol> declareTypeParams(
      List<Signature.TypeParam> params,
      io.github.matrixidot.jsharp.compiler.symbols.Symbol owner,
      Map<String, TypeVarSymbol> scope) {
    List<TypeVarSymbol> tvs = new ArrayList<>();
    for (int i = 0; i < params.size(); i++) {
      TypeVarSymbol tv =
          new TypeVarSymbol(params.get(i).identifier(), owner, i, TypeParam.Variance.INVARIANT);
      tvs.add(tv);
      scope.put(tv.name(), tv);
    }
    return tvs;
  }

  private void resolveBounds(
      List<Signature.TypeParam> params, List<TypeVarSymbol> tvs, Map<String, TypeVarSymbol> scope) {
    for (int i = 0; i < params.size(); i++) {
      Signature.TypeParam p = params.get(i);
      List<Type> bounds = new ArrayList<>();
      p.classBound().ifPresent(b -> addBound(bounds, b, scope));
      for (Signature.RefTypeSig b : p.interfaceBounds()) {
        addBound(bounds, b, scope);
      }
      if (bounds.isEmpty()) {
        bounds.add(syms.objectType());
      }
      tvs.get(i).setBounds(bounds, bounds.getFirst().erasure());
    }
  }

  private void addBound(
      List<Type> bounds, Signature.RefTypeSig b, Map<String, TypeVarSymbol> scope) {
    Type t = toTypeOrNull(b, scope, Nullness.NON_NULL);
    if (t != null && !t.isError()) {
      bounds.add(t);
    }
  }

  // ------------------------------------------------------------------ members

  @Override
  public void completeMembers(ClassSymbol c) {
    ClassModel m = models.remove(c);
    if (m == null) {
      return;
    }
    boolean nullMarked = c.has(Flags.JSHARP);
    for (Annotation a : classAnnotations(m)) {
      if (NullnessAnnotations.isNullMarked(a.className().stringValue())) {
        nullMarked = true;
      }
    }
    Nullness unannotated = nullMarked ? Nullness.NON_NULL : Nullness.PLATFORM;
    Map<String, TypeVarSymbol> classScope = new HashMap<>(outerTypeVars(c));
    for (TypeVarSymbol tv : c.typeParams()) {
      classScope.put(tv.name(), tv);
    }
    for (FieldModel f : m.fields()) {
      loadField(c, f, classScope, unannotated);
    }
    for (MethodModel mm : m.methods()) {
      loadMethod(c, mm, classScope, unannotated);
    }
    m.findAttribute(Attributes.record())
        .ifPresent(
            r -> {
              for (RecordComponentInfo rc : r.components()) {
                FieldSymbol f = c.field(rc.name().stringValue());
                if (f != null) {
                  c.addRecordComponent(f);
                }
              }
            });
  }

  private void loadField(
      ClassSymbol c, FieldModel f, Map<String, TypeVarSymbol> scope, Nullness unannotated) {
    long flags = f.flags().flagsMask();
    if (Flags.is(flags, Flags.SYNTHETIC)) {
      return;
    }
    Nullness top = unannotated;
    List<Annotation> anns = new ArrayList<>();
    f.findAttribute(Attributes.runtimeVisibleAnnotations())
        .ifPresent(a -> anns.addAll(a.annotations()));
    f.findAttribute(Attributes.runtimeInvisibleAnnotations())
        .ifPresent(a -> anns.addAll(a.annotations()));
    Nullness declared = nullnessOf(anns);
    Nullness typeUse =
        typeUseNullness(
            f.findAttribute(Attributes.runtimeVisibleTypeAnnotations())
                .map(a -> a.annotations())
                .orElse(List.of()),
            -2);
    if (typeUse == null) {
      typeUse =
          typeUseNullness(
              f.findAttribute(Attributes.runtimeInvisibleTypeAnnotations())
                  .map(a -> a.annotations())
                  .orElse(List.of()),
              -2);
    }
    if (typeUse != null) {
      top = typeUse;
    } else if (declared != null) {
      top = declared;
    }
    if (Flags.is(flags, Flags.ENUM)) {
      top = Nullness.NON_NULL;
      flags |= Flags.ENUM_CONSTANT;
    }
    Type type;
    var sig = f.findAttribute(Attributes.signature());
    if (sig.isPresent()) {
      type = toType(sig.get().asTypeSignature(), scope, top, unannotated);
    } else {
      type = toType(Signature.parseFrom(f.fieldType().stringValue()), scope, top, unannotated);
    }
    FieldSymbol fs = new FieldSymbol(f.fieldName().stringValue(), c, flags, type);
    f.findAttribute(Attributes.constantValue())
        .ifPresent(cv -> fs.setConstantValue(constantFor(cv.constant().constantValue(), type)));
    if (Flags.is(flags, Flags.DEPRECATED) || hasAnnotation(anns, "Ljava/lang/Deprecated;")) {
      fs.addFlags(Flags.DEPRECATED);
    }
    c.addField(fs);
  }

  private static Object constantFor(Object raw, Type type) {
    // ConstantValue stores booleans/chars/bytes/shorts as ints.
    if (raw instanceof Integer i && type instanceof PrimType p) {
      return switch (p) {
        case BOOLEAN -> i != 0;
        case CHAR -> (char) i.intValue();
        case BYTE -> (byte) i.intValue();
        case SHORT -> (short) i.intValue();
        default -> i;
      };
    }
    return raw;
  }

  private void loadMethod(
      ClassSymbol c, MethodModel mm, Map<String, TypeVarSymbol> classScope, Nullness unannotated) {
    long flags = mm.flags().flagsMask();
    String name = mm.methodName().stringValue();
    if (Flags.is(flags, Flags.SYNTHETIC)
        || Flags.is(flags, Flags.BRIDGE)
        || name.equals("<clinit>")) {
      return;
    }
    MethodSymbol ms = new MethodSymbol(name, c, flags);
    List<Annotation> anns = new ArrayList<>();
    mm.findAttribute(Attributes.runtimeVisibleAnnotations())
        .ifPresent(a -> anns.addAll(a.annotations()));
    mm.findAttribute(Attributes.runtimeInvisibleAnnotations())
        .ifPresent(a -> anns.addAll(a.annotations()));
    if (hasAnnotation(anns, JSHARP_EXTENSION)) {
      ms.addFlags(Flags.EXTENSION);
    }
    if (hasAnnotation(anns, "Ljava/lang/Deprecated;")) {
      ms.addFlags(Flags.DEPRECATED);
    }
    boolean noReturn = hasAnnotation(anns, "Ljsharp/lang/NoReturn;");
    List<TypeAnnotation> typeAnns = new ArrayList<>();
    mm.findAttribute(Attributes.runtimeVisibleTypeAnnotations())
        .ifPresent(a -> typeAnns.addAll(a.annotations()));
    mm.findAttribute(Attributes.runtimeInvisibleTypeAnnotations())
        .ifPresent(a -> typeAnns.addAll(a.annotations()));
    List<List<Annotation>> paramAnns = new ArrayList<>();
    mm.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
        .ifPresent(a -> mergeParamAnns(paramAnns, a.parameterAnnotations()));
    mm.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
        .ifPresent(a -> mergeParamAnns(paramAnns, a.parameterAnnotations()));

    Map<String, TypeVarSymbol> scope = new HashMap<>(classScope);
    List<Signature> paramSigs;
    Signature resultSig;
    var sig = mm.findAttribute(Attributes.signature());
    List<Signature.TypeParam> tps = List.of();
    if (sig.isPresent()) {
      MethodSignature ms2 = sig.get().asMethodSignature();
      tps = ms2.typeParameters();
      paramSigs = ms2.arguments();
      resultSig = ms2.result();
    } else {
      MethodSignature ms2 = MethodSignature.parseFrom(mm.methodType().stringValue());
      paramSigs = ms2.arguments();
      resultSig = ms2.result();
    }
    List<TypeVarSymbol> tvs = declareTypeParams(tps, ms, scope);
    ms.setTypeParams(tvs);
    resolveBounds(tps, tvs, scope);

    Nullness retNull = pick(typeUseNullness(typeAnns, -1), nullnessOf(anns), unannotated);
    Type declaredRet = toType(resultSig, scope, retNull, unannotated);
    ms.setReturnType(noReturn ? Type.NeverType.INSTANCE : declaredRet);
    if (noReturn) {
      ms.setJvmReturnType(declaredRet);
    }

    List<String> names = new ArrayList<>();
    mm.findAttribute(Attributes.methodParameters())
        .ifPresent(
            mp -> {
              for (MethodParameterInfo pi : mp.parameters()) {
                if (!Flags.is(pi.flagsMask(), Flags.SYNTHETIC)
                    && !Flags.is(pi.flagsMask(), Flags.MANDATED)) {
                  names.add(pi.name().map(n -> n.stringValue()).orElse(null));
                }
              }
            });
    // Descriptor-only constructors of enums/inner classes carry synthetic leading parameters that
    // the Signature attribute omits; drop them so arity matches the source view.
    int skip = 0;
    if (name.equals(MethodSymbol.CONSTRUCTOR) && sig.isEmpty()) {
      if (c.has(Flags.ENUM)) {
        skip = Math.min(2, paramSigs.size());
      } else if (c.outer() != null && !c.has(Flags.STATIC) && !c.has(Flags.INTERFACE)) {
        skip = Math.min(1, paramSigs.size());
      }
    }
    List<MethodSymbol.Param> params = new ArrayList<>();
    int n = paramSigs.size() - skip;
    int annOffset = paramAnns.size() > n ? paramAnns.size() - n : 0;
    for (int i = 0; i < n; i++) {
      List<Annotation> pa =
          i + annOffset < paramAnns.size() ? paramAnns.get(i + annOffset) : List.of();
      Nullness pn = pick(typeUseNullness(typeAnns, i), nullnessOf(pa), unannotated);
      Type t = toType(paramSigs.get(i + skip), scope, pn, unannotated);
      String pname = i < names.size() && names.get(i) != null ? names.get(i) : "arg" + i;
      boolean varargs = Flags.is(flags, Flags.VARARGS) && i == n - 1;
      MethodSymbol.Param p = new MethodSymbol.Param(pname, t, varargs, null, false, null);
      for (Annotation a : pa) {
        if (a.className().stringValue().equals(JSHARP_DEFAULT)) {
          p = p.withDefault(defaultValue(a, t));
        }
      }
      params.add(p);
    }
    ms.setParams(params);
    if (Flags.is(c.rawFlags(), Flags.INTERFACE)
        && !Flags.is(flags, Flags.ABSTRACT)
        && !Flags.is(flags, Flags.STATIC)) {
      ms.addFlags(Flags.DEFAULT);
    }
    c.addMethod(ms);
  }

  private static void mergeParamAnns(List<List<Annotation>> into, List<List<Annotation>> from) {
    for (int i = 0; i < from.size(); i++) {
      if (into.size() <= i) {
        into.add(new ArrayList<>());
      }
      List<Annotation> l = new ArrayList<>(into.get(i));
      l.addAll(from.get(i));
      into.set(i, l);
    }
  }

  private static Object defaultValue(Annotation a, Type t) {
    for (AnnotationElement e : a.elements()) {
      if (e.name().stringValue().equals("value")) {
        return switch (e.value()) {
          case AnnotationValue.OfInt v -> constantFor(v.intValue(), t);
          case AnnotationValue.OfLong v -> v.longValue();
          case AnnotationValue.OfFloat v -> v.floatValue();
          case AnnotationValue.OfDouble v -> v.doubleValue();
          case AnnotationValue.OfBoolean v -> v.booleanValue();
          case AnnotationValue.OfChar v -> v.charValue();
          case AnnotationValue.OfByte v -> v.byteValue();
          case AnnotationValue.OfShort v -> v.shortValue();
          case AnnotationValue.OfString v -> v.stringValue();
          default -> null;
        };
      }
    }
    return null; // `@DefaultValue` without value: default is null
  }

  private static Nullness pick(Nullness typeUse, Nullness declared, Nullness fallback) {
    if (typeUse != null) {
      return typeUse;
    }
    return declared != null ? declared : fallback;
  }

  private static boolean hasAnnotation(List<Annotation> anns, String descriptor) {
    for (Annotation a : anns) {
      if (a.className().stringValue().equals(descriptor)) {
        return true;
      }
    }
    return false;
  }

  private static Nullness nullnessOf(List<Annotation> anns) {
    for (Annotation a : anns) {
      Nullness n = NullnessAnnotations.classify(a.className().stringValue());
      if (n != null) {
        return n;
      }
    }
    return null;
  }

  /**
   * Nullness from type-use annotations on the top-level type of a return value ({@code param ==
   * -1}), a field ({@code -2}) or a formal parameter (index).
   */
  private static Nullness typeUseNullness(List<TypeAnnotation> anns, int param) {
    for (TypeAnnotation ta : anns) {
      if (!ta.targetPath().isEmpty()) {
        continue;
      }
      boolean matches =
          switch (ta.targetInfo()) {
            case TypeAnnotation.FormalParameterTarget fp -> fp.formalParameterIndex() == param;
            case TypeAnnotation.EmptyTarget e ->
                (param == -1 && e.targetType() == TypeAnnotation.TargetType.METHOD_RETURN)
                    || (param == -2 && e.targetType() == TypeAnnotation.TargetType.FIELD);
            default -> false;
          };
      if (matches) {
        Nullness n = NullnessAnnotations.classify(ta.annotation().className().stringValue());
        if (n != null) {
          return n;
        }
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ signatures -> types

  private ClassType classTypeOf(String internalName, Nullness n) {
    ClassSymbol s = syms.lookup(internalName);
    return s == null ? null : new ClassType(s, List.of(), n);
  }

  private Type toTypeOrNull(Signature sig, Map<String, TypeVarSymbol> scope, Nullness top) {
    Type t = toType(sig, scope, top, Nullness.PLATFORM);
    return t.isError() ? null : t;
  }

  /**
   * Converts a signature to a type.
   *
   * @param top nullness of the outermost type
   * @param deep nullness of nested types (type arguments, array elements)
   */
  Type toType(Signature sig, Map<String, TypeVarSymbol> scope, Nullness top, Nullness deep) {
    return switch (sig) {
      case Signature.BaseTypeSig b ->
          switch (b.baseType()) {
            case 'Z' -> PrimType.BOOLEAN;
            case 'B' -> PrimType.BYTE;
            case 'S' -> PrimType.SHORT;
            case 'C' -> PrimType.CHAR;
            case 'I' -> PrimType.INT;
            case 'J' -> PrimType.LONG;
            case 'F' -> PrimType.FLOAT;
            case 'D' -> PrimType.DOUBLE;
            default -> PrimType.VOID;
          };
      case Signature.ArrayTypeSig a ->
          new Type.ArrayType(toType(a.componentSignature(), scope, deep, deep), top);
      case Signature.TypeVarSig tv -> {
        TypeVarSymbol s = scope.get(tv.identifier());
        yield s == null
            ? syms.objectType().withNullness(top)
            : new Type.TypeVar(s, top == Nullness.PLATFORM ? Nullness.PLATFORM : top);
      }
      case Signature.ClassTypeSig ct -> {
        String bn = binaryName(ct);
        ClassSymbol s = syms.lookup(bn);
        if (s == null) {
          yield Type.ErrorType.INSTANCE;
        }
        List<Type> args = new ArrayList<>();
        for (Signature.TypeArg ta : ct.typeArgs()) {
          args.add(
              switch (ta) {
                case Signature.TypeArg.Unbounded u ->
                    new Type.WildcardType(Type.WildcardType.Kind.UNBOUNDED, null);
                case Signature.TypeArg.Bounded bd -> {
                  Type bound = toType(bd.boundType(), scope, deep, deep);
                  yield switch (bd.wildcardIndicator()) {
                    case NONE -> bound;
                    case EXTENDS -> new Type.WildcardType(Type.WildcardType.Kind.EXTENDS, bound);
                    case SUPER -> new Type.WildcardType(Type.WildcardType.Kind.SUPER, bound);
                  };
                }
              });
        }
        // jsharp.core.TupleN<A, B, ...> is how a J# tuple type (A, B, ...) is erased; read it back
        // as a tuple so `t.item1` and deconstruction work across compilations.
        if (bn.matches("jsharp/core/Tuple[2-8]")
            && args.size() == bn.charAt(bn.length() - 1) - '0'
            && args.stream().noneMatch(x -> x instanceof Type.WildcardType)) {
          List<String> names = new ArrayList<>();
          for (int i = 0; i < args.size(); i++) {
            names.add(null);
          }
          yield new Type.TupleType(args, names, s, top);
        }
        yield new ClassType(s, args, top);
      }
      default -> Type.ErrorType.INSTANCE;
    };
  }

  private static String binaryName(Signature.ClassTypeSig ct) {
    Optional<Signature.ClassTypeSig> outer = ct.outerType();
    return outer.map(o -> binaryName(o) + "$" + ct.className()).orElse(ct.className());
  }
}

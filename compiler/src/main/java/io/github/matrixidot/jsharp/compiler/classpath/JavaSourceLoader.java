package io.github.matrixidot.jsharp.compiler.classpath;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import io.github.matrixidot.jsharp.compiler.ast.TypeParam;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostics;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symtab;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ArrayType;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Modifier;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Reads the declarations of the Java source files compiled together with J# sources, so J# code can
 * use Java classes of its own module before javac compiles them (D082).
 *
 * <p>Only signatures are read, with javac's parser and no attribution: classes, type parameters,
 * supertypes, fields (with constant values), methods and constructors, including the members Java
 * declares implicitly (default constructors, record accessors and canonical constructors, enum
 * {@code values}/{@code valueOf}). Names resolve with Java's scoping rules. Unannotated types are
 * platform types, as for Java class files. Bodies are compiled later by javac, against the J# class
 * files.
 */
public final class JavaSourceLoader implements ClassSymbol.Completer {
  private final Symtab syms;
  private final Diagnostics diags;
  private final Map<ClassSymbol, Info> infos = new IdentityHashMap<>();
  private final Map<MethodSymbol, Span> methodSpans = new IdentityHashMap<>();
  private SourcePositions positions;

  /** One parsed Java file and its name-resolution context. */
  private record FileCtx(
      SourceFile file,
      CompilationUnitTree unit,
      String pkg,
      List<? extends ImportTree> imports,
      List<ClassSymbol> topLevel,
      List<ClassSymbol> all) {}

  /** What a Java source class was declared from. */
  private record Info(ClassTree tree, FileCtx file) {}

  public JavaSourceLoader(Symtab syms, Diagnostics diags) {
    this.syms = syms;
    this.diags = diags;
  }

  /** True for the name of a Java source file. */
  public static boolean isJava(SourceFile f) {
    return f.path().endsWith(".java");
  }

  /**
   * Parses {@code files} and enters their classes (including nested ones) into the symbol table.
   * Java syntax errors are reported as JS1000. Returns the top-level classes.
   */
  public List<ClassSymbol> load(List<SourceFile> files) {
    if (files.isEmpty()) {
      return List.of();
    }
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    if (javac == null) {
      Diagnostic.error(
              Code.JAVA_ERROR,
              files.getFirst(),
              new Span(0, 0),
              "compiling Java sources needs a JDK, but this is a Java runtime without javac")
          .report(diags);
      return List.of();
    }
    List<JavaFileObject> objects = new ArrayList<>();
    for (SourceFile f : files) {
      objects.add(new InMemorySource(f));
    }
    DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
    JavacTask task =
        (JavacTask) javac.getTask(null, null, collected, List.of("-proc:none"), null, objects);
    positions = Trees.instance(task).getSourcePositions();
    List<ClassSymbol> out = new ArrayList<>();
    try {
      for (CompilationUnitTree unit : task.parse()) {
        SourceFile file = byUri(files).get(unit.getSourceFile().toUri());
        String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        FileCtx ctx =
            new FileCtx(file, unit, pkg, unit.getImports(), new ArrayList<>(), new ArrayList<>());
        for (Tree t : unit.getTypeDecls()) {
          if (t instanceof ClassTree ct) {
            ClassSymbol c = enter(ct, null, ctx);
            if (c != null) {
              ctx.topLevel().add(c);
              out.add(c);
            }
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    reportJavac(collected, diags, files);
    return out;
  }

  private static Map<URI, SourceFile> byUri(List<SourceFile> files) {
    Map<URI, SourceFile> out = new HashMap<>();
    for (SourceFile f : files) {
      out.put(InMemorySource.uriOf(f.path()), f);
    }
    return out;
  }

  /** Copies javac diagnostics about in-memory sources into J# diagnostics. */
  public static void reportJavac(
      DiagnosticCollector<JavaFileObject> collected, Diagnostics diags, List<SourceFile> files) {
    Map<URI, SourceFile> sources = byUri(files);
    for (var d : collected.getDiagnostics()) {
      Code code =
          switch (d.getKind()) {
            case ERROR -> Code.JAVA_ERROR;
            case WARNING, MANDATORY_WARNING -> Code.JAVA_WARNING;
            default -> null;
          };
      if (code == null) {
        continue;
      }
      SourceFile file = d.getSource() == null ? null : sources.get(d.getSource().toUri());
      Span span = new Span(0, 0);
      if (file != null && d.getStartPosition() >= 0) {
        int start = (int) d.getStartPosition();
        int end = (int) Math.max(d.getEndPosition(), start);
        span = new Span(start, end);
      }
      String msg = d.getMessage(java.util.Locale.ROOT);
      int nl = msg.indexOf('\n');
      Diagnostic.Builder b =
          Diagnostic.error(code, file, span, nl < 0 ? msg : msg.substring(0, nl));
      if (nl >= 0) {
        b.help(msg.substring(nl + 1).strip());
      }
      b.report(diags);
    }
  }

  /** Wraps a source file for javac. */
  public static JavaFileObject fileObject(SourceFile f) {
    return new InMemorySource(f);
  }

  /** The span of a Java method's name, for diagnostics about it; null if unknown. */
  public Span spanOf(MethodSymbol m) {
    return methodSpans.get(m);
  }

  private ClassSymbol enter(ClassTree tree, ClassSymbol outer, FileCtx file) {
    String simple = tree.getSimpleName().toString();
    String prefix = file.pkg().isEmpty() ? "" : file.pkg().replace('.', '/') + "/";
    String binary = outer == null ? prefix + simple : outer.binaryName() + "$" + simple;
    Span nameSpan = nameSpan(file, tree, simple);
    if (syms.isDeclaredInSource(binary)) {
      ClassSymbol prev = syms.lookup(binary);
      Diagnostic.error(
              Code.DUPLICATE_TYPE, file.file(), nameSpan, "duplicate class '" + simple + "'")
          .note(
              "first declared here",
              prev.javaFile() != null ? prev.javaFile() : file.file(),
              prev.javaSpan() != null ? prev.javaSpan() : nameSpan)
          .report(diags);
      return null;
    }
    ClassSymbol c = new ClassSymbol(binary, simple, file.pkg(), this);
    c.setJavaOrigin(file.file(), nameSpan);
    c.setOuter(outer);
    if (outer != null) {
      outer.addMemberType(c);
    }
    infos.put(c, new Info(tree, file));
    file.all().add(c);
    syms.enterJavaSource(c);
    for (Tree m : tree.getMembers()) {
      if (m instanceof ClassTree nested) {
        enter(nested, c, file);
      }
    }
    return c;
  }

  /** The span of {@code name} at or after the start of {@code tree}. */
  private Span nameSpan(FileCtx file, Tree tree, String name) {
    long start = positions.getStartPosition(file.unit(), tree);
    if (start < 0) {
      return new Span(0, 0);
    }
    String text = file.file().content();
    int at = start < text.length() ? indexOfWord(text, name, (int) start) : -1;
    return at < 0 ? new Span((int) start, (int) start) : new Span(at, at + name.length());
  }

  private static int indexOfWord(String text, String word, int from) {
    for (int i = text.indexOf(word, from); i >= 0; i = text.indexOf(word, i + 1)) {
      boolean startOk = i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1));
      int end = i + word.length();
      boolean endOk = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
      if (startOk && endOk) {
        return i;
      }
    }
    return -1;
  }

  // ------------------------------------------------------------------ header

  @Override
  public void completeHeader(ClassSymbol c) {
    Info info = infos.get(c);
    if (info == null) {
      return;
    }
    ClassTree tree = info.tree();
    Set<Modifier> mods = tree.getModifiers().getFlags();
    long flags = modifierFlags(tree.getModifiers());
    switch (tree.getKind()) {
      case INTERFACE -> flags |= Flags.INTERFACE | Flags.ABSTRACT;
      case ANNOTATION_TYPE -> flags |= Flags.INTERFACE | Flags.ABSTRACT | Flags.ANNOTATION;
      case ENUM -> {
        flags |= Flags.ENUM;
        if (!hasConstantBodies(tree, c.name())) {
          flags |= Flags.FINAL;
        }
        for (Tree m : tree.getMembers()) {
          if (m instanceof MethodTree mt
              && mt.getModifiers().getFlags().contains(Modifier.ABSTRACT)) {
            flags |= Flags.ABSTRACT;
          }
        }
      }
      case RECORD -> flags |= Flags.RECORD | Flags.FINAL;
      default -> {}
    }
    ClassSymbol outer = c.outer();
    if (outer != null) {
      boolean outerInterface = Flags.is(outer.flags(), Flags.INTERFACE);
      if (tree.getKind() != Tree.Kind.CLASS || outerInterface) {
        flags |= Flags.STATIC;
      }
      if (outerInterface) {
        flags |= Flags.PUBLIC;
      }
    }
    if (mods.contains(Modifier.SEALED)) {
      flags |= Flags.SEALED;
    }
    if (mods.contains(Modifier.NON_SEALED)) {
      flags |= Flags.NON_SEALED;
    }
    for (AnnotationTree a : tree.getModifiers().getAnnotations()) {
      switch (simpleName(a.getAnnotationType())) {
        case "FunctionalInterface" -> flags |= Flags.FUNCTIONAL;
        case "Deprecated" -> flags |= Flags.DEPRECATED;
        default -> {}
      }
    }
    c.setFlags(flags);

    Scope scope = new Scope(c, info.file(), outerTypeVars(c));
    List<TypeVarSymbol> tvs = declareTypeParams(tree.getTypeParameters(), c, scope);
    c.setTypeParams(tvs);
    resolveBounds(tree.getTypeParameters(), tvs, scope);
    ClassType sup =
        switch (tree.getKind()) {
          case ENUM ->
              generic("java/lang/Enum", List.of(c.thisType().withNullness(Nullness.PLATFORM)));
          case RECORD -> generic("java/lang/Record", List.of());
          case INTERFACE, ANNOTATION_TYPE -> null;
          default ->
              tree.getExtendsClause() != null
                      && resolve(tree.getExtendsClause(), scope, Nullness.NON_NULL)
                          instanceof ClassType ct
                  ? ct
                  : syms.objectType();
        };
    List<ClassType> ifaces = new ArrayList<>();
    for (Tree t : tree.getImplementsClause()) {
      if (resolve(t, scope, Nullness.NON_NULL) instanceof ClassType ct) {
        ifaces.add(ct);
      }
    }
    if (tree.getKind() == Tree.Kind.ANNOTATION_TYPE) {
      ifaces.add(generic("java/lang/annotation/Annotation", List.of()));
    }
    c.setSuperclass(sup);
    c.setInterfaces(ifaces);
    if (Flags.is(flags, Flags.SEALED)) {
      c.setPermitted(permitted(c, tree, scope, info.file()));
    }
  }

  /** The permitted subclasses: the permits clause, else the direct subtypes in the same file. */
  private List<ClassSymbol> permitted(ClassSymbol c, ClassTree tree, Scope scope, FileCtx file) {
    List<ClassSymbol> out = new ArrayList<>();
    for (Tree t : tree.getPermitsClause()) {
      if (resolve(t, scope, Nullness.NON_NULL) instanceof ClassType ct) {
        out.add(ct.sym());
      }
    }
    if (!tree.getPermitsClause().isEmpty()) {
      return out;
    }
    for (ClassSymbol other : file.all()) {
      Info oi = infos.get(other);
      if (other == c || oi == null) {
        continue;
      }
      Scope os = new Scope(other, file, new HashMap<>());
      List<Tree> supers = new ArrayList<>(oi.tree().getImplementsClause());
      if (oi.tree().getExtendsClause() != null) {
        supers.add(oi.tree().getExtendsClause());
      }
      for (Tree t : supers) {
        Tree raw = t instanceof ParameterizedTypeTree p ? p.getType() : t;
        if (resolve(raw, os, Nullness.NON_NULL) instanceof ClassType ct && ct.sym() == c) {
          out.add(other);
          break;
        }
      }
    }
    return out;
  }

  private static boolean hasConstantBodies(ClassTree tree, String enumName) {
    for (Tree m : tree.getMembers()) {
      if (m instanceof VariableTree v
          && isEnumConstant(v, enumName)
          && ((NewClassTree) v.getInitializer()).getClassBody() != null) {
        return true;
      }
    }
    return false;
  }

  /** javac parses enum constants as fields initialized with {@code new E(...)}. */
  private static boolean isEnumConstant(VariableTree v, String enumName) {
    // An enum cannot otherwise instantiate itself, so the initializer identifies a constant.
    return v.getInitializer() instanceof NewClassTree n
        && n.getIdentifier() instanceof IdentifierTree id
        && id.getName().contentEquals(enumName);
  }

  private static long modifierFlags(ModifiersTree mods) {
    long f = 0;
    for (Modifier m : mods.getFlags()) {
      f |=
          switch (m) {
            case PUBLIC -> Flags.PUBLIC;
            case PROTECTED -> Flags.PROTECTED;
            case PRIVATE -> Flags.PRIVATE;
            case STATIC -> Flags.STATIC;
            case FINAL -> Flags.FINAL;
            case ABSTRACT -> Flags.ABSTRACT;
            case SYNCHRONIZED -> Flags.SYNCHRONIZED;
            case NATIVE -> Flags.NATIVE;
            case TRANSIENT -> Flags.TRANSIENT;
            case VOLATILE -> Flags.VOLATILE;
            case DEFAULT -> Flags.DEFAULT;
            default -> 0L;
          };
    }
    return f;
  }

  // ------------------------------------------------------------------ members

  @Override
  public void completeMembers(ClassSymbol c) {
    Info info = infos.get(c);
    if (info == null) {
      return;
    }
    ClassTree tree = info.tree();
    Tree.Kind kind = tree.getKind();
    boolean iface = Flags.is(c.rawFlags(), Flags.INTERFACE);
    Map<String, TypeVarSymbol> classVars = outerTypeVars(c);
    for (TypeVarSymbol tv : c.typeParams()) {
      classVars.put(tv.name(), tv);
    }
    Scope scope = new Scope(c, info.file(), classVars);
    Nullness unannotated = nullMarked(c) ? Nullness.NON_NULL : Nullness.PLATFORM;
    if (kind == Tree.Kind.RECORD) {
      // The parser lists record components as the record's instance fields.
      for (Tree m : tree.getMembers()) {
        if (m instanceof VariableTree v && !v.getModifiers().getFlags().contains(Modifier.STATIC)) {
          Type t = resolve(v.getType(), scope, nullnessOf(v.getModifiers(), unannotated));
          FieldSymbol f =
              new FieldSymbol(v.getName().toString(), c, Flags.PRIVATE | Flags.FINAL, t);
          c.addField(f);
          c.addRecordComponent(f);
        }
      }
    }
    boolean hasCtor = false;
    for (Tree m : tree.getMembers()) {
      switch (m) {
        case VariableTree v -> {
          boolean component =
              kind == Tree.Kind.RECORD && !v.getModifiers().getFlags().contains(Modifier.STATIC);
          if (!component) {
            field(c, v, scope, iface, kind == Tree.Kind.ENUM, unannotated);
          }
        }
        case MethodTree mt -> hasCtor |= method(c, mt, scope, iface, info.file(), unannotated);
        default -> {}
      }
    }
    if (kind == Tree.Kind.RECORD) {
      recordMembers(c);
    } else if (!hasCtor && !iface) {
      long access =
          kind == Tree.Kind.ENUM
              ? Flags.PRIVATE
              : c.rawFlags() & (Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE);
      MethodSymbol ctor = new MethodSymbol(MethodSymbol.CONSTRUCTOR, c, access);
      ctor.setParams(List.of());
      ctor.setReturnType(PrimType.VOID);
      c.addMethod(ctor);
    }
    if (kind == Tree.Kind.ENUM) {
      enumMembers(c);
    }
  }

  private boolean nullMarked(ClassSymbol c) {
    for (ClassSymbol k = c; k != null; k = k.outer()) {
      Info i = infos.get(k);
      if (i != null
          && i.tree().getModifiers().getAnnotations().stream()
              .anyMatch(a -> simpleName(a.getAnnotationType()).equals("NullMarked"))) {
        return true;
      }
    }
    return false;
  }

  private void field(
      ClassSymbol c,
      VariableTree v,
      Scope scope,
      boolean iface,
      boolean inEnum,
      Nullness unannotated) {
    long flags = modifierFlags(v.getModifiers());
    Type t;
    if (inEnum && isEnumConstant(v, c.name())) {
      flags = Flags.PUBLIC | Flags.STATIC | Flags.FINAL | Flags.ENUM | Flags.ENUM_CONSTANT;
      t = c.thisType().withNullness(Nullness.NON_NULL);
    } else {
      if (iface) {
        flags |= Flags.PUBLIC | Flags.STATIC | Flags.FINAL;
      }
      t = resolve(v.getType(), scope, nullnessOf(v.getModifiers(), unannotated));
    }
    FieldSymbol f = new FieldSymbol(v.getName().toString(), c, flags, t);
    if (Flags.is(flags, Flags.STATIC)
        && Flags.is(flags, Flags.FINAL)
        && !Flags.is(flags, Flags.ENUM_CONSTANT)
        && v.getInitializer() != null) {
      Object k = constant(v.getInitializer(), t);
      if (k != null) {
        f.setConstantValue(k);
      }
    }
    if (deprecated(v.getModifiers())) {
      f.addFlags(Flags.DEPRECATED);
    }
    c.addField(f);
  }

  /** Enters a method or constructor; returns true for a constructor. */
  private boolean method(
      ClassSymbol c,
      MethodTree mt,
      Scope classScope,
      boolean iface,
      FileCtx file,
      Nullness unannotated) {
    boolean ctor = mt.getReturnType() == null;
    String name = ctor ? MethodSymbol.CONSTRUCTOR : mt.getName().toString();
    long flags = modifierFlags(mt.getModifiers());
    if (ctor && Flags.is(c.rawFlags(), Flags.ENUM)) {
      flags |= Flags.PRIVATE; // enum constructors are implicitly private
    }
    if (iface) {
      if (!Flags.is(flags, Flags.PRIVATE)) {
        flags |= Flags.PUBLIC;
      }
      if (mt.getBody() == null && !Flags.is(flags, Flags.STATIC)) {
        flags |= Flags.ABSTRACT;
      }
    }
    MethodSymbol m = new MethodSymbol(name, c, flags);
    Scope scope = classScope.child();
    List<TypeVarSymbol> tvs = declareTypeParams(mt.getTypeParameters(), m, scope);
    m.setTypeParams(tvs);
    resolveBounds(mt.getTypeParameters(), tvs, scope);
    if (ctor) {
      m.setReturnType(PrimType.VOID);
    } else {
      m.setReturnType(
          resolve(mt.getReturnType(), scope, nullnessOf(mt.getModifiers(), unannotated)));
    }
    List<MethodSymbol.Param> params = new ArrayList<>();
    for (VariableTree p : mt.getParameters()) {
      Type t = resolve(p.getType(), scope, nullnessOf(p.getModifiers(), unannotated));
      params.add(MethodSymbol.Param.of(p.getName().toString(), t));
    }
    if (!mt.getParameters().isEmpty() && isVarargs(mt.getParameters().getLast())) {
      m.addFlags(Flags.VARARGS);
    }
    if (ctor && Flags.is(c.rawFlags(), Flags.RECORD) && isCompact(mt, file)) {
      // A compact canonical constructor takes the record components.
      for (FieldSymbol rc : c.recordComponents()) {
        params.add(MethodSymbol.Param.of(rc.name(), rc.type()));
      }
    }
    m.setParams(params);
    if (deprecated(mt.getModifiers())) {
      m.addFlags(Flags.DEPRECATED);
    }
    methodSpans.put(m, nameSpan(file, mt, ctor ? c.name() : name));
    c.addMethod(m);
    return ctor;
  }

  /**
   * A compact constructor has no parameter list: no {@code )} right before its body. (It cannot
   * have a throws clause, so the last token before the body is its name.)
   */
  private boolean isCompact(MethodTree mt, FileCtx file) {
    if (!mt.getParameters().isEmpty() || mt.getBody() == null) {
      return false;
    }
    long body = positions.getStartPosition(file.unit(), mt.getBody());
    String text = file.file().content();
    int i = (int) body - 1;
    while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
      i--;
    }
    return i >= 0 && text.charAt(i) != ')';
  }

  private static boolean isVarargs(VariableTree p) {
    // javac's tree printer writes `T... name` for variable-arity parameters.
    return p.getType() instanceof ArrayTypeTree && p.toString().contains("...");
  }

  /**
   * Accessors and, unless declared, the canonical constructor and {@code equals}/{@code
   * hashCode}/{@code toString} of a record (javac generates them as {@code public final}).
   */
  private void recordMembers(ClassSymbol c) {
    List<FieldSymbol> comps = c.recordComponents();
    Type object = syms.objectType().withNullness(Nullness.PLATFORM);
    if (c.methods("equals").stream().noneMatch(m -> m.params().size() == 1)) {
      MethodSymbol eq = new MethodSymbol("equals", c, Flags.PUBLIC | Flags.FINAL);
      eq.setParams(List.of(MethodSymbol.Param.of("o", object)));
      eq.setReturnType(PrimType.BOOLEAN);
      c.addMethod(eq);
    }
    if (c.methods("hashCode").stream().noneMatch(m -> m.params().isEmpty())) {
      MethodSymbol hash = new MethodSymbol("hashCode", c, Flags.PUBLIC | Flags.FINAL);
      hash.setParams(List.of());
      hash.setReturnType(PrimType.INT);
      c.addMethod(hash);
    }
    if (c.methods("toString").stream().noneMatch(m -> m.params().isEmpty())) {
      MethodSymbol str = new MethodSymbol("toString", c, Flags.PUBLIC | Flags.FINAL);
      str.setParams(List.of());
      str.setReturnType(syms.stringType().withNullness(Nullness.PLATFORM));
      c.addMethod(str);
    }
    for (FieldSymbol rc : comps) {
      if (c.methods(rc.name()).stream().noneMatch(m -> m.params().isEmpty())) {
        MethodSymbol acc = new MethodSymbol(rc.name(), c, Flags.PUBLIC);
        acc.setParams(List.of());
        acc.setReturnType(rc.type());
        c.addMethod(acc);
      }
    }
    boolean hasCanonical =
        c.methods(MethodSymbol.CONSTRUCTOR).stream().anyMatch(m -> sameTypes(m, comps));
    if (!hasCanonical) {
      MethodSymbol ctor =
          new MethodSymbol(
              MethodSymbol.CONSTRUCTOR,
              c,
              c.rawFlags() & (Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE));
      List<MethodSymbol.Param> ps = new ArrayList<>();
      for (FieldSymbol rc : comps) {
        ps.add(MethodSymbol.Param.of(rc.name(), rc.type()));
      }
      ctor.setParams(ps);
      ctor.setReturnType(PrimType.VOID);
      c.addMethod(ctor);
    }
  }

  private static boolean sameTypes(MethodSymbol m, List<FieldSymbol> comps) {
    if (m.params().size() != comps.size()) {
      return false;
    }
    for (int i = 0; i < comps.size(); i++) {
      if (!m.params().get(i).type().erasure().equals(comps.get(i).type().erasure())) {
        return false;
      }
    }
    return true;
  }

  /** The implicit {@code values()} and {@code valueOf(String)} of an enum. */
  private void enumMembers(ClassSymbol c) {
    // Platform types, as javac's class files declare them.
    Type self = c.thisType().withNullness(Nullness.PLATFORM);
    MethodSymbol values = new MethodSymbol("values", c, Flags.PUBLIC | Flags.STATIC);
    values.setParams(List.of());
    values.setReturnType(new ArrayType(self, Nullness.PLATFORM));
    c.addMethod(values);
    MethodSymbol valueOf = new MethodSymbol("valueOf", c, Flags.PUBLIC | Flags.STATIC);
    valueOf.setParams(
        List.of(MethodSymbol.Param.of("name", syms.stringType().withNullness(Nullness.PLATFORM))));
    valueOf.setReturnType(self);
    c.addMethod(valueOf);
  }

  /**
   * The value of a constant variable initialized with a literal (or a negated numeric literal);
   * null for anything else. (javac may still fold such a field; J# then reads it at run time.)
   */
  private static Object constant(ExpressionTree init, Type t) {
    Object v = init instanceof LiteralTree l ? l.getValue() : null;
    if (init instanceof UnaryTree u
        && u.getKind() == Tree.Kind.UNARY_MINUS
        && u.getExpression() instanceof LiteralTree l
        && l.getValue() instanceof Number n) {
      v =
          switch (n) {
            case Integer i -> -i;
            case Long x -> -x;
            case Double d -> -d;
            case Float f -> -f;
            default -> null;
          };
    }
    if (v == null) {
      return null;
    }
    if (t instanceof PrimType p) {
      if (v instanceof Number n) {
        return switch (p) {
          case INT -> n.intValue();
          case LONG -> n.longValue();
          case DOUBLE -> n.doubleValue();
          case FLOAT -> n.floatValue();
          case SHORT -> n.shortValue();
          case BYTE -> n.byteValue();
          case CHAR -> (char) n.intValue();
          default -> null;
        };
      }
      if (v instanceof Character ch) {
        return switch (p) {
          case CHAR -> ch;
          case INT -> (int) ch;
          case LONG -> (long) ch;
          default -> null;
        };
      }
      return p == PrimType.BOOLEAN && v instanceof Boolean ? v : null;
    }
    boolean isString =
        t instanceof ClassType ct && ct.sym().binaryName().equals("java/lang/String");
    return isString && v instanceof String ? v : null;
  }

  // ------------------------------------------------------------------ nullness & annotations

  private static String simpleName(Tree t) {
    String s = t.toString();
    return s.substring(s.lastIndexOf('.') + 1);
  }

  private static Nullness nullnessOf(List<? extends AnnotationTree> anns, Nullness fallback) {
    for (AnnotationTree a : anns) {
      switch (simpleName(a.getAnnotationType())) {
        case "Nullable", "CheckForNull", "NullableDecl" -> {
          return Nullness.NULLABLE;
        }
        case "NonNull", "NotNull", "Nonnull", "NonNullDecl" -> {
          return Nullness.NON_NULL;
        }
        default -> {}
      }
    }
    return fallback;
  }

  private static Nullness nullnessOf(ModifiersTree mods, Nullness fallback) {
    return nullnessOf(mods.getAnnotations(), fallback);
  }

  private static boolean deprecated(ModifiersTree mods) {
    return mods.getAnnotations().stream()
        .anyMatch(a -> simpleName(a.getAnnotationType()).equals("Deprecated"));
  }

  // ------------------------------------------------------------------ types

  /** The class, file and type variables a declaration's type names resolve in. */
  private record Scope(ClassSymbol cls, FileCtx file, Map<String, TypeVarSymbol> vars) {
    Scope child() {
      return new Scope(cls, file, new HashMap<>(vars));
    }
  }

  private static Map<String, TypeVarSymbol> outerTypeVars(ClassSymbol c) {
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

  private static List<TypeVarSymbol> declareTypeParams(
      List<? extends TypeParameterTree> params, Symbol owner, Scope scope) {
    List<TypeVarSymbol> tvs = new ArrayList<>();
    for (int i = 0; i < params.size(); i++) {
      TypeVarSymbol tv =
          new TypeVarSymbol(
              params.get(i).getName().toString(), owner, i, TypeParam.Variance.INVARIANT);
      tvs.add(tv);
      scope.vars().put(tv.name(), tv);
    }
    return tvs;
  }

  private void resolveBounds(
      List<? extends TypeParameterTree> params, List<TypeVarSymbol> tvs, Scope scope) {
    for (int i = 0; i < params.size(); i++) {
      List<Type> bounds = new ArrayList<>();
      for (Tree b : params.get(i).getBounds()) {
        Type t = resolve(b, scope, Nullness.NON_NULL);
        if (!t.isError()) {
          bounds.add(t);
        }
      }
      if (bounds.isEmpty()) {
        bounds.add(syms.objectType());
      }
      tvs.get(i).setBounds(bounds, bounds.getFirst().erasure());
    }
  }

  private ClassType generic(String binary, List<Type> args) {
    ClassSymbol s = syms.lookup(binary);
    return s == null ? null : new ClassType(s, args, Nullness.NON_NULL);
  }

  /** Resolves a Java type tree; {@code top} is the nullness of the outermost type. */
  private Type resolve(Tree t, Scope scope, Nullness top) {
    return switch (t) {
      case PrimitiveTypeTree p ->
          switch (p.getPrimitiveTypeKind()) {
            case BOOLEAN -> PrimType.BOOLEAN;
            case BYTE -> PrimType.BYTE;
            case SHORT -> PrimType.SHORT;
            case INT -> PrimType.INT;
            case LONG -> PrimType.LONG;
            case CHAR -> PrimType.CHAR;
            case FLOAT -> PrimType.FLOAT;
            case DOUBLE -> PrimType.DOUBLE;
            default -> PrimType.VOID;
          };
      case ArrayTypeTree a -> new ArrayType(resolve(a.getType(), scope, Nullness.PLATFORM), top);
      case AnnotatedTypeTree at ->
          resolve(at.getUnderlyingType(), scope, nullnessOf(at.getAnnotations(), top));
      case ParameterizedTypeTree pt -> {
        Type base = resolve(pt.getType(), scope, top);
        if (!(base instanceof ClassType bc)) {
          yield base;
        }
        List<Type> args = new ArrayList<>();
        for (Tree arg : pt.getTypeArguments()) {
          args.add(typeArg(arg, scope));
        }
        yield new ClassType(bc.sym(), args, top);
      }
      case IdentifierTree id -> {
        String name = id.getName().toString();
        TypeVarSymbol tv = scope.vars().get(name);
        if (tv != null) {
          yield new Type.TypeVar(tv, top);
        }
        ClassSymbol c = lookupSimple(name, scope);
        yield c == null ? Type.ErrorType.INSTANCE : new ClassType(c, List.of(), top);
      }
      case MemberSelectTree ms -> {
        ClassSymbol c = lookupQualified(ms, scope);
        yield c == null ? Type.ErrorType.INSTANCE : new ClassType(c, List.of(), top);
      }
      default -> Type.ErrorType.INSTANCE;
    };
  }

  private Type typeArg(Tree arg, Scope scope) {
    if (arg instanceof WildcardTree w) {
      return switch (w.getKind()) {
        case UNBOUNDED_WILDCARD -> new Type.WildcardType(Type.WildcardType.Kind.UNBOUNDED, null);
        case EXTENDS_WILDCARD ->
            new Type.WildcardType(
                Type.WildcardType.Kind.EXTENDS, resolve(w.getBound(), scope, Nullness.PLATFORM));
        default ->
            new Type.WildcardType(
                Type.WildcardType.Kind.SUPER, resolve(w.getBound(), scope, Nullness.PLATFORM));
      };
    }
    return resolve(arg, scope, Nullness.PLATFORM);
  }

  /**
   * Java's rules for a simple type name: member types of the class and its enclosing classes
   * (inherited ones included), types of the same file, single-type imports, the package, on-demand
   * imports, then {@code java.lang}.
   */
  private ClassSymbol lookupSimple(String name, Scope scope) {
    for (ClassSymbol c = scope.cls(); c != null; c = c.outer()) {
      ClassSymbol m = memberType(c, name, new HashSet<>());
      if (m != null) {
        return m;
      }
    }
    for (ClassSymbol top : scope.file().topLevel()) {
      if (top.name().equals(name)) {
        return top;
      }
    }
    for (ImportTree imp : scope.file().imports()) {
      if (!imp.isStatic()
          && imp.getQualifiedIdentifier() instanceof MemberSelectTree ms
          && ms.getIdentifier().contentEquals(name)) {
        ClassSymbol c = lookupQualified(ms, scope);
        if (c != null) {
          return c;
        }
      }
    }
    String pkg = scope.file().pkg();
    ClassSymbol samePkg = syms.lookup((pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + name);
    if (samePkg != null) {
      return samePkg;
    }
    for (ImportTree imp : scope.file().imports()) {
      if (!imp.isStatic()
          && imp.getQualifiedIdentifier() instanceof MemberSelectTree ms
          && ms.getIdentifier().contentEquals("*")) {
        String q = ms.getExpression().toString().replace('.', '/');
        ClassSymbol c = syms.lookup(q + "/" + name);
        if (c != null) {
          return c;
        }
        ClassSymbol container = syms.lookup(q);
        if (container != null) {
          ClassSymbol m = memberType(container, name, new HashSet<>());
          if (m != null) {
            return m;
          }
        }
      }
    }
    return syms.lookup("java/lang/" + name);
  }

  private static ClassSymbol memberType(ClassSymbol c, String name, Set<ClassSymbol> seen) {
    if (c == null || !seen.add(c)) {
      return null;
    }
    for (ClassSymbol m : c.memberTypes()) {
      if (m.name().equals(name)) {
        return m;
      }
    }
    if (c.headerInProgress()) {
      return null; // supertypes are not known yet (a cycle through a supertype clause)
    }
    if (c.superclass() != null) {
      ClassSymbol m = memberType(c.superclass().sym(), name, seen);
      if (m != null) {
        return m;
      }
    }
    for (ClassType i : c.interfaces()) {
      ClassSymbol m = memberType(i.sym(), name, seen);
      if (m != null) {
        return m;
      }
    }
    return null;
  }

  /** {@code Outer.Inner} (a class qualifier) or {@code a.b.C} (a package). */
  private ClassSymbol lookupQualified(MemberSelectTree ms, Scope scope) {
    String id = ms.getIdentifier().toString();
    Tree q = ms.getExpression();
    ClassSymbol container =
        switch (q) {
          case IdentifierTree qi -> lookupSimple(qi.getName().toString(), scope);
          case MemberSelectTree qm -> lookupQualified(qm, scope);
          default -> null;
        };
    if (container != null) {
      ClassSymbol m = memberType(container, id, new HashSet<>());
      if (m != null) {
        return m;
      }
    }
    return syms.lookup(q.toString().replace('.', '/') + "/" + id);
  }

  /** A Java source held in memory. */
  private static final class InMemorySource extends SimpleJavaFileObject {
    final SourceFile file;

    InMemorySource(SourceFile f) {
      super(uriOf(f.path()), Kind.SOURCE);
      this.file = f;
    }

    private static URI uriOf(String path) {
      String p = path.replace('\\', '/');
      try {
        return new URI("string", null, p.startsWith("/") ? p : "/" + p, null);
      } catch (java.net.URISyntaxException e) {
        return URI.create("string:///Source.java");
      }
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return file.content();
    }
  }
}

package io.github.matrixidot.jsharp.compiler.symbols;

import io.github.matrixidot.jsharp.compiler.ast.CompilationUnit;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A class, interface, enum, record or annotation type, from J# source or from a class file.
 *
 * <p>Symbols are completed lazily in two stages via a {@link Completer}: the <em>header</em>
 * (flags, type parameters, supertypes, member types, permitted subclasses) and the <em>members</em>
 * (fields, methods, properties). Accessors trigger completion, so phases can run in any order and
 * Java classes are only parsed as far as they are used.
 */
public final class ClassSymbol implements Symbol {

  /** Fills in a symbol's header and members on demand. */
  public interface Completer {
    void completeHeader(ClassSymbol c);

    void completeMembers(ClassSymbol c);
  }

  private enum State {
    NEW,
    HEADER,
    HEADER_DONE,
    MEMBERS,
    DONE
  }

  private final String binaryName;
  private final String simpleName;
  private final String packageName;
  private ClassSymbol outer;
  private boolean outerKnown;
  private long flags;
  private List<TypeVarSymbol> typeParams = List.of();
  private ClassType superclass;
  private List<ClassType> interfaces = List.of();
  private List<ClassSymbol> permitted = List.of();
  private final Map<String, List<MethodSymbol>> methods = new LinkedHashMap<>();
  private final Map<String, FieldSymbol> fields = new LinkedHashMap<>();
  private final Map<String, ClassSymbol> memberTypes = new LinkedHashMap<>();
  private final Map<String, PropertySymbol> properties = new LinkedHashMap<>();
  private final List<FieldSymbol> recordComponents = new ArrayList<>();
  private final Completer completer;
  private State state = State.NEW;
  private Decl.TypeDecl decl;
  private CompilationUnit unit;
  private Type thisType;
  private String sourceFileName;
  private SourceFile javaFile;
  private Span javaSpan;

  /**
   * @param binaryName JVM internal name, e.g. {@code java/util/Map$Entry}
   * @param simpleName source name, e.g. {@code Entry}
   * @param packageName dotted package, e.g. {@code java.util}
   */
  public ClassSymbol(
      String binaryName, String simpleName, String packageName, Completer completer) {
    this.binaryName = binaryName;
    this.simpleName = simpleName;
    this.packageName = packageName;
    this.completer = completer;
  }

  // ------------------------------------------------------------------ completion

  private void ensureHeader() {
    if (state == State.NEW) {
      state = State.HEADER;
      if (completer != null) {
        completer.completeHeader(this);
      }
      state = State.HEADER_DONE;
    }
  }

  private void ensureMembers() {
    ensureHeader();
    if (state == State.HEADER_DONE) {
      state = State.MEMBERS;
      if (completer != null) {
        completer.completeMembers(this);
      }
      state = State.DONE;
    }
  }

  /** True while the header is being completed (used for cycle detection). */
  public boolean headerInProgress() {
    return state == State.HEADER;
  }

  /** Completes the header and the members now (they are otherwise completed on first use). */
  public void completeAll() {
    ensureMembers();
  }

  // ------------------------------------------------------------------ identity

  @Override
  public String name() {
    return simpleName;
  }

  /** The JVM internal name, e.g. {@code java/util/Map$Entry}. */
  public String binaryName() {
    return binaryName;
  }

  /** The dotted package name; empty for the unnamed package. */
  public String packageName() {
    return packageName;
  }

  /** Dotted source name, e.g. {@code java.util.Map.Entry}. */
  public String qualifiedName() {
    ClassSymbol o = outer();
    String prefix = o != null ? o.qualifiedName() : packageName;
    return prefix.isEmpty() ? simpleName : prefix + "." + simpleName;
  }

  /** Name shown in diagnostics: simple names joined by dots ({@code Map.Entry}). */
  public String displayName() {
    ClassSymbol o = outer();
    return o != null ? o.displayName() + "." + simpleName : simpleName;
  }

  /** The directly enclosing class, or null for a top-level class. */
  public ClassSymbol outer() {
    if (!outerKnown) {
      ensureHeader();
    }
    return outer;
  }

  /** Sets the enclosing class (null: top level). */
  public void setOuter(ClassSymbol o) {
    this.outer = o;
    this.outerKnown = true;
  }

  /** Source symbols know their flags, outer class and member types from the start. */
  private void ensureStructure() {
    if ((flags & Flags.SOURCE) == 0) {
      ensureHeader();
    }
  }

  /** The top-level class this class is nested in, or itself. */
  public ClassSymbol outermost() {
    ClassSymbol c = this;
    while (c.outer() != null) {
      c = c.outer();
    }
    return c;
  }

  @Override
  public long flags() {
    ensureStructure();
    return flags;
  }

  /** Flags without triggering completion (for completers). */
  public long rawFlags() {
    return flags;
  }

  /** Replaces the flags ({@link Flags}). */
  public void setFlags(long f) {
    this.flags = f;
  }

  /** Adds flags ({@link Flags}). */
  public void addFlags(long f) {
    this.flags |= f;
  }

  /** True for interfaces and annotation types. */
  public boolean isInterface() {
    return has(Flags.INTERFACE);
  }

  /** True for enums. */
  public boolean isEnum() {
    return has(Flags.ENUM);
  }

  /** True for records. */
  public boolean isRecord() {
    return has(Flags.RECORD);
  }

  /** True for abstract classes and interfaces. */
  public boolean isAbstract() {
    return has(Flags.ABSTRACT);
  }

  /** True if the class cannot be extended. */
  public boolean isFinal() {
    return has(Flags.FINAL);
  }

  /** Records where a class from a Java source file of the compilation is declared (D082). */
  public void setJavaOrigin(SourceFile file, Span nameSpan) {
    this.javaFile = file;
    this.javaSpan = nameSpan;
  }

  /** The Java source file declaring this class, or null. */
  public SourceFile javaFile() {
    return javaFile;
  }

  /** The span of the class name in {@link #javaFile()}, or null. */
  public Span javaSpan() {
    return javaSpan;
  }

  /** True for a class declared in J# source of this compilation. */
  public boolean isSource() {
    return (flags & Flags.SOURCE) != 0;
  }

  /** True for annotation types. */
  public boolean isAnnotation() {
    return has(Flags.ANNOTATION);
  }

  // ------------------------------------------------------------------ annotations (D109)

  private List<AnnotationUse> annotations = List.of();

  /** The checked annotations of the declaration, for code generation. */
  public List<AnnotationUse> annotations() {
    return annotations;
  }

  /** Sets the checked annotations (D109). */
  public void setAnnotations(List<AnnotationUse> anns) {
    annotations = List.copyOf(anns);
  }

  private String retention = "CLASS";
  private Set<String> annotationTargets;

  /**
   * For an annotation type: its {@code @Retention} ({@code SOURCE}, {@code CLASS}, {@code
   * RUNTIME}).
   */
  public String retention() {
    return retention;
  }

  /**
   * For an annotation type: the {@code ElementType} names of its {@code @Target}, or null without
   * one (every declaration).
   */
  public Set<String> annotationTargets() {
    return annotationTargets;
  }

  /** Sets an annotation type's retention and targets (null: every declaration). */
  public void setAnnotationInfo(String retention, Set<String> targets) {
    this.retention = retention;
    this.annotationTargets = targets == null ? null : Set.copyOf(targets);
  }

  // ------------------------------------------------------------------ header

  /** The declared type parameters, in order. */
  public List<TypeVarSymbol> typeParams() {
    ensureHeader();
    return typeParams;
  }

  /** Sets the declared type parameters. */
  public void setTypeParams(List<TypeVarSymbol> tps) {
    this.typeParams = List.copyOf(tps);
    this.thisType = null;
  }

  /** The superclass type, or null for {@code java.lang.Object} and (in J#'s model) interfaces. */
  public ClassType superclass() {
    ensureHeader();
    return superclass;
  }

  /** Sets the superclass type (null for {@code Object}, and for interfaces from class files). */
  public void setSuperclass(ClassType t) {
    this.superclass = t;
  }

  /** The directly implemented or extended interfaces. */
  public List<ClassType> interfaces() {
    ensureHeader();
    return interfaces;
  }

  /** Sets the directly implemented or extended interfaces. */
  public void setInterfaces(List<ClassType> is) {
    this.interfaces = List.copyOf(is);
  }

  /** The permitted subclasses of a sealed class; empty otherwise. */
  public List<ClassSymbol> permitted() {
    ensureHeader();
    return permitted;
  }

  /** Sets the permitted subclasses. */
  public void setPermitted(List<ClassSymbol> p) {
    this.permitted = List.copyOf(p);
  }

  /** The member class of that simple name, or null (inherited ones are not included). */
  public ClassSymbol memberType(String name) {
    ensureStructure();
    return memberTypes.get(name);
  }

  /** The member classes declared here. */
  public Collection<ClassSymbol> memberTypes() {
    ensureStructure();
    return memberTypes.values();
  }

  /** Adds a member class. */
  public void addMemberType(ClassSymbol c) {
    memberTypes.put(c.name(), c);
  }

  /** {@code C<T1..Tn>} with the class's own type parameters as arguments. */
  public ClassType thisType() {
    if (thisType == null) {
      List<Type> args = new ArrayList<>();
      for (TypeVarSymbol tv : typeParams()) {
        args.add(tv.asType());
      }
      thisType = new ClassType(this, args, Nullness.NON_NULL);
    }
    return (ClassType) thisType;
  }

  // ------------------------------------------------------------------ members

  /** The methods and constructors ({@code <init>}) of that name declared here. */
  public List<MethodSymbol> methods(String name) {
    ensureMembers();
    return methods.getOrDefault(name, List.of());
  }

  /** All methods and constructors declared here (not inherited ones). */
  public List<MethodSymbol> allMethods() {
    ensureMembers();
    List<MethodSymbol> out = new ArrayList<>();
    methods.values().forEach(out::addAll);
    return out;
  }

  /** Adds a method or constructor. */
  public void addMethod(MethodSymbol m) {
    methods.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m);
  }

  /** The field of that name declared here, or null. */
  public FieldSymbol field(String name) {
    ensureMembers();
    return fields.get(name);
  }

  /** The fields declared here, enum constants and synthetic fields included. */
  public Collection<FieldSymbol> fields() {
    ensureMembers();
    return fields.values();
  }

  /** Adds a field. */
  public void addField(FieldSymbol f) {
    fields.put(f.name(), f);
  }

  /** The property of that name declared here, or null. */
  public PropertySymbol property(String name) {
    ensureMembers();
    return properties.get(name);
  }

  /** The properties declared here. */
  public Collection<PropertySymbol> properties() {
    ensureMembers();
    return properties.values();
  }

  /** Adds a property. */
  public void addProperty(PropertySymbol p) {
    properties.put(p.name(), p);
  }

  /** A record's component fields, in declaration order; empty otherwise. */
  public List<FieldSymbol> recordComponents() {
    ensureMembers();
    return recordComponents;
  }

  /** Adds a record component (in order). */
  public void addRecordComponent(FieldSymbol f) {
    recordComponents.add(f);
  }

  /** An enum's constants, in declaration order; empty otherwise. */
  public List<FieldSymbol> enumConstants() {
    List<FieldSymbol> out = new ArrayList<>();
    for (FieldSymbol f : fields()) {
      if (f.has(Flags.ENUM_CONSTANT)) {
        out.add(f);
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ source link

  /** The declaration of a source class; null for class files and module classes. */
  public Decl.TypeDecl decl() {
    return decl;
  }

  /** The file of a source class; null for class files. */
  public CompilationUnit unit() {
    return unit;
  }

  /** Records the declaration and file of a source class. */
  public void setSource(Decl.TypeDecl decl, CompilationUnit unit) {
    this.decl = decl;
    this.unit = unit;
  }

  /** The {@code SourceFile} attribute value. */
  public String sourceFileName() {
    return sourceFileName;
  }

  /** Sets the file name written to the SourceFile attribute. */
  public void setSourceFileName(String n) {
    this.sourceFileName = n;
  }

  @Override
  public String kindName() {
    if (has(Flags.ANNOTATION)) {
      return "annotation";
    }
    if (isInterface()) {
      return "interface";
    }
    if (isEnum()) {
      return "enum";
    }
    if (isRecord()) {
      return "record";
    }
    return "class";
  }

  @Override
  public String toString() {
    return qualifiedName();
  }
}

package io.github.matrixidot.jsharp.compiler.symbols;

import io.github.matrixidot.jsharp.compiler.ast.CompilationUnit;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
  private Completer completer;
  private State state = State.NEW;
  private Decl.TypeDecl decl;
  private CompilationUnit unit;
  private Type thisType;
  private String sourceFileName;

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

  public void setCompleter(Completer c) {
    this.completer = c;
  }

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

  public void completeAll() {
    ensureMembers();
  }

  // ------------------------------------------------------------------ identity

  @Override
  public String name() {
    return simpleName;
  }

  public String binaryName() {
    return binaryName;
  }

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

  public ClassSymbol outer() {
    if (!outerKnown) {
      ensureHeader();
    }
    return outer;
  }

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

  public void setFlags(long f) {
    this.flags = f;
  }

  public void addFlags(long f) {
    this.flags |= f;
  }

  public boolean isInterface() {
    return has(Flags.INTERFACE);
  }

  public boolean isEnum() {
    return has(Flags.ENUM);
  }

  public boolean isRecord() {
    return has(Flags.RECORD);
  }

  public boolean isAbstract() {
    return has(Flags.ABSTRACT);
  }

  public boolean isFinal() {
    return has(Flags.FINAL);
  }

  public boolean isSource() {
    return (flags & Flags.SOURCE) != 0;
  }

  public boolean isAnnotation() {
    return has(Flags.ANNOTATION);
  }

  // ------------------------------------------------------------------ header

  public List<TypeVarSymbol> typeParams() {
    ensureHeader();
    return typeParams;
  }

  public void setTypeParams(List<TypeVarSymbol> tps) {
    this.typeParams = List.copyOf(tps);
    this.thisType = null;
  }

  /** The superclass type, or null for {@code java.lang.Object} and (in J#'s model) interfaces. */
  public ClassType superclass() {
    ensureHeader();
    return superclass;
  }

  public void setSuperclass(ClassType t) {
    this.superclass = t;
  }

  public List<ClassType> interfaces() {
    ensureHeader();
    return interfaces;
  }

  public void setInterfaces(List<ClassType> is) {
    this.interfaces = List.copyOf(is);
  }

  public List<ClassSymbol> permitted() {
    ensureHeader();
    return permitted;
  }

  public void setPermitted(List<ClassSymbol> p) {
    this.permitted = List.copyOf(p);
  }

  public ClassSymbol memberType(String name) {
    ensureStructure();
    return memberTypes.get(name);
  }

  public Collection<ClassSymbol> memberTypes() {
    ensureStructure();
    return memberTypes.values();
  }

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

  public List<MethodSymbol> methods(String name) {
    ensureMembers();
    return methods.getOrDefault(name, List.of());
  }

  public List<MethodSymbol> allMethods() {
    ensureMembers();
    List<MethodSymbol> out = new ArrayList<>();
    methods.values().forEach(out::addAll);
    return out;
  }

  public void addMethod(MethodSymbol m) {
    methods.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m);
  }

  public FieldSymbol field(String name) {
    ensureMembers();
    return fields.get(name);
  }

  public Collection<FieldSymbol> fields() {
    ensureMembers();
    return fields.values();
  }

  public void addField(FieldSymbol f) {
    fields.put(f.name(), f);
  }

  public PropertySymbol property(String name) {
    ensureMembers();
    return properties.get(name);
  }

  public Collection<PropertySymbol> properties() {
    ensureMembers();
    return properties.values();
  }

  public void addProperty(PropertySymbol p) {
    properties.put(p.name(), p);
  }

  public List<FieldSymbol> recordComponents() {
    ensureMembers();
    return recordComponents;
  }

  public void addRecordComponent(FieldSymbol f) {
    recordComponents.add(f);
  }

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

  public Decl.TypeDecl decl() {
    return decl;
  }

  public CompilationUnit unit() {
    return unit;
  }

  public void setSource(Decl.TypeDecl decl, CompilationUnit unit) {
    this.decl = decl;
    this.unit = unit;
  }

  /** The {@code SourceFile} attribute value. */
  public String sourceFileName() {
    return sourceFileName;
  }

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

package io.github.matrixidot.jsharp.compiler.symbols;

import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.ast.Operators;
import io.github.matrixidot.jsharp.compiler.types.Type;
import java.util.List;

/** A method or constructor ({@code <init>}). Static initializers are not symbols. */
public final class MethodSymbol implements Symbol {
  public static final String CONSTRUCTOR = "<init>";

  /**
   * A parameter.
   *
   * @param isVarargs a trailing {@code params T[]} parameter
   * @param defaultExpr source default expression (J# source only), or null
   * @param hasDefault whether the parameter has a default value
   * @param defaultValue the constant default value (boxed; null for a {@code null} default)
   */
  public record Param(
      String name,
      Type type,
      boolean isVarargs,
      Expr defaultExpr,
      boolean hasDefault,
      Object defaultValue) {
    /** A plain parameter: no varargs, no default. */
    public static Param of(String name, Type type) {
      return new Param(name, type, false, null, false, null);
    }

    /**
     * This parameter with a default argument (a constant, or null for one computed at the call).
     */
    public Param withDefault(Object value) {
      return new Param(name, type, isVarargs, defaultExpr, true, value);
    }
  }

  private final String name;
  private final ClassSymbol owner;
  private long flags;
  private List<TypeVarSymbol> typeParams = List.of();
  private List<Param> params = List.of();
  private Type returnType;
  private Decl decl;
  private PropertySymbol property;
  private Type jvmReturnType;

  /** A method (or constructor, named {@link #CONSTRUCTOR}) of {@code owner}. */
  public MethodSymbol(String name, ClassSymbol owner, long flags) {
    this.name = name;
    this.owner = owner;
    this.flags = flags;
  }

  @Override
  public String name() {
    return name;
  }

  /** The declaring class. */
  public ClassSymbol owner() {
    return owner;
  }

  @Override
  public long flags() {
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

  /** True for constructors ({@code <init>}). */
  public boolean isConstructor() {
    return name.equals(CONSTRUCTOR);
  }

  /** True for abstract methods. */
  public boolean isAbstract() {
    return has(Flags.ABSTRACT);
  }

  /** True if the last parameter takes a variable number of arguments. */
  public boolean isVarargs() {
    return has(Flags.VARARGS);
  }

  /** True for extension methods (their first parameter is the receiver). */
  public boolean isExtension() {
    return has(Flags.EXTENSION);
  }

  /** The declared type parameters, in order. */
  public List<TypeVarSymbol> typeParams() {
    return typeParams;
  }

  /** Sets the declared type parameters. */
  public void setTypeParams(List<TypeVarSymbol> tps) {
    this.typeParams = List.copyOf(tps);
  }

  /** The parameters, in order. */
  public List<Param> params() {
    return params;
  }

  /** Sets the parameters. */
  public void setParams(List<Param> ps) {
    this.params = List.copyOf(ps);
  }

  /** The return type ({@code void} for constructors). */
  public Type returnType() {
    return returnType;
  }

  /** Sets the return type. */
  public void setReturnType(Type t) {
    this.returnType = t;
  }

  /** The JVM return type when it differs from {@link #returnType()} (never-returning methods). */
  public Type jvmReturnType() {
    return jvmReturnType;
  }

  /** Sets a JVM return type that differs from the J# one (see {@link #jvmReturnType()}). */
  public void setJvmReturnType(Type t) {
    this.jvmReturnType = t;
  }

  private MethodSymbol sourceView;

  /**
   * For the hoisted method of a local function (D083): the function as declared (its name and
   * parameters without captures), which editors show and navigate to. Null otherwise.
   */
  public MethodSymbol sourceView() {
    return sourceView;
  }

  /** Sets the declared view of a hoisted local function (see {@link #sourceView()}). */
  public void setSourceView(MethodSymbol m) {
    this.sourceView = m;
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

  private List<List<AnnotationUse>> parameterAnnotations = List.of();

  /** The checked annotations of each parameter (empty, or one list per parameter). */
  public List<List<AnnotationUse>> parameterAnnotations() {
    return parameterAnnotations;
  }

  /** Sets the checked annotations of each parameter (D109). */
  public void setParameterAnnotations(List<List<AnnotationUse>> anns) {
    parameterAnnotations = anns.stream().map(List::copyOf).toList();
  }

  /** The declaration in source (a method, constructor or property), or null. */
  public Decl decl() {
    return decl;
  }

  /** Records the declaration in source. */
  public void setDecl(Decl d) {
    this.decl = d;
  }

  /** The property this method is an accessor of, or null. */
  public PropertySymbol property() {
    return property;
  }

  /** Marks the method as an accessor of a property. */
  public void setProperty(PropertySymbol p) {
    this.property = p;
  }

  @Override
  public String kindName() {
    return isConstructor() ? "constructor" : "method";
  }

  /** Human-readable signature, e.g. {@code List.add(E)}. */
  public String signature() {
    StringBuilder sb = new StringBuilder();
    String operator = has(Flags.OPERATOR) ? Operators.symbolOf(name, params.size()) : null;
    if (operator != null) {
      sb.append(owner.displayName()).append(".operator ").append(operator); // not plus/times
    } else if (owner instanceof ClassSymbol c && c.has(Flags.MODULE) && !isConstructor()) {
      sb.append(name); // top-level function: its synthetic module class is not user-visible
    } else {
      sb.append(owner.displayName());
      if (!isConstructor()) {
        sb.append('.').append(name);
      }
    }
    sb.append('(');
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      Param p = params.get(i);
      sb.append(p.type() == null ? "?" : p.type().display());
    }
    return sb.append(')').toString();
  }

  @Override
  public String toString() {
    return signature();
  }
}

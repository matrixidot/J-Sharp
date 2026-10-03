package io.github.matrixidot.jsharp.compiler.symbols;

import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
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
    public static Param of(String name, Type type) {
      return new Param(name, type, false, null, false, null);
    }

    public Param withType(Type t) {
      return new Param(name, t, isVarargs, defaultExpr, hasDefault, defaultValue);
    }

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
  private String jvmName;
  private Type jvmReturnType;

  public MethodSymbol(String name, ClassSymbol owner, long flags) {
    this.name = name;
    this.owner = owner;
    this.flags = flags;
  }

  @Override
  public String name() {
    return name;
  }

  /** Name in the class file (differs from {@link #name()} for property accessors). */
  public String jvmName() {
    return jvmName != null ? jvmName : name;
  }

  public void setJvmName(String n) {
    this.jvmName = n;
  }

  public ClassSymbol owner() {
    return owner;
  }

  @Override
  public long flags() {
    return flags;
  }

  public void setFlags(long f) {
    this.flags = f;
  }

  public void addFlags(long f) {
    this.flags |= f;
  }

  public boolean isConstructor() {
    return name.equals(CONSTRUCTOR);
  }

  public boolean isAbstract() {
    return has(Flags.ABSTRACT);
  }

  public boolean isVarargs() {
    return has(Flags.VARARGS);
  }

  public boolean isExtension() {
    return has(Flags.EXTENSION);
  }

  public List<TypeVarSymbol> typeParams() {
    return typeParams;
  }

  public void setTypeParams(List<TypeVarSymbol> tps) {
    this.typeParams = List.copyOf(tps);
  }

  public List<Param> params() {
    return params;
  }

  public void setParams(List<Param> ps) {
    this.params = List.copyOf(ps);
  }

  public Type returnType() {
    return returnType;
  }

  public void setReturnType(Type t) {
    this.returnType = t;
  }

  /** The JVM return type when it differs from {@link #returnType()} (never-returning methods). */
  public Type jvmReturnType() {
    return jvmReturnType;
  }

  public void setJvmReturnType(Type t) {
    this.jvmReturnType = t;
  }

  public Decl decl() {
    return decl;
  }

  public void setDecl(Decl d) {
    this.decl = d;
  }

  public PropertySymbol property() {
    return property;
  }

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
    String operator =
        has(Flags.OPERATOR)
            ? io.github.matrixidot.jsharp.compiler.ast.Operators.symbolOf(name, params.size())
            : null;
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

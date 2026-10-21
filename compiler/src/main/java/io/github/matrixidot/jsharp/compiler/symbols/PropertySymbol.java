package io.github.matrixidot.jsharp.compiler.symbols;

import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.types.Type;

/**
 * A J# property. At the JVM level it is a getter ({@code getX}/{@code isX}), an optional setter
 * ({@code setX}) and an optional backing field. Record components are also exposed as properties
 * (getter = the record accessor).
 */
public final class PropertySymbol implements Symbol {
  private final String name;
  private final ClassSymbol owner;
  private long flags;
  private Type type;
  private MethodSymbol getter;
  private MethodSymbol setter;
  private FieldSymbol backingField;
  private Decl.Property decl;

  /** A property of {@code owner}; its accessors are set with {@link #setAccessors}. */
  public PropertySymbol(String name, ClassSymbol owner, long flags, Type type) {
    this.name = name;
    this.owner = owner;
    this.flags = flags;
    this.type = type;
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

  /** Adds flags ({@link Flags}). */
  public void addFlags(long f) {
    flags |= f;
  }

  /** The property type. */
  public Type type() {
    return type;
  }

  /** Sets the type (once inferred). */
  public void setType(Type t) {
    this.type = t;
  }

  /** The getter ({@code getX}/{@code isX}), or null for a write-only property. */
  public MethodSymbol getter() {
    return getter;
  }

  /** The setter ({@code setX}), or null for a read-only property. */
  public MethodSymbol setter() {
    return setter;
  }

  /** The field holding the value, or null for a computed property. */
  public FieldSymbol backingField() {
    return backingField;
  }

  /** Sets the getter, setter and backing field (each may be null). */
  public void setAccessors(MethodSymbol getter, MethodSymbol setter, FieldSymbol backing) {
    this.getter = getter;
    this.setter = setter;
    this.backingField = backing;
  }

  /** The declaration in source, or null. */
  public Decl.Property decl() {
    return decl;
  }

  /** Records the declaration in source. */
  public void setDecl(Decl.Property d) {
    this.decl = d;
  }

  /** True for {@code init} setters: settable only while the object is created. */
  public boolean isInitOnly() {
    return has(Flags.INIT_ONLY);
  }

  /** True for {@code required} properties: object creation must set them. */
  public boolean isRequired() {
    return has(Flags.REQUIRED);
  }

  @Override
  public String kindName() {
    return "property";
  }

  @Override
  public String toString() {
    return owner.displayName() + "." + name;
  }
}

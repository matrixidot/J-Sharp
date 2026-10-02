package dev.jsharp.compiler.symbols;

import dev.jsharp.compiler.ast.Decl;
import dev.jsharp.compiler.types.Type;

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

  public ClassSymbol owner() {
    return owner;
  }

  @Override
  public long flags() {
    return flags;
  }

  public void addFlags(long f) {
    flags |= f;
  }

  public Type type() {
    return type;
  }

  public void setType(Type t) {
    this.type = t;
  }

  public MethodSymbol getter() {
    return getter;
  }

  public MethodSymbol setter() {
    return setter;
  }

  public FieldSymbol backingField() {
    return backingField;
  }

  public void setAccessors(MethodSymbol getter, MethodSymbol setter, FieldSymbol backing) {
    this.getter = getter;
    this.setter = setter;
    this.backingField = backing;
  }

  public Decl.Property decl() {
    return decl;
  }

  public void setDecl(Decl.Property d) {
    this.decl = d;
  }

  public boolean isInitOnly() {
    return has(Flags.INIT_ONLY);
  }

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

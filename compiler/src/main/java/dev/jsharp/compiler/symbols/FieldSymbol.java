package dev.jsharp.compiler.symbols;

import dev.jsharp.compiler.ast.Decl;
import dev.jsharp.compiler.ast.VarDeclarator;
import dev.jsharp.compiler.types.Type;

/** A field (including enum constants, record component fields and property backing fields). */
public final class FieldSymbol implements Symbol {
  private final String name;
  private final ClassSymbol owner;
  private long flags;
  private Type type;
  private Object constantValue;
  private Decl decl;
  private VarDeclarator declarator;
  private PropertySymbol property;

  public FieldSymbol(String name, ClassSymbol owner, long flags, Type type) {
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

  /** Compile-time constant value (static final primitive/String fields), or null. */
  public Object constantValue() {
    return constantValue;
  }

  public void setConstantValue(Object v) {
    this.constantValue = v;
  }

  public Decl decl() {
    return decl;
  }

  public VarDeclarator declarator() {
    return declarator;
  }

  public void setSource(Decl decl, VarDeclarator declarator) {
    this.decl = decl;
    this.declarator = declarator;
  }

  public PropertySymbol property() {
    return property;
  }

  public void setProperty(PropertySymbol p) {
    this.property = p;
  }

  @Override
  public String kindName() {
    return has(Flags.ENUM_CONSTANT) ? "enum constant" : "field";
  }

  @Override
  public String toString() {
    return owner.displayName() + "." + name;
  }
}

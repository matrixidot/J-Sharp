package io.github.matrixidot.jsharp.compiler.symbols;

import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.VarDeclarator;
import io.github.matrixidot.jsharp.compiler.types.Type;
import java.util.List;

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

  /** A field of {@code owner}; {@code type} may be set later ({@link #setType}). */
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

  /** The declared (or inferred) type. */
  public Type type() {
    return type;
  }

  /** Sets the type (once inferred from the initializer). */
  public void setType(Type t) {
    this.type = t;
  }

  /** Compile-time constant value (static final primitive/String fields), or null. */
  public Object constantValue() {
    return constantValue;
  }

  /** Sets the compile-time constant value (a boxed primitive or String). */
  public void setConstantValue(Object v) {
    this.constantValue = v;
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

  /** The declaration in source, or null. */
  public Decl decl() {
    return decl;
  }

  /** The declarator ({@code name = init}) in source, or null. */
  public VarDeclarator declarator() {
    return declarator;
  }

  /** Records the declaration in source. */
  public void setSource(Decl decl, VarDeclarator declarator) {
    this.decl = decl;
    this.declarator = declarator;
  }

  /** The property this field backs, or null. */
  public PropertySymbol property() {
    return property;
  }

  /** Marks the field as the backing field of a property. */
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

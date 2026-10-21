package io.github.matrixidot.jsharp.compiler.symbols;

import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.types.Type;

/** A local variable, parameter, catch parameter or pattern binding. */
public final class VarSymbol implements Symbol {
  /** Variable kinds. */
  public enum Kind {
    LOCAL,
    PARAM,
    CATCH,
    PATTERN,
    RESOURCE,
    FOREACH
  }

  private final String name;
  private Type type;
  private final long flags;
  private final Kind kind;
  private final Span span;
  private final int id;
  private boolean reassigned;
  private VarSymbol cell;

  /** A local variable of a body; {@code id} is unique within the body. */
  public VarSymbol(String name, Type type, long flags, Kind kind, Span span, int id) {
    this.name = name;
    this.type = type;
    this.flags = flags;
    this.kind = kind;
    this.span = span;
    this.id = id;
  }

  @Override
  public String name() {
    return name;
  }

  /** The declared (or inferred) type. */
  public Type type() {
    return type;
  }

  /** Sets the type (once inferred). */
  public void setType(Type t) {
    this.type = t;
  }

  @Override
  public long flags() {
    return flags;
  }

  /** What declared it: a local, parameter, catch parameter, pattern binding, ... */
  public Kind kind() {
    return kind;
  }

  /** Where it is declared. */
  public Span span() {
    return span;
  }

  /** Unique id within a method body (stable across lowering for slot allocation). */
  public int id() {
    return id;
  }

  /** True for {@code val} and final variables. */
  public boolean isFinal() {
    return has(Flags.FINAL);
  }

  /**
   * For an {@code atomic} local (D084): the variable holding its {@code AtomicInteger}/{@code
   * AtomicLong}/{@code AtomicBoolean}/{@code AtomicReference} cell, which lowered code and captures
   * use instead of the value; null otherwise.
   */
  public VarSymbol cell() {
    return cell;
  }

  /** Sets the atomic cell that holds this variable (see {@link #cell()}). */
  public void setCell(VarSymbol c) {
    this.cell = c;
  }

  /** True for {@code atomic} locals (D084). */
  public boolean isAtomic() {
    return has(Flags.ATOMIC);
  }

  /** True if the variable is assigned after its declaration. */
  public boolean reassigned() {
    return reassigned;
  }

  /** Records an assignment after the declaration. */
  public void markReassigned() {
    reassigned = true;
  }

  @Override
  public String kindName() {
    return kind == Kind.PARAM ? "parameter" : "variable";
  }

  @Override
  public String toString() {
    return name + "#" + id;
  }
}

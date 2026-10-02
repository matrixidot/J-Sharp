package dev.jsharp.compiler.symbols;

import dev.jsharp.compiler.source.Span;
import dev.jsharp.compiler.types.Type;

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
  private boolean captured;
  private boolean reassigned;

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

  public Type type() {
    return type;
  }

  public void setType(Type t) {
    this.type = t;
  }

  @Override
  public long flags() {
    return flags;
  }

  public Kind kind() {
    return kind;
  }

  public Span span() {
    return span;
  }

  /** Unique id within a method body (stable across lowering for slot allocation). */
  public int id() {
    return id;
  }

  public boolean isFinal() {
    return has(Flags.FINAL);
  }

  public boolean captured() {
    return captured;
  }

  public void markCaptured() {
    captured = true;
  }

  public boolean reassigned() {
    return reassigned;
  }

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

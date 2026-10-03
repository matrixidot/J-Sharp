package io.github.matrixidot.jsharp.compiler.symbols;

/** A named program entity: class, method, field, property, type variable or local variable. */
public sealed interface Symbol
    permits ClassSymbol, MethodSymbol, FieldSymbol, PropertySymbol, TypeVarSymbol, VarSymbol {
  String name();

  long flags();

  default boolean has(long flag) {
    return (flags() & flag) != 0;
  }

  default boolean isStatic() {
    return has(Flags.STATIC);
  }

  /** Kind word for diagnostics ("class", "method", ...). */
  String kindName();
}

package dev.jsharp.compiler.bound;

import dev.jsharp.compiler.symbols.FieldSymbol;
import dev.jsharp.compiler.symbols.MethodSymbol;
import dev.jsharp.compiler.symbols.PropertySymbol;
import dev.jsharp.compiler.symbols.VarSymbol;
import dev.jsharp.compiler.types.Type;

/** Assignable locations. */
public sealed interface BLValue {
  Type type();

  record LocalLV(VarSymbol var) implements BLValue {
    @Override
    public Type type() {
      return var.type();
    }
  }

  /** Field location; {@code receiver} null for static fields. */
  record FieldLV(BExpr receiver, FieldSymbol field, Type type) implements BLValue {}

  record ArrayLV(BExpr array, BExpr index, Type type) implements BLValue {}

  /**
   * A property (or JavaBeans getter/setter pair, or an indexer such as {@code list[i]}).
   *
   * @param receiver the object (null for static)
   * @param getter getter method (for compound assignment), may be null for pure writes
   * @param setter setter method
   * @param extraArgs leading arguments of getter/setter (e.g. the index of {@code list[i]})
   */
  record PropertyLV(
      BExpr receiver,
      PropertySymbol property,
      MethodSymbol getter,
      MethodSymbol setter,
      java.util.List<BExpr> extraArgs,
      Type type,
      BExpr.CallKind kind)
      implements BLValue {}
}

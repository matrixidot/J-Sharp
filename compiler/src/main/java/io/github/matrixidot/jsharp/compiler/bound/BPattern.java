package io.github.matrixidot.jsharp.compiler.bound;

import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Type;
import java.util.List;

/** Typed patterns (spec section 6), matched against a value of {@code inputType}. */
public sealed interface BPattern {
  Span span();

  /** Matches anything (discard / {@code var x} with binding). */
  record Any(VarSymbol binding, Span span) implements BPattern {}

  /** {@code T x}: instanceof test plus optional binding. */
  record TypeTest(Type type, VarSymbol binding, Span span) implements BPattern {}

  /** Equality with a constant (or {@code null}). */
  record Constant(BExpr value, Span span) implements BPattern {}

  /** {@code < v}, {@code >= v}, ... on a numeric/char/comparable value. */
  record Relational(BExpr.BinOp op, BExpr value, Type operandType, Span span) implements BPattern {}

  /**
   * Type test followed by subpatterns on accessors (positional = record components or deconstructor
   * results; properties = getters).
   *
   * @param type tested type (null when no test is needed)
   * @param accessors accessor per subpattern
   * @param subpatterns patterns for each accessor result
   */
  record Recursive(
      Type type,
      List<MethodSymbol> accessors,
      List<Type> accessorTypes,
      List<BPattern> subpatterns,
      VarSymbol binding,
      Span span)
      implements BPattern {}

  record And(BPattern left, BPattern right, Span span) implements BPattern {}

  /**
   * A list pattern {@code [p0, p1, .. slice, q0]} over a {@code java.util.List} or an array.
   *
   * @param slice the pattern for the {@code ..} part (null if none or bare {@code ..})
   * @param hasSlice whether the pattern contains {@code ..} (then the length is a minimum)
   * @param elementType element type of the input
   * @param sliceType type of the slice ({@code List<E>} or {@code E[]})
   * @param binding variable for the whole value ({@code [..] xs}), or null
   */
  record ListPat(
      java.util.List<BPattern> prefix,
      BPattern slice,
      boolean hasSlice,
      java.util.List<BPattern> suffix,
      io.github.matrixidot.jsharp.compiler.types.Type elementType,
      io.github.matrixidot.jsharp.compiler.types.Type sliceType,
      boolean array,
      VarSymbol binding,
      Span span)
      implements BPattern {}

  record Or(BPattern left, BPattern right, Span span) implements BPattern {}

  record Not(BPattern pattern, Span span) implements BPattern {}
}

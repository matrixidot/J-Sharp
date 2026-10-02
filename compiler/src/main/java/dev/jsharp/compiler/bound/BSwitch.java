package dev.jsharp.compiler.bound;

import dev.jsharp.compiler.symbols.VarSymbol;
import dev.jsharp.compiler.types.Type;
import java.util.List;

/**
 * A pattern switch: evaluate the selector into {@code selectorVar}, then test cases in order.
 *
 * @param exhaustive true if the checker proved the cases cover every value; otherwise the generated
 *     code falls through to {@code defaultBody} or throws {@code MatchException}
 */
public record BSwitch(
    BExpr selector,
    VarSymbol selectorVar,
    Type selectorType,
    List<Case> cases,
    boolean exhaustive) {

  /**
   * One case.
   *
   * @param pattern the pattern, or null for {@code default}
   * @param guard the {@code when} guard, or null
   * @param body statements (for switch statements) — the arm body for expressions is a {@code
   *     Return}-free block ending in a {@link BExpr} value
   * @param value the arm value for switch expressions, else null
   */
  public record Case(BPattern pattern, BExpr guard, List<BStmt> body, BExpr value) {}
}

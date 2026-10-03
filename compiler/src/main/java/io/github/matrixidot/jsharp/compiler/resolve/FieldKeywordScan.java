package io.github.matrixidot.jsharp.compiler.resolve;

import io.github.matrixidot.jsharp.compiler.ast.AstWalk;
import io.github.matrixidot.jsharp.compiler.ast.Body;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.Expr;

/** Detects use of the contextual {@code field} keyword in a property accessor body. */
final class FieldKeywordScan {
  private FieldKeywordScan() {}

  static boolean usesField(Body body) {
    boolean[] found = new boolean[1];
    AstWalk.walk(
        body,
        n -> {
          if (n instanceof Expr.Name name && name.name().equals("field")) {
            found[0] = true;
          }
          // Lambdas may still use `field`; nested classes have their own scope.
          return !found[0]
              && !(n instanceof Decl.TypeDecl)
              && !(n instanceof Expr.New nw && nw.anonBody() != null);
        });
    return found[0];
  }
}

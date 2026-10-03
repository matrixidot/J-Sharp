package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;

/** A member or lambda body: a block or an expression ({@code => expr}). */
public sealed interface Body extends Node {
  record Block(Stmt.Block block) implements Body {
    @Override
    public Span span() {
      return block.span();
    }
  }

  record ExprBody(Expr expr) implements Body {
    @Override
    public Span span() {
      return expr.span();
    }
  }
}

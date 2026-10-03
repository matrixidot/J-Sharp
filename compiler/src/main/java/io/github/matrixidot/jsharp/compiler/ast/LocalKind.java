package io.github.matrixidot.jsharp.compiler.ast;

/** How a variable was declared: {@code var}, {@code val}, or with an explicit type. */
public enum LocalKind {
  VAR,
  VAL,
  TYPED
}

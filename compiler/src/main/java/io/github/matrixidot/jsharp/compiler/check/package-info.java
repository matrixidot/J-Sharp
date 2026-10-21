/**
 * Semantic checking of bodies: {@link io.github.matrixidot.jsharp.compiler.check.ClassChecker}
 * checks each class, {@link io.github.matrixidot.jsharp.compiler.check.Attr} each expression
 * (bidirectional typing), with helpers for calls and inference ({@code Calls}, {@code Infer}),
 * operators ({@code Ops}), statements and flow analysis ({@code Stmts}, {@code FlowState}), and
 * patterns ({@code Patterns}). The output is the bound tree.
 */
package io.github.matrixidot.jsharp.compiler.check;

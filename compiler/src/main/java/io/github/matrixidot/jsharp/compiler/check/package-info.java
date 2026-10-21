/**
 * Semantic checking of bodies: {@link io.github.matrixidot.jsharp.compiler.check.ClassChecker}
 * checks each class, {@link io.github.matrixidot.jsharp.compiler.check.Attr} each expression
 * (bidirectional typing). Attr's helpers each hold it and handle one part: calls ({@code Calls}),
 * overload resolution and inference ({@code Overloads}, {@code Infer}, {@code CallDiagnostics} for
 * failed calls), object creation ({@code Creation}), member access ({@code Members}), lambdas and
 * method references ({@code Lambdas}), operators ({@code Ops}), statements and flow analysis
 * ({@code Stmts}, {@code FlowState}), patterns and switches ({@code Patterns}, {@code
 * Exhaustiveness}), collection literals, local functions, and annotations ({@code Annotations}).
 * The output is the bound tree.
 */
package io.github.matrixidot.jsharp.compiler.check;

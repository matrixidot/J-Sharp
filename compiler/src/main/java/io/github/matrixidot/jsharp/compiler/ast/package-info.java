/**
 * The syntax tree: immutable records under sealed interfaces ({@link
 * io.github.matrixidot.jsharp.compiler.ast.Decl}, {@link
 * io.github.matrixidot.jsharp.compiler.ast.Stmt}, {@link
 * io.github.matrixidot.jsharp.compiler.ast.Expr}, {@link
 * io.github.matrixidot.jsharp.compiler.ast.Pattern}, {@link
 * io.github.matrixidot.jsharp.compiler.ast.TypeNode}), exactly as written, with a source span on
 * every node. Phases switch over the node kinds; {@link
 * io.github.matrixidot.jsharp.compiler.ast.AstWalk} is the generic traversal.
 */
package io.github.matrixidot.jsharp.compiler.ast;

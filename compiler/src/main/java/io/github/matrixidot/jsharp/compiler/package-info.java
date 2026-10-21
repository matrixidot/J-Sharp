/**
 * The J# compiler. {@link io.github.matrixidot.jsharp.compiler.driver.Compilation} runs the
 * pipeline, each phase in its own package:
 *
 * <ol>
 *   <li>{@code syntax}: lexer and parser, source text to the syntax tree ({@code ast});
 *   <li>{@code resolve}: class symbols for every declared type, imports, then headers and member
 *       signatures ({@code symbols}, {@code types});
 *   <li>{@code check}: name resolution in bodies, type checking and inference, flow analysis; the
 *       result is the bound tree ({@code bound});
 *   <li>{@code lower}: desugars the bound tree to the subset code generation understands;
 *   <li>{@code codegen}: class files, with the JDK ClassFile API.
 * </ol>
 *
 * <p>Java classes come from class files and Java sources ({@code classpath}); every problem is a
 * {@code diag} diagnostic with a stable code. {@code ide} answers editor questions from a checked
 * program. {@link io.github.matrixidot.jsharp.compiler.Context} holds the state of one compilation;
 * there is no global mutable state.
 */
package io.github.matrixidot.jsharp.compiler;

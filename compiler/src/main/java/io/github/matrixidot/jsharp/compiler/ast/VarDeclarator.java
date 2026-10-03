package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;

/** {@code name [= init]} in a variable or field declaration. */
public record VarDeclarator(String name, Span nameSpan, Expr init, Span span) implements Node {}

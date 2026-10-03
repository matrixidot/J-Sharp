package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;

/** {@code name = value} inside an object initializer or {@code with} expression. */
public record FieldInit(String name, Span nameSpan, Expr value, Span span) implements Node {}

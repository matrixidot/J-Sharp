package dev.jsharp.compiler.ast;

import dev.jsharp.compiler.source.Span;

/** {@code name = value} inside an object initializer or {@code with} expression. */
public record FieldInit(String name, Span nameSpan, Expr value, Span span) implements Node {}

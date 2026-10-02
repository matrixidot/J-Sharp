package dev.jsharp.compiler.ast;

import dev.jsharp.compiler.source.Span;

/** {@code name [= init]} in a variable or field declaration. */
public record VarDeclarator(String name, Span nameSpan, Expr init, Span span) implements Node {}

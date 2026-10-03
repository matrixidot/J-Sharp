package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;

/** {@code package a.b.c;} */
public record PackageDecl(String name, Span span) implements Node {}

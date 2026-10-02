package dev.jsharp.compiler.ast;

import dev.jsharp.compiler.source.Span;

/** {@code package a.b.c;} */
public record PackageDecl(String name, Span span) implements Node {}

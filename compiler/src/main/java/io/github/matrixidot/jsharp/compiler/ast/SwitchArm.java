package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;

/** A switch-expression arm {@code pattern [when guard] => body}. */
public record SwitchArm(Pattern pattern, Expr guard, Expr body, Span span) implements Node {}

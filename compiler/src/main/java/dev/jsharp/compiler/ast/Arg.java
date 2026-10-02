package dev.jsharp.compiler.ast;

import dev.jsharp.compiler.source.Span;

/**
 * A call argument, tuple element or annotation argument.
 *
 * @param name argument name for named arguments ({@code tls: true}), else null
 * @param value the argument expression
 */
public record Arg(String name, Expr value, Span span) implements Node {}

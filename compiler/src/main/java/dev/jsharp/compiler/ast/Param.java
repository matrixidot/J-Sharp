package dev.jsharp.compiler.ast;

import dev.jsharp.compiler.source.Span;

/**
 * A method, constructor, lambda or record-header parameter.
 *
 * @param modifiers annotations and {@code final}
 * @param isThis true for the receiver of an extension method ({@code this int x})
 * @param isParams true for a varargs parameter ({@code params int[] xs})
 * @param type declared type, or null for an inferred lambda parameter
 * @param name parameter name ({@code _} for a discard)
 * @param defaultValue default argument, or null
 */
public record Param(
    Modifiers modifiers,
    boolean isThis,
    boolean isParams,
    TypeNode type,
    String name,
    Span nameSpan,
    Expr defaultValue,
    Span span)
    implements Node {}

package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;
import java.util.List;

/**
 * One target of a deconstruction {@code var (x, (y, _)) = ...}.
 *
 * @param type explicit type, or null
 * @param name variable name ({@code _} discards); null when {@code nested} is used
 * @param nested nested targets, or null
 */
public record DeconstructVar(
    TypeNode type, String name, Span nameSpan, List<DeconstructVar> nested, Span span)
    implements Node {}

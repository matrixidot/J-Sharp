package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;
import java.util.List;

/**
 * An enum constant {@code Earth(5.97e24, 6.37e6)}.
 *
 * @param args constructor arguments, or null when written without parentheses
 */
public record EnumConstant(
    List<Annotation> annotations, String name, Span nameSpan, List<Arg> args, Span span)
    implements Node {}

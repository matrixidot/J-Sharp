package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;
import java.util.List;

/**
 * An annotation use {@code @Name(args)}.
 *
 * @param target use-site target such as {@code file} in {@code @file:ClassName("X")}, or null
 * @param name the annotation type name
 * @param args arguments; a single unnamed argument means {@code value}
 */
public record Annotation(String target, TypeNode name, List<Arg> args, Span span) implements Node {
  public Annotation {
    args = List.copyOf(args);
  }
}

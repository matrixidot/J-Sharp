package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;
import java.util.List;

/** Pattern syntax (spec section 6). */
public sealed interface Pattern extends Node {

  /** {@code Circle c} or {@code List<String>} (binding null). */
  record Type(TypeNode type, String binding, Span span) implements Pattern {}

  /** {@code var x}. */
  record Var(String name, Span span) implements Pattern {}

  /** {@code _}. */
  record Discard(Span span) implements Pattern {}

  /**
   * A constant: literal, {@code null}, or a (qualified) name. A bare name may also denote a type;
   * the checker decides.
   */
  record Constant(Expr value, Span span) implements Pattern {}

  /** {@code > 0}, {@code <= limit}. */
  record Relational(Expr.BinaryOp op, Expr value, Span span) implements Pattern {}

  /**
   * Positional and/or property pattern: {@code Rect(var w, var h)}, {@code Rect { w: var w }},
   * {@code (var a, var b)}, {@code { length: > 3 } s}.
   *
   * @param type matched type, or null
   * @param positional positional subpatterns, or null
   * @param properties property subpatterns, or null
   * @param binding variable bound to the matched value, or null
   */
  record Recursive(
      TypeNode type,
      List<Pattern> positional,
      List<PropertySub> properties,
      String binding,
      Span span)
      implements Pattern {}

  /**
   * {@code name: pattern} (with an extended path {@code a.b: pattern}).
   *
   * @param path property names
   */
  record PropertySub(List<String> path, Span pathSpan, Pattern pattern) {
    public PropertySub {
      path = List.copyOf(path);
    }
  }

  record And(Pattern left, Pattern right, Span span) implements Pattern {}

  record Or(Pattern left, Pattern right, Span span) implements Pattern {}

  record Not(Pattern pattern, Span span) implements Pattern {}

  record Paren(Pattern pattern, Span span) implements Pattern {}

  /** {@code [a, .., b]} (v0.2). */
  record ListPattern(List<Pattern> elements, String binding, Span span) implements Pattern {
    public ListPattern {
      elements = List.copyOf(elements);
    }
  }

  /** {@code ..} or {@code .. var rest} inside a list pattern. */
  record Slice(Pattern pattern, Span span) implements Pattern {}
}

package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;

/**
 * A property accessor: {@code get;}, {@code private set { ... }}, {@code init;}.
 *
 * @param body accessor body, or null for an auto-implemented accessor
 */
public record Accessor(Modifiers modifiers, Kind kind, Body body, Span span) implements Node {
  /** Accessor kinds. */
  public enum Kind {
    GET,
    SET,
    INIT
  }
}

package dev.jsharp.compiler.ast;

import dev.jsharp.compiler.source.Span;
import java.util.List;

/**
 * A generic type parameter, with bounds merged from inline {@code T : Bound} and {@code where}
 * clauses.
 */
public record TypeParam(Variance variance, String name, List<TypeNode> bounds, Span span)
    implements Node {
  public TypeParam {
    bounds = List.copyOf(bounds);
  }

  /** Declaration-site (or use-site, for wildcards) variance. */
  public enum Variance {
    INVARIANT,
    OUT,
    IN
  }
}

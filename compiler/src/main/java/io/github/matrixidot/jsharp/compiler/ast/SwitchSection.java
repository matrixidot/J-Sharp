package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;
import java.util.List;

/** A switch-statement section: one or more labels followed by statements. */
public record SwitchSection(List<Label> labels, List<Stmt> body, Span span) implements Node {
  public SwitchSection {
    labels = List.copyOf(labels);
    body = List.copyOf(body);
  }

  /**
   * {@code case pattern [when guard]:} or {@code default:} (pattern null).
   *
   * @param pattern case pattern, or null for {@code default}
   * @param guard the {@code when} guard, or null
   */
  public record Label(Pattern pattern, Expr guard, Span span) {}
}

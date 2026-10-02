package dev.jsharp.compiler.ast;

import dev.jsharp.compiler.source.Span;
import java.util.List;

/**
 * {@code catch (A | B e) when (cond) { ... }}; {@code catch { ... }} has no types and no name.
 *
 * @param name variable name, or null
 * @param filter the {@code when} condition, or null
 */
public record CatchClause(
    List<TypeNode> types, String name, Span nameSpan, Expr filter, Stmt.Block body, Span span)
    implements Node {
  public CatchClause {
    types = List.copyOf(types);
  }
}

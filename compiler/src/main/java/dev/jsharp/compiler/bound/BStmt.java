package dev.jsharp.compiler.bound;

import dev.jsharp.compiler.source.Span;
import dev.jsharp.compiler.symbols.VarSymbol;
import dev.jsharp.compiler.types.Type;
import dev.jsharp.compiler.types.Type.ClassType;
import java.util.List;

/** Typed statements. */
public sealed interface BStmt {
  Span span();

  /** Identity object for break/continue targets. */
  final class Label {
    private final String name;

    public Label(String name) {
      this.name = name;
    }

    public String name() {
      return name;
    }

    @Override
    public String toString() {
      return name == null ? "<anon>" : name;
    }
  }

  record Block(List<BStmt> stmts, Span span) implements BStmt {}

  record LocalDecl(VarSymbol var, BExpr init, Span span) implements BStmt {}

  record ExprStmt(BExpr expr, Span span) implements BStmt {}

  record If(BExpr cond, BStmt then, BStmt otherwise, Span span) implements BStmt {}

  record While(BExpr cond, BStmt body, Label label, Span span) implements BStmt {}

  record DoWhile(BStmt body, BExpr cond, Label label, Span span) implements BStmt {}

  record For(List<BStmt> init, BExpr cond, List<BExpr> update, BStmt body, Label label, Span span)
      implements BStmt {}

  /**
   * foreach over an array or {@code Iterable}.
   *
   * @param elementType type of the iteration element (before conversion to the variable's type)
   * @param destructure statements run at the start of each iteration (deconstruction), or empty
   */
  record Foreach(
      VarSymbol var,
      BExpr iterable,
      Type elementType,
      List<BStmt> destructure,
      BStmt body,
      Label label,
      Span span)
      implements BStmt {}

  /** A labeled non-loop statement ({@code break label} leaves it). */
  record Labeled(Label label, BStmt body, Span span) implements BStmt {}

  record Break(Label target, Span span) implements BStmt {}

  record Continue(Label target, Span span) implements BStmt {}

  record Return(BExpr value, Span span) implements BStmt {}

  record Throw(BExpr exception, Span span) implements BStmt {}

  /**
   * A catch clause.
   *
   * @param types caught types (one entry for a single type; several for multi-catch)
   * @param var the exception variable (its type is the lub of the caught types)
   * @param filter {@code when} condition, or null
   */
  record Catch(List<ClassType> types, VarSymbol var, BExpr filter, BStmt body, Span span) {}

  record Try(BStmt body, List<Catch> catches, BStmt finallyBody, Span span) implements BStmt {}

  /** {@code using (var r = ...) body}: closes {@code resource} (if non-null) after the body. */
  record Using(VarSymbol resource, BExpr init, BStmt body, Span span) implements BStmt {}

  /** {@code lock (monitor) body}. */
  record Sync(BExpr monitor, BStmt body, Span span) implements BStmt {}

  /** A switch statement over a decision structure. */
  record Switch(BSwitch sw, Label label, Span span) implements BStmt {}

  record Empty(Span span) implements BStmt {}
}

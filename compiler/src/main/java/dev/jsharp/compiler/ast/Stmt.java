package dev.jsharp.compiler.ast;

import dev.jsharp.compiler.source.Span;
import java.util.List;

/** Statement syntax. */
public sealed interface Stmt extends Node {

  record Block(List<Stmt> stmts, Span span) implements Stmt {
    public Block {
      stmts = List.copyOf(stmts);
    }
  }

  /**
   * {@code var x = 1;}, {@code val y = 2;}, {@code int a = 1, b;}.
   *
   * @param type the declared type when {@code kind == TYPED}, else null
   */
  record LocalVar(
      Modifiers modifiers, LocalKind kind, TypeNode type, List<VarDeclarator> vars, Span span)
      implements Stmt {
    public LocalVar {
      vars = List.copyOf(vars);
    }
  }

  /** {@code var (x, y) = point;}. */
  record Deconstruct(LocalKind kind, List<DeconstructVar> vars, Expr init, Span span)
      implements Stmt {
    public Deconstruct {
      vars = List.copyOf(vars);
    }
  }

  /** A local class/record/interface/enum declaration. */
  record LocalType(Decl.TypeDecl decl, Span span) implements Stmt {}

  record ExprStmt(Expr expr, Span span) implements Stmt {}

  record If(Expr cond, Stmt then, Stmt otherwise, Span span) implements Stmt {}

  record While(Expr cond, Stmt body, Span span) implements Stmt {}

  record DoWhile(Stmt body, Expr cond, Span span) implements Stmt {}

  /** C-style for; {@code init} holds local declarations or expression statements. */
  record For(List<Stmt> init, Expr cond, List<Expr> update, Stmt body, Span span) implements Stmt {
    public For {
      init = List.copyOf(init);
      update = List.copyOf(update);
    }
  }

  /**
   * {@code foreach (var x in xs)} / {@code foreach (var (k, v) in map)}.
   *
   * @param name loop variable, or null when {@code deconstruct} is used
   */
  record Foreach(
      LocalKind kind,
      TypeNode type,
      String name,
      Span nameSpan,
      List<DeconstructVar> deconstruct,
      Expr iterable,
      Stmt body,
      Span span)
      implements Stmt {}

  record Break(String label, Span span) implements Stmt {}

  record Continue(String label, Span span) implements Stmt {}

  record Return(Expr value, Span span) implements Stmt {}

  /** {@code throw expr;} or {@code throw;} (value null: rethrow the caught exception). */
  record Throw(Expr value, Span span) implements Stmt {}

  record Labeled(String label, Span labelSpan, Stmt body, Span span) implements Stmt {}

  record Switch(Expr selector, List<SwitchSection> sections, Span span) implements Stmt {
    public Switch {
      sections = List.copyOf(sections);
    }
  }

  record Try(Block body, List<CatchClause> catches, Block finallyBlock, Span span) implements Stmt {
    public Try {
      catches = List.copyOf(catches);
    }
  }

  /** {@code using (resource) body}; the resource is a {@link LocalVar} or an {@link ExprStmt}. */
  record Using(Stmt resource, Stmt body, Span span) implements Stmt {}

  /** {@code using var r = open();} — disposed at the end of the enclosing block. */
  record UsingDecl(LocalVar decl, Span span) implements Stmt {}

  record Lock(Expr monitor, Block body, Span span) implements Stmt {}

  record Checked(Block body, Span span) implements Stmt {}

  record Empty(Span span) implements Stmt {}
}

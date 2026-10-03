package io.github.matrixidot.jsharp.compiler.ast;

import java.util.List;
import java.util.function.Predicate;

/**
 * Generic pre-order traversal of syntax trees (explicit per node kind; no reflection). The visitor
 * returns false to skip a node's children.
 */
public final class AstWalk {
  private AstWalk() {}

  /** Visits {@code n} and its descendants; stops descending where {@code visit} returns false. */
  public static void walk(Node n, Predicate<Node> visit) {
    if (n == null || !visit.test(n)) {
      return;
    }
    switch (n) {
      case CompilationUnit u -> all(u.members(), visit);
      case Decl.TypeDecl t -> {
        all(t.enumConstants(), visit);
        all(t.members(), visit);
      }
      case Decl.Method m -> {
        all(m.params(), visit);
        walk(m.body(), visit);
      }
      case Decl.Constructor k -> {
        if (k.params() != null) {
          all(k.params(), visit);
        }
        walk(k.body(), visit);
      }
      case Decl.Field f -> all(f.vars(), visit);
      case Decl.Property p -> {
        if (p.accessors() != null) {
          all(p.accessors(), visit);
        }
        walk(p.getter(), visit);
        walk(p.initializer(), visit);
      }
      case Decl.Initializer i -> walk(i.body(), visit);
      case Decl.TopLevelStmt t -> walk(t.stmt(), visit);
      case Accessor a -> walk(a.body(), visit);
      case EnumConstant c -> {
        if (c.args() != null) {
          all(c.args(), visit);
        }
      }
      case Param p -> walk(p.defaultValue(), visit);
      case VarDeclarator v -> walk(v.init(), visit);
      case Body.Block b -> walk(b.block(), visit);
      case Body.ExprBody b -> walk(b.expr(), visit);
      case Arg a -> walk(a.value(), visit);
      case FieldInit f -> walk(f.value(), visit);
      case SwitchArm a -> {
        walk(a.pattern(), visit);
        walk(a.guard(), visit);
        walk(a.body(), visit);
      }
      case SwitchSection s -> {
        for (SwitchSection.Label l : s.labels()) {
          walk(l.pattern(), visit);
          walk(l.guard(), visit);
        }
        all(s.body(), visit);
      }
      case CatchClause c -> {
        walk(c.filter(), visit);
        walk(c.body(), visit);
      }
      case DeconstructVar d -> {
        if (d.nested() != null) {
          all(d.nested(), visit);
        }
      }
      case Stmt s -> stmt(s, visit);
      case Expr e -> expr(e, visit);
      case Pattern p -> pattern(p, visit);
      case TypeNode t -> {}
      case Annotation a -> all(a.args(), visit);
      case TypeParam t -> {}
      case Modifiers m -> {}
      case ImportDecl i -> {}
      case PackageDecl p -> {}
    }
  }

  private static void all(List<? extends Node> nodes, Predicate<Node> visit) {
    for (Node n : nodes) {
      walk(n, visit);
    }
  }

  private static void stmt(Stmt s, Predicate<Node> v) {
    switch (s) {
      case Stmt.Block b -> all(b.stmts(), v);
      case Stmt.LocalVar l -> all(l.vars(), v);
      case Stmt.Deconstruct d -> {
        all(d.vars(), v);
        walk(d.init(), v);
      }
      case Stmt.LocalType t -> walk(t.decl(), v);
      case Stmt.ExprStmt e -> walk(e.expr(), v);
      case Stmt.If i -> {
        walk(i.cond(), v);
        walk(i.then(), v);
        walk(i.otherwise(), v);
      }
      case Stmt.While w -> {
        walk(w.cond(), v);
        walk(w.body(), v);
      }
      case Stmt.DoWhile d -> {
        walk(d.body(), v);
        walk(d.cond(), v);
      }
      case Stmt.For f -> {
        all(f.init(), v);
        walk(f.cond(), v);
        all(f.update(), v);
        walk(f.body(), v);
      }
      case Stmt.Foreach f -> {
        if (f.deconstruct() != null) {
          all(f.deconstruct(), v);
        }
        walk(f.iterable(), v);
        walk(f.body(), v);
      }
      case Stmt.Return r -> walk(r.value(), v);
      case Stmt.Throw t -> walk(t.value(), v);
      case Stmt.Labeled l -> walk(l.body(), v);
      case Stmt.Switch sw -> {
        walk(sw.selector(), v);
        all(sw.sections(), v);
      }
      case Stmt.Try t -> {
        walk(t.body(), v);
        all(t.catches(), v);
        walk(t.finallyBlock(), v);
      }
      case Stmt.Using u -> {
        walk(u.resource(), v);
        walk(u.body(), v);
      }
      case Stmt.UsingDecl u -> walk(u.decl(), v);
      case Stmt.Lock l -> {
        walk(l.monitor(), v);
        walk(l.body(), v);
      }
      case Stmt.Checked c -> walk(c.body(), v);
      case Stmt.Break b -> {}
      case Stmt.Continue c -> {}
      case Stmt.Empty e -> {}
    }
  }

  private static void expr(Expr e, Predicate<Node> v) {
    switch (e) {
      case Expr.Interpolated i -> {
        for (Expr.Part p : i.parts()) {
          if (p instanceof Expr.HolePart h) {
            walk(h.expr(), v);
          }
        }
      }
      case Expr.Member m -> walk(m.target(), v);
      case Expr.Call c -> {
        walk(c.callee(), v);
        all(c.args(), v);
      }
      case Expr.Index i -> {
        walk(i.target(), v);
        walk(i.index(), v);
      }
      case Expr.New n -> {
        if (n.args() != null) {
          all(n.args(), v);
        }
        if (n.init() != null) {
          all(n.init(), v);
        }
        if (n.anonBody() != null) {
          all(n.anonBody(), v);
        }
      }
      case Expr.NewArray n -> {
        all(n.dims(), v);
        walk(n.init(), v);
      }
      case Expr.ArrayInit a -> all(a.elements(), v);
      case Expr.Unary u -> walk(u.operand(), v);
      case Expr.Binary b -> {
        walk(b.left(), v);
        walk(b.right(), v);
      }
      case Expr.Range r -> {
        walk(r.from(), v);
        walk(r.to(), v);
      }
      case Expr.Assign a -> {
        walk(a.target(), v);
        walk(a.value(), v);
      }
      case Expr.Conditional c -> {
        walk(c.cond(), v);
        walk(c.then(), v);
        walk(c.otherwise(), v);
      }
      case Expr.Switch s -> {
        walk(s.selector(), v);
        all(s.arms(), v);
      }
      case Expr.Is i -> {
        walk(i.expr(), v);
        walk(i.pattern(), v);
      }
      case Expr.As a -> walk(a.expr(), v);
      case Expr.Cast c -> walk(c.expr(), v);
      case Expr.Lambda l -> {
        all(l.params(), v);
        walk(l.body(), v);
      }
      case Expr.MethodRef m -> walk(m.target(), v);
      case Expr.NameOf n -> walk(n.expr(), v);
      case Expr.Tuple t -> all(t.elements(), v);
      case Expr.With w -> {
        walk(w.target(), v);
        all(w.inits(), v);
      }
      case Expr.Throw t -> walk(t.expr(), v);
      case Expr.Await a -> walk(a.expr(), v);
      case Expr.Checked c -> walk(c.expr(), v);
      case Expr.Paren p -> walk(p.expr(), v);
      case Expr.Literal l -> {}
      case Expr.Name n -> {}
      case Expr.This t -> {}
      case Expr.Super s -> {}
      case Expr.TypeOf t -> {}
      case Expr.Error err -> {}
    }
  }

  private static void pattern(Pattern p, Predicate<Node> v) {
    switch (p) {
      case Pattern.Constant c -> walk(c.value(), v);
      case Pattern.Relational r -> walk(r.value(), v);
      case Pattern.Recursive r -> {
        if (r.positional() != null) {
          all(r.positional(), v);
        }
        if (r.properties() != null) {
          for (Pattern.PropertySub ps : r.properties()) {
            walk(ps.pattern(), v);
          }
        }
      }
      case Pattern.And a -> {
        walk(a.left(), v);
        walk(a.right(), v);
      }
      case Pattern.Or o -> {
        walk(o.left(), v);
        walk(o.right(), v);
      }
      case Pattern.Not n -> walk(n.pattern(), v);
      case Pattern.Paren pp -> walk(pp.pattern(), v);
      case Pattern.ListPattern l -> all(l.elements(), v);
      case Pattern.Slice s -> walk(s.pattern(), v);
      case Pattern.Type t -> {}
      case Pattern.Var vp -> {}
      case Pattern.Discard d -> {}
    }
  }
}

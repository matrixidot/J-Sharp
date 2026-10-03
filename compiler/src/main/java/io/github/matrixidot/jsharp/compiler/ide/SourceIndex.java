package io.github.matrixidot.jsharp.compiler.ide;

import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.VarDeclarator;
import io.github.matrixidot.jsharp.compiler.bound.BClass;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.bound.BLValue;
import io.github.matrixidot.jsharp.compiler.bound.BPattern;
import io.github.matrixidot.jsharp.compiler.bound.BStmt;
import io.github.matrixidot.jsharp.compiler.bound.BSwitch;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.PropertySymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symbol;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps source positions to what is there, for editor tooling (hover, go-to-definition, completion):
 * every symbol reference and declaration in the checked (pre-lowering) bound tree, with its span,
 * symbol and type. Built from a finished analysis; immutable afterwards.
 */
public final class SourceIndex {
  /** What a reference denotes. */
  public enum Kind {
    LOCAL,
    FIELD,
    PROPERTY,
    METHOD,
    CONSTRUCTOR,
    CLASS,
    EXPRESSION
  }

  /**
   * One indexed range.
   *
   * @param symbol the referenced symbol, or null for a plain expression
   * @param type the type of the expression or declaration (may be null)
   * @param declaration true at the declaring occurrence of {@code symbol}
   */
  public record Ref(Span span, Kind kind, Symbol symbol, Type type, boolean declaration) {}

  /** A source location. */
  public record Location(SourceFile file, Span span) {}

  private final Map<SourceFile, List<Ref>> byFile = new IdentityHashMap<>();
  private final Map<VarSymbol, Location> localDecls = new IdentityHashMap<>();

  private SourceIndex() {}

  /** Indexes the checked classes of an analysis. */
  public static SourceIndex build(List<BClass> classes) {
    return build(classes, Map.of());
  }

  /**
   * Indexes the checked classes plus recorded expression types (which survive errors in enclosing
   * expressions; see {@code Compilation.recordExpressionTypes}).
   */
  public static SourceIndex build(List<BClass> classes, Map<SourceFile, Map<Span, Type>> recorded) {
    SourceIndex idx = new SourceIndex();
    for (BClass c : classes) {
      idx.indexClass(c);
    }
    for (var byFile : recorded.entrySet()) {
      idx.file = byFile.getKey();
      for (var e : byFile.getValue().entrySet()) {
        idx.add(e.getKey(), Kind.EXPRESSION, null, e.getValue(), false);
      }
    }
    idx.file = null;
    for (List<Ref> refs : idx.byFile.values()) {
      refs.sort(Comparator.comparingInt((Ref r) -> r.span().start()));
    }
    return idx;
  }

  /** All references in {@code file}, ordered by start offset. */
  public List<Ref> refs(SourceFile file) {
    return byFile.getOrDefault(file, List.of());
  }

  /**
   * The most specific reference covering {@code offset} (or ending right at it): the smallest
   * range, preferring symbol references over plain expressions.
   */
  public Optional<Ref> at(SourceFile file, int offset) {
    Ref best = null;
    for (Ref r : refs(file)) {
      if (r.span().start() <= offset && offset <= r.span().end()) {
        if (best == null || better(r, best)) {
          best = r;
        }
      }
    }
    return Optional.ofNullable(best);
  }

  private static boolean better(Ref a, Ref b) {
    int la = a.span().end() - a.span().start();
    int lb = b.span().end() - b.span().start();
    if (la != lb) {
      return la < lb;
    }
    return a.symbol() != null && b.symbol() == null;
  }

  /** The expression ending exactly at {@code offset} (the receiver before a '.'), if any. */
  public Optional<Ref> endingAt(SourceFile file, int offset) {
    Ref best = null;
    for (Ref r : refs(file)) {
      if (r.span().end() == offset && r.type() != null) {
        if (best == null || r.span().start() < best.span().start()) {
          best = r; // the widest expression ending here: a.b.c rather than c
        }
      }
    }
    return Optional.ofNullable(best);
  }

  /** Where {@code sym} is declared, if it comes from source. */
  public Optional<Location> declaration(Symbol sym) {
    return switch (sym) {
      case VarSymbol v -> Optional.ofNullable(localDecls.get(v));
      case ClassSymbol c -> classLocation(c);
      case MethodSymbol m -> {
        if (m.property() != null) {
          yield declaration(m.property());
        }
        if (m.decl() instanceof Decl.Method dm) {
          yield located(m.owner(), dm.nameSpan());
        }
        if (m.decl() instanceof Decl.Constructor dc) {
          yield located(m.owner(), dc.nameSpan());
        }
        yield m.isConstructor() ? classLocation(m.owner()) : Optional.empty();
      }
      case PropertySymbol p ->
          p.decl() != null ? located(p.owner(), p.decl().nameSpan()) : Optional.empty();
      case FieldSymbol f -> {
        if (f.property() != null) {
          yield declaration(f.property());
        }
        if (f.decl() instanceof Decl.Field df) {
          for (VarDeclarator v : df.vars()) {
            if (v.name().equals(f.name())) {
              yield located(f.owner(), v.nameSpan());
            }
          }
        }
        yield Optional.empty();
      }
      default -> Optional.empty();
    };
  }

  private static Optional<Location> classLocation(ClassSymbol c) {
    return c.decl() != null ? located(c, c.decl().nameSpan()) : Optional.empty();
  }

  private static Optional<Location> located(ClassSymbol owner, Span span) {
    ClassSymbol top = owner.outermost();
    if (top.unit() == null || span == null) {
      return Optional.empty();
    }
    return Optional.of(new Location(top.unit().file(), span));
  }

  // ------------------------------------------------------------------ building

  private SourceFile file;

  private void add(Span span, Kind kind, Symbol sym, Type type, boolean decl) {
    if (span == null || span == Span.NONE || file == null || span.end() <= span.start()) {
      return;
    }
    byFile.computeIfAbsent(file, k -> new ArrayList<>()).add(new Ref(span, kind, sym, type, decl));
  }

  private void indexClass(BClass c) {
    ClassSymbol sym = c.sym();
    ClassSymbol top = sym.outermost();
    if (top.unit() == null) {
      return;
    }
    SourceFile saved = file;
    file = top.unit().file();
    if (sym.decl() != null) {
      add(sym.decl().nameSpan(), Kind.CLASS, sym, sym.thisType(), true);
    }
    for (FieldSymbol f : sym.fields()) {
      if (f.property() == null && !f.name().startsWith("$")) {
        declaration(f).ifPresent(loc -> add(loc.span(), Kind.FIELD, f, f.type(), true));
      }
    }
    for (PropertySymbol p : sym.properties()) {
      if (p.decl() != null) {
        add(p.decl().nameSpan(), Kind.PROPERTY, p, p.type(), true);
      }
    }
    for (BClass.Method m : c.methods()) {
      MethodSymbol ms = m.sym();
      if (ms.property() == null && ms.decl() instanceof Decl.Method dm) {
        add(dm.nameSpan(), Kind.METHOD, ms, ms.returnType(), true);
      }
      for (VarSymbol p : m.params()) {
        declareVar(p);
      }
      if (m.body() != null) {
        stmt(m.body());
      }
    }
    c.instanceInit().forEach(this::stmt);
    c.staticInit().forEach(this::stmt);
    c.nested().forEach(this::indexClass);
    file = saved;
  }

  private void declareVar(VarSymbol v) {
    if (v == null || v.name().startsWith("$") || v.name().equals("_") || v.span() == null) {
      return;
    }
    localDecls.put(v, new Location(file, v.span()));
    add(v.span(), Kind.LOCAL, v, v.type(), true);
  }

  private void stmts(List<BStmt> ss) {
    for (BStmt s : ss) {
      stmt(s);
    }
  }

  private void stmt(BStmt s) {
    if (s == null) {
      return;
    }
    switch (s) {
      case BStmt.Block b -> stmts(b.stmts());
      case BStmt.LocalDecl d -> {
        declareVar(d.var());
        expr(d.init());
      }
      case BStmt.ExprStmt e -> expr(e.expr());
      case BStmt.If i -> {
        expr(i.cond());
        stmt(i.then());
        stmt(i.otherwise());
      }
      case BStmt.While w -> {
        expr(w.cond());
        stmt(w.body());
      }
      case BStmt.DoWhile d -> {
        stmt(d.body());
        expr(d.cond());
      }
      case BStmt.For f -> {
        stmts(f.init());
        expr(f.cond());
        f.update().forEach(this::expr);
        stmt(f.body());
      }
      case BStmt.Foreach f -> {
        declareVar(f.var());
        expr(f.iterable());
        stmts(f.destructure());
        stmt(f.body());
      }
      case BStmt.Labeled l -> stmt(l.body());
      case BStmt.Return r -> expr(r.value());
      case BStmt.Throw t -> expr(t.exception());
      case BStmt.Try t -> {
        stmt(t.body());
        for (BStmt.Catch c : t.catches()) {
          declareVar(c.var());
          expr(c.filter());
          stmt(c.body());
        }
        stmt(t.finallyBody());
      }
      case BStmt.Using u -> {
        declareVar(u.resource());
        expr(u.init());
        stmt(u.body());
      }
      case BStmt.Sync sy -> {
        expr(sy.monitor());
        stmt(sy.body());
      }
      case BStmt.Switch sw -> switchCases(sw.sw());
      case BStmt.IntSwitch is -> {
        expr(is.selector());
        stmts(is.bodies());
        stmt(is.defaultBody());
      }
      case BStmt.Break b -> {}
      case BStmt.Continue c -> {}
      case BStmt.Empty e -> {}
    }
  }

  private void switchCases(BSwitch sw) {
    expr(sw.selector());
    for (BSwitch.Case c : sw.cases()) {
      pattern(c.pattern());
      expr(c.guard());
      if (c.body() != null) {
        stmts(c.body());
      }
      expr(c.value());
    }
  }

  private void pattern(BPattern p) {
    if (p == null) {
      return;
    }
    switch (p) {
      case BPattern.Any a -> declareVar(a.binding());
      case BPattern.TypeTest t -> declareVar(t.binding());
      case BPattern.Constant c -> expr(c.value());
      case BPattern.Relational r -> expr(r.value());
      case BPattern.Recursive r -> {
        r.subpatterns().forEach(this::pattern);
        declareVar(r.binding());
      }
      case BPattern.And a -> {
        pattern(a.left());
        pattern(a.right());
      }
      case BPattern.ListPat lp -> {
        lp.prefix().forEach(this::pattern);
        pattern(lp.slice());
        lp.suffix().forEach(this::pattern);
        declareVar(lp.binding());
      }
      case BPattern.Or o -> {
        pattern(o.left());
        pattern(o.right());
      }
      case BPattern.Not n -> pattern(n.pattern());
    }
  }

  private void lvalue(BLValue lv) {
    switch (lv) {
      case BLValue.LocalLV l -> {}
      case BLValue.FieldLV f -> expr(f.receiver());
      case BLValue.ArrayLV a -> {
        expr(a.array());
        expr(a.index());
      }
      case BLValue.PropertyLV p -> {
        expr(p.receiver());
        p.extraArgs().forEach(this::expr);
      }
    }
  }

  private void expr(BExpr e) {
    if (e == null) {
      return;
    }
    switch (e) {
      case BExpr.Local l -> add(l.span(), Kind.LOCAL, l.var(), l.var().type(), false);
      case BExpr.Field f -> {
        expr(f.receiver());
        add(f.span(), Kind.FIELD, f.field(), f.type(), false);
      }
      case BExpr.Call c -> {
        expr(c.receiver());
        c.args().forEach(this::expr);
        MethodSymbol m = c.method();
        if (m.property() != null) {
          add(c.span(), Kind.PROPERTY, m.property(), c.type(), false);
        } else {
          add(c.span(), m.isConstructor() ? Kind.CONSTRUCTOR : Kind.METHOD, m, c.type(), false);
        }
      }
      case BExpr.New n -> {
        n.args().forEach(this::expr);
        add(n.span(), Kind.CONSTRUCTOR, n.ctor(), n.type(), false);
      }
      case BExpr.ObjectInit oi -> {
        expr(oi.creation());
        oi.inits().forEach(i -> expr(i.value()));
      }
      case BExpr.NewArray na -> {
        na.dims().forEach(this::expr);
        if (na.elems() != null) {
          na.elems().forEach(this::expr);
        }
        add(na.span(), Kind.EXPRESSION, null, na.type(), false);
      }
      case BExpr.ArrayElem a -> {
        expr(a.array());
        expr(a.index());
        add(a.span(), Kind.EXPRESSION, null, a.type(), false);
      }
      case BExpr.ArrayLength a -> {
        expr(a.array());
        add(a.span(), Kind.EXPRESSION, null, a.type(), false);
      }
      case BExpr.Unary u -> {
        expr(u.operand());
        add(u.span(), Kind.EXPRESSION, null, u.type(), false);
      }
      case BExpr.Binary b -> {
        expr(b.left());
        expr(b.right());
        add(b.span(), Kind.EXPRESSION, null, b.type(), false);
      }
      case BExpr.ValueEquals v -> {
        expr(v.left());
        expr(v.right());
      }
      case BExpr.Assign a -> {
        lvalue(a.target());
        expr(a.value());
      }
      case BExpr.CompoundAssign c -> {
        lvalue(c.target());
        expr(c.value());
      }
      case BExpr.IncDec i -> lvalue(i.target());
      case BExpr.Conv c -> expr(c.expr());
      case BExpr.InstanceOf i -> expr(i.expr());
      case BExpr.Conditional c -> {
        expr(c.cond());
        expr(c.then());
        expr(c.otherwise());
        add(c.span(), Kind.EXPRESSION, null, c.type(), false);
      }
      case BExpr.Concat c -> {
        c.parts().forEach(this::expr);
        add(c.span(), Kind.EXPRESSION, null, c.type(), false);
      }
      case BExpr.Lambda l -> {
        l.params().forEach(this::declareVar);
        stmt(l.body());
      }
      case BExpr.MethodRef m -> {
        expr(m.receiver());
        add(m.span(), Kind.METHOD, m.target(), m.type(), false);
      }
      case BExpr.Let l -> {
        expr(l.init());
        expr(l.body());
      }
      case BExpr.Throw t -> expr(t.exception());
      case BExpr.SafeAccess s -> {
        expr(s.receiver());
        expr(s.whenPresent());
        add(s.span(), Kind.EXPRESSION, null, s.type(), false);
      }
      case BExpr.Coalesce c -> {
        expr(c.left());
        expr(c.right());
        add(c.span(), Kind.EXPRESSION, null, c.type(), false);
      }
      case BExpr.Block b -> {
        stmts(b.stmts());
        expr(b.value());
      }
      case BExpr.Await a -> {
        expr(a.task());
        add(a.span(), Kind.EXPRESSION, null, a.type(), false);
      }
      case BExpr.Switch s -> {
        switchCases(s.sw());
        add(s.span(), Kind.EXPRESSION, null, s.type(), false);
      }
      case BExpr.IsPattern ip -> {
        expr(ip.expr());
        pattern(ip.pattern());
      }
      case BExpr.Const c -> add(c.span(), Kind.EXPRESSION, null, c.type(), false);
      case BExpr.This t -> add(t.span(), Kind.EXPRESSION, null, t.type(), false);
      case BExpr.OuterThis o -> add(o.span(), Kind.EXPRESSION, null, o.type(), false);
      case BExpr.ClassLit c -> add(c.span(), Kind.EXPRESSION, null, c.type(), false);
      case BExpr.IntSwitch s -> {
        expr(s.selector());
        s.values().forEach(this::expr);
        expr(s.defaultValue());
      }
      case BExpr.Indy i -> i.captured().forEach(this::expr);
      case BExpr.Nop n -> {}
      case BExpr.Error err -> {}
    }
  }
}

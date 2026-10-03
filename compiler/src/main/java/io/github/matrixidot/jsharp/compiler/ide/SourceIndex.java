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
   * @param nameSpan the identifier naming {@code symbol} inside {@code span} (for references and
   *     rename), or null when there is none (plain expressions)
   */
  public record Ref(
      Span span, Kind kind, Symbol symbol, Type type, boolean declaration, Span nameSpan) {}

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

  /**
   * The symbol editors treat {@code sym} as: a property for its accessors and backing field, the
   * declared local function for its hoisted method.
   */
  public static Symbol canonical(Symbol sym) {
    return switch (sym) {
      case MethodSymbol m when m.sourceView() != null -> m.sourceView();
      case MethodSymbol m when m.property() != null -> m.property();
      case FieldSymbol f when f.property() != null -> f.property();
      case null, default -> sym;
    };
  }

  /**
   * Every occurrence of {@code sym} in the indexed files (declaration included), as identifier
   * locations. For a class, its constructor calls count too.
   */
  public List<Location> references(Symbol sym) {
    Symbol target = canonical(sym);
    List<Location> out = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (var e : byFile.entrySet()) {
      for (Ref r : e.getValue()) {
        if (r.symbol() == null || r.nameSpan() == null) {
          continue;
        }
        Symbol s = canonical(r.symbol());
        boolean match =
            s == target
                || target instanceof ClassSymbol c
                    && s instanceof MethodSymbol m
                    && m.isConstructor()
                    && m.owner() == c;
        if (match && seen.add(System.identityHashCode(e.getKey()) + ":" + r.nameSpan().start())) {
          out.add(new Location(e.getKey(), r.nameSpan()));
        }
      }
    }
    out.sort(
        Comparator.comparing((Location l) -> l.file().path())
            .thenComparingInt(l -> l.span().start()));
    return out;
  }

  /** Where {@code sym} is declared, if it comes from source. */
  public Optional<Location> declaration(Symbol sym) {
    return switch (sym) {
      case VarSymbol v -> Optional.ofNullable(localDecls.get(v));
      case ClassSymbol c -> classLocation(c);
      case MethodSymbol m when m.sourceView() != null -> declaration(m.sourceView());
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
        yield m.isConstructor() || m.owner().outermost().javaFile() != null
            ? classLocation(m.owner())
            : Optional.empty();
      }
      case PropertySymbol p ->
          p.decl() != null
              ? located(p.owner(), p.decl().nameSpan())
              : located(p.owner(), headerSpan(p.owner(), p.name()));
      case FieldSymbol f -> {
        if (f.property() != null) {
          yield declaration(f.property());
        }
        if (f.has(io.github.matrixidot.jsharp.compiler.symbols.Flags.ENUM_CONSTANT)
            && f.owner().decl() != null) {
          for (var ec : f.owner().decl().enumConstants()) {
            if (ec.name().equals(f.name())) {
              yield located(f.owner(), ec.nameSpan());
            }
          }
        }
        if (f.decl() instanceof Decl.Field df) {
          for (VarDeclarator v : df.vars()) {
            if (v.name().equals(f.name())) {
              yield located(f.owner(), v.nameSpan());
            }
          }
        }
        // Members of Java source classes lead to their class.
        yield f.owner().outermost().javaFile() != null
            ? classLocation(f.owner())
            : Optional.empty();
      }
      default -> Optional.empty();
    };
  }

  /** The span of record component {@code name} in its record's header, or null. */
  private static Span headerSpan(ClassSymbol c, String name) {
    if (c.decl() == null || c.decl().header() == null) {
      return null;
    }
    for (var p : c.decl().header()) {
      if (p.name().equals(name)) {
        return p.nameSpan();
      }
    }
    return null;
  }

  private static Optional<Location> classLocation(ClassSymbol c) {
    if (c.javaFile() != null && c.javaSpan() != null) {
      return Optional.of(new Location(c.javaFile(), c.javaSpan())); // a Java source (D082)
    }
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
    // Declarations and locals are exactly their names.
    add(span, kind, sym, type, decl, sym != null && (decl || kind == Kind.LOCAL) ? span : null);
  }

  private void add(Span span, Kind kind, Symbol sym, Type type, boolean decl, Span nameSpan) {
    if (span == null || span == Span.NONE || file == null || span.end() <= span.start()) {
      return;
    }
    byFile
        .computeIfAbsent(file, k -> new ArrayList<>())
        .add(new Ref(span, kind, sym, type, decl, nameSpan));
  }

  /**
   * The identifier {@code name} inside {@code span}: the first whole-word occurrence after the
   * explicit receiver (or from the start when there is none), else the last one.
   */
  private Span nameIn(Span span, BExpr receiver, String name) {
    if (span == null || span == Span.NONE || name == null || file == null) {
      return null;
    }
    String text = file.content();
    int from = span.start();
    if (receiver != null && isExplicit(receiver) && receiver.span().end() <= span.end()) {
      from = Math.max(from, receiver.span().end());
    }
    int found = wordAt(text, name, from, span.end());
    if (found < 0) {
      found = wordAt(text, name, span.start(), span.end());
    }
    return found < 0 ? null : new Span(found, found + name.length());
  }

  /** False for the implicit {@code this} of {@code f()} or {@code x}, which has no own text. */
  private boolean isExplicit(BExpr receiver) {
    Span s = receiver.span();
    if (s == null || s == Span.NONE || s.end() <= s.start()) {
      return false;
    }
    if (receiver instanceof BExpr.This || receiver instanceof BExpr.OuterThis) {
      String t = file.content().substring(s.start(), s.end());
      return t.equals("this") || t.endsWith(".this");
    }
    return true;
  }

  private static int wordAt(String text, String word, int from, int to) {
    for (int i = text.indexOf(word, from);
        i >= 0 && i + word.length() <= to;
        i = text.indexOf(word, i + 1)) {
      boolean startOk = i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1));
      int end = i + word.length();
      boolean endOk = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
      if (startOk && endOk) {
        return i;
      }
    }
    return -1;
  }

  private static int lastWordAt(String text, String word, int from, int to) {
    int last = -1;
    for (int i = wordAt(text, word, from, to); i >= 0; i = wordAt(text, word, i + 1, to)) {
      last = i;
    }
    return last;
  }

  /** What editors show for a method: the declared local function for a hoisted one. */
  private static MethodSymbol view(MethodSymbol m) {
    return m.sourceView() != null ? m.sourceView() : m;
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
      Span decl = p.decl() != null ? p.decl().nameSpan() : headerSpan(sym, p.name());
      if (decl != null) {
        add(decl, Kind.PROPERTY, p, p.type(), true); // record components are declared in the header
      }
    }
    if (sym.isEnum() && sym.decl() != null) {
      for (var ec : sym.decl().enumConstants()) {
        FieldSymbol f = sym.field(ec.name());
        if (f != null) {
          add(ec.nameSpan(), Kind.FIELD, f, f.type(), true);
        }
      }
    }
    for (BClass.Method m : c.methods()) {
      MethodSymbol ms = view(m.sym());
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
    if (v == null
        || v.name().startsWith("$")
        || v.name().equals("_")
        || v.span() == null
        || v.has(io.github.matrixidot.jsharp.compiler.symbols.Flags.SYNTHETIC)
        || localDecls.containsKey(v)) {
      return; // synthetic, or a captured variable seen again as a hoisted function's parameter
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

  /** An assignment target; {@code span} is the whole assignment or increment. */
  private void lvalue(BLValue lv, Span span) {
    switch (lv) {
      case BLValue.LocalLV l -> {
        Span name = nameIn(span, null, l.var().name());
        if (name != null) {
          add(name, Kind.LOCAL, l.var(), l.var().type(), false, name);
        }
      }
      case BLValue.FieldLV f -> {
        expr(f.receiver());
        Span name = nameIn(span, f.receiver(), f.field().name());
        if (name != null) {
          add(name, Kind.FIELD, f.field(), f.type(), false, name);
        }
      }
      case BLValue.ArrayLV a -> {
        expr(a.array());
        expr(a.index());
      }
      case BLValue.PropertyLV p -> {
        expr(p.receiver());
        p.extraArgs().forEach(this::expr);
        Span name = nameIn(span, p.receiver(), p.property().name());
        if (name != null) {
          add(name, Kind.PROPERTY, p.property(), p.type(), false, name);
        }
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
        add(
            f.span(),
            Kind.FIELD,
            f.field(),
            f.type(),
            false,
            nameIn(f.span(), f.receiver(), f.field().name()));
      }
      case BExpr.Call c -> {
        expr(c.receiver());
        c.args().forEach(this::expr);
        MethodSymbol m = view(c.method());
        // An extension call's receiver is its first argument: the name follows it.
        BExpr before =
            c.receiver() != null
                ? c.receiver()
                : m.isExtension() && !c.args().isEmpty() ? c.args().getFirst() : null;
        if (m.property() != null) {
          add(
              c.span(),
              Kind.PROPERTY,
              m.property(),
              c.type(),
              false,
              nameIn(c.span(), before, m.property().name()));
        } else if (m.isConstructor()) {
          add(c.span(), Kind.CONSTRUCTOR, m, c.type(), false, null);
        } else {
          add(c.span(), Kind.METHOD, m, c.type(), false, nameIn(c.span(), before, m.name()));
        }
      }
      case BExpr.New n -> {
        n.args().forEach(this::expr);
        add(
            n.span(),
            Kind.CONSTRUCTOR,
            n.ctor(),
            n.type(),
            false,
            n.ctor() == null ? null : nameIn(n.span(), null, n.ctor().owner().name()));
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
        lvalue(a.target(), a.span());
        expr(a.value());
      }
      case BExpr.CompoundAssign c -> {
        lvalue(c.target(), c.span());
        expr(c.value());
      }
      case BExpr.IncDec i -> lvalue(i.target(), i.span());
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
        if (m.target() != null) {
          MethodSymbol t = view(m.target());
          int at = lastWordAt(file.content(), t.name(), m.span().start(), m.span().end());
          add(
              m.span(),
              Kind.METHOD,
              t,
              m.type(),
              false,
              at < 0 ? null : new Span(at, at + t.name().length()));
        }
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

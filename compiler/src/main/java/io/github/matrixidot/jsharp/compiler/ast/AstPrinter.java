package io.github.matrixidot.jsharp.compiler.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders syntax trees as indented S-expressions. Used by golden parser tests and {@code jsharp
 * check --dump-ast}; the format is stable but not a public API.
 */
public final class AstPrinter {
  private static final int WIDTH = 100;

  private AstPrinter() {}

  /** An S-expression: a head followed by atoms ({@code String}) and nested S-expressions. */
  private record S(String head, List<Object> items) {
    S add(Object o) {
      if (o != null) {
        items.add(o);
      }
      return this;
    }
  }

  private static S s(String head, Object... items) {
    S r = new S(head, new ArrayList<>());
    for (Object o : items) {
      r.add(o);
    }
    return r;
  }

  public static String print(Node node) {
    StringBuilder sb = new StringBuilder();
    render(toS(node), 0, sb);
    return sb.append('\n').toString();
  }

  // ------------------------------------------------------------------ layout

  private static String flat(Object o) {
    if (o instanceof String str) {
      return str;
    }
    S x = (S) o;
    StringBuilder sb = new StringBuilder("(").append(x.head());
    for (Object i : x.items()) {
      sb.append(' ').append(flat(i));
    }
    return sb.append(')').toString();
  }

  private static void render(Object o, int indent, StringBuilder sb) {
    String f = flat(o);
    if (o instanceof String || indent + f.length() <= WIDTH) {
      sb.append(f);
      return;
    }
    S x = (S) o;
    sb.append('(').append(x.head());
    for (Object i : x.items()) {
      sb.append('\n').append(" ".repeat(indent + 2));
      render(i, indent + 2, sb);
    }
    sb.append(')');
  }

  // ------------------------------------------------------------------ conversion

  private static String quote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '\n' -> sb.append("\\n");
        case '\t' -> sb.append("\\t");
        case '\r' -> sb.append("\\r");
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        default -> sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  private static S list(String head, List<? extends Node> nodes) {
    S r = s(head);
    for (Node n : nodes) {
      r.add(toS(n));
    }
    return r;
  }

  private static Object opt(Node n) {
    return n == null ? null : toS(n);
  }

  private static S mods(Modifiers m) {
    if (m == null || m.isEmpty()) {
      return null;
    }
    S r = s("mods");
    for (Annotation a : m.annotations()) {
      r.add(toS(a));
    }
    for (Modifiers.Item i : m.list()) {
      r.add(i.modifier().keyword());
    }
    return r;
  }

  private static S typeParams(List<TypeParam> tps) {
    return tps.isEmpty() ? null : list("tparams", tps);
  }

  private static Object toS(Node n) {
    return switch (n) {
      case CompilationUnit u -> {
        S r = s("unit");
        if (!u.fileAnnotations().isEmpty()) {
          r.add(list("file-annotations", u.fileAnnotations()));
        }
        r.add(opt(u.pkg()));
        for (ImportDecl i : u.imports()) {
          r.add(toS(i));
        }
        for (Decl d : u.members()) {
          r.add(toS(d));
        }
        yield r;
      }
      case PackageDecl p -> s("package", p.name());
      case ImportDecl i ->
          s(
              "import",
              i.isStatic() ? "static" : null,
              i.name() + (i.wildcard() ? ".*" : ""),
              i.alias() == null ? null : "as " + i.alias());
      case Annotation a -> {
        S r = s("@" + (a.target() == null ? "" : a.target() + ":") + typeStr(a.name()));
        for (Arg arg : a.args()) {
          r.add(toS(arg));
        }
        yield r;
      }
      case Modifiers m -> mods(m) == null ? s("mods") : mods(m);
      case Arg a -> a.name() == null ? toS(a.value()) : s("named " + a.name(), toS(a.value()));
      case Param p ->
          s(
              "param",
              mods(p.modifiers()),
              p.isThis() ? "this" : null,
              p.isParams() ? "params" : null,
              p.type() == null ? null : typeStr(p.type()),
              p.name(),
              p.defaultValue() == null ? null : s("=", toS(p.defaultValue())));
      case TypeParam tp -> {
        S r =
            s(
                "tparam",
                tp.variance() == TypeParam.Variance.INVARIANT
                    ? null
                    : tp.variance().name().toLowerCase(java.util.Locale.ROOT),
                tp.name());
        for (TypeNode b : tp.bounds()) {
          r.add(s(":", typeStr(b)));
        }
        yield r;
      }
      case TypeNode t -> typeStr(t);
      case Decl d -> declS(d);
      case Stmt st -> stmtS(st);
      case Expr e -> exprS(e);
      case Pattern p -> patS(p);
      case Accessor a ->
          s(a.kind().name().toLowerCase(java.util.Locale.ROOT), mods(a.modifiers()), opt(a.body()));
      case EnumConstant c -> {
        S r = s("const", c.name());
        if (c.args() != null) {
          r.add(list("args", c.args()));
        }
        yield r;
      }
      case CatchClause c -> {
        S r = s("catch");
        S types = s("types");
        for (TypeNode t : c.types()) {
          types.add(typeStr(t));
        }
        r.add(types).add(c.name());
        if (c.filter() != null) {
          r.add(s("when", toS(c.filter())));
        }
        yield r.add(toS(c.body()));
      }
      case SwitchArm a ->
          s(
              "arm",
              toS(a.pattern()),
              a.guard() == null ? null : s("when", toS(a.guard())),
              toS(a.body()));
      case SwitchSection sec -> {
        S r = s("section");
        for (SwitchSection.Label l : sec.labels()) {
          r.add(
              l.pattern() == null
                  ? "default"
                  : s(
                      "case",
                      toS(l.pattern()),
                      l.guard() == null ? null : s("when", toS(l.guard()))));
        }
        for (Stmt st : sec.body()) {
          r.add(toS(st));
        }
        yield r;
      }
      case FieldInit f -> s("=", f.name(), toS(f.value()));
      case VarDeclarator v -> v.init() == null ? v.name() : s("=", v.name(), toS(v.init()));
      case DeconstructVar d ->
          d.nested() != null
              ? list("nested", d.nested())
              : d.type() == null ? d.name() : s(typeStr(d.type()), d.name());
      case Body.Block b -> toS(b.block());
      case Body.ExprBody b -> s("=>", toS(b.expr()));
    };
  }

  private static S declS(Decl d) {
    return switch (d) {
      case Decl.TypeDecl t -> {
        S r = s(t.kind().keyword(), t.name(), mods(t.modifiers()), typeParams(t.typeParams()));
        if (t.header() != null) {
          r.add(list("header", t.header()));
        }
        if (!t.supertypes().isEmpty()) {
          S sup = s(":");
          t.supertypes().forEach(x -> sup.add(typeStr(x)));
          r.add(sup);
        }
        if (t.permits() != null) {
          S p = s("permits");
          t.permits().forEach(x -> p.add(typeStr(x)));
          r.add(p);
        }
        for (EnumConstant c : t.enumConstants()) {
          r.add(toS(c));
        }
        for (Decl m : t.members()) {
          r.add(declS(m));
        }
        yield r;
      }
      case Decl.Method m -> {
        S r =
            s(
                "method",
                m.name(),
                mods(m.modifiers()),
                typeParams(m.typeParams()),
                m.returnType() == null ? "<inferred>" : typeStr(m.returnType()));
        r.add(list("params", m.params()));
        yield r.add(m.body() == null ? "<abstract>" : toS(m.body()));
      }
      case Decl.Constructor c -> {
        S r = s(c.params() == null ? "compact-ctor" : "ctor", c.name(), mods(c.modifiers()));
        if (c.params() != null) {
          r.add(list("params", c.params()));
        }
        yield r.add(opt(c.body()));
      }
      case Decl.Field f -> {
        S r =
            s(
                "field",
                mods(f.modifiers()),
                f.kind() == LocalKind.TYPED
                    ? typeStr(f.type())
                    : f.kind().name().toLowerCase(java.util.Locale.ROOT));
        for (VarDeclarator v : f.vars()) {
          r.add(toS(v));
        }
        yield r;
      }
      case Decl.Property p -> {
        S r = s("property", p.name(), mods(p.modifiers()), typeStr(p.type()));
        if (p.accessors() != null) {
          for (Accessor a : p.accessors()) {
            r.add(toS(a));
          }
        }
        if (p.getter() != null) {
          r.add(s("=>", toS(p.getter())));
        }
        if (p.initializer() != null) {
          r.add(s("=", toS(p.initializer())));
        }
        yield r;
      }
      case Decl.Initializer i -> s(i.isStatic() ? "static-init" : "init", toS(i.body()));
      case Decl.TopLevelStmt t -> s("top", toS(t.stmt()));
    };
  }

  private static S stmtS(Stmt st) {
    return switch (st) {
      case Stmt.Block b -> list("block", b.stmts());
      case Stmt.LocalVar v -> {
        S r =
            s(
                "local",
                mods(v.modifiers()),
                v.kind() == LocalKind.TYPED
                    ? typeStr(v.type())
                    : v.kind().name().toLowerCase(java.util.Locale.ROOT));
        for (VarDeclarator d : v.vars()) {
          r.add(toS(d));
        }
        yield r;
      }
      case Stmt.Deconstruct d -> {
        S r = s("deconstruct", d.kind().name().toLowerCase(java.util.Locale.ROOT));
        r.add(list("targets", d.vars()));
        yield r.add(toS(d.init()));
      }
      case Stmt.LocalType t -> s("local-type", declS(t.decl()));
      case Stmt.LocalFunction f -> s("local-function", declS(f.decl()));
      case Stmt.ExprStmt e -> s("expr", toS(e.expr()));
      case Stmt.If i -> s("if", toS(i.cond()), toS(i.then()), opt(i.otherwise()));
      case Stmt.While w -> s("while", toS(w.cond()), toS(w.body()));
      case Stmt.DoWhile w -> s("do-while", toS(w.body()), toS(w.cond()));
      case Stmt.For f -> {
        S init = list("init", f.init());
        S upd = list("update", f.update());
        yield s("for", init, f.cond() == null ? "<none>" : toS(f.cond()), upd, toS(f.body()));
      }
      case Stmt.Foreach f ->
          s(
              "foreach",
              f.kind() == LocalKind.TYPED && f.type() != null
                  ? typeStr(f.type())
                  : f.kind().name().toLowerCase(java.util.Locale.ROOT),
              f.deconstruct() != null ? list("targets", f.deconstruct()) : f.name(),
              s("in", toS(f.iterable())),
              toS(f.body()));
      case Stmt.Break b -> s("break", b.label());
      case Stmt.Continue c -> s("continue", c.label());
      case Stmt.Return r -> s("return", opt(r.value()));
      case Stmt.Throw t -> s("throw", t.value() == null ? "<rethrow>" : toS(t.value()));
      case Stmt.Labeled l -> s("label " + l.label(), toS(l.body()));
      case Stmt.Switch sw -> {
        S r = s("switch-stmt", toS(sw.selector()));
        sw.sections().forEach(x -> r.add(toS(x)));
        yield r;
      }
      case Stmt.Try t -> {
        S r = s("try", toS(t.body()));
        t.catches().forEach(c -> r.add(toS(c)));
        if (t.finallyBlock() != null) {
          r.add(s("finally", toS(t.finallyBlock())));
        }
        yield r;
      }
      case Stmt.Using u -> s("using", toS(u.resource()), toS(u.body()));
      case Stmt.UsingDecl u -> s("using-decl", toS(u.decl()));
      case Stmt.Lock l -> s("lock", toS(l.monitor()), toS(l.body()));
      case Stmt.Checked c -> s("checked-block", toS(c.body()));
      case Stmt.Empty e -> s("empty");
    };
  }

  private static Object exprS(Expr e) {
    return switch (e) {
      case Expr.Literal l ->
          switch (l.kind()) {
            case STRING -> quote((String) l.value());
            case CHAR -> "'" + quote(String.valueOf(l.value())).replace("\"", "") + "'";
            case NULL -> "null";
            default -> l.text();
          };
      case Expr.Interpolated i -> {
        S r = s("interp");
        for (Expr.Part p : i.parts()) {
          switch (p) {
            case Expr.TextPart t -> r.add(quote(t.text()));
            case Expr.HolePart h ->
                r.add(
                    s(
                        "hole",
                        toS(h.expr()),
                        h.format() == null ? null : "format=" + quote(h.format())));
          }
        }
        yield r;
      }
      case Expr.Name n -> n.typeArgs().isEmpty() ? n.name() : n.name() + typeArgsStr(n.typeArgs());
      case Expr.Member m ->
          s(m.nullSafe() ? "?." : ".", toS(m.target()), m.name() + typeArgsStr(m.typeArgs()));
      case Expr.Call c -> {
        S r = s("call", toS(c.callee()));
        c.args().forEach(a -> r.add(toS(a)));
        yield r;
      }
      case Expr.Index i -> s(i.nullSafe() ? "?[]" : "[]", toS(i.target()), toS(i.index()));
      case Expr.New nw -> {
        S r = s("new", nw.type() == null ? "<target-typed>" : typeStr(nw.type()));
        if (nw.args() != null) {
          r.add(list("args", nw.args()));
        }
        if (nw.init() != null) {
          r.add(list("init", nw.init()));
        }
        if (nw.anonBody() != null) {
          S body = s("anon-body");
          nw.anonBody().forEach(d -> body.add(declS(d)));
          r.add(body);
        }
        yield r;
      }
      case Expr.NewArray na -> {
        S r = s("new-array", typeStr(na.elementType()));
        na.dims().forEach(d -> r.add(s("dim", toS(d))));
        if (na.extraDims() > 0) {
          r.add("[]".repeat(na.extraDims()));
        }
        yield r.add(opt(na.init()));
      }
      case Expr.ArrayInit ai -> list("array-init", ai.elements());
      case Expr.CollectionLiteral cl -> list("collection", cl.elements());
      case Expr.Spread sp -> s("..", toS(sp.expr()));
      case Expr.MapLiteral ml -> {
        S r = s("map");
        ml.entries().forEach(en -> r.add(s(":", toS(en.key()), toS(en.value()))));
        yield r;
      }
      case Expr.Unary u ->
          s(
              u.op() == Expr.UnaryOp.NON_NULL
                  ? "!!"
                  : u.op().isPostfix() ? "post" + u.op().symbol() : u.op().symbol(),
              toS(u.operand()));
      case Expr.Binary b -> s(b.op().symbol(), toS(b.left()), toS(b.right()));
      case Expr.Range r ->
          s("..", r.from() == null ? "_" : toS(r.from()), r.to() == null ? "_" : toS(r.to()));
      case Expr.Assign a -> s(a.op().symbol(), toS(a.target()), toS(a.value()));
      case Expr.Conditional c ->
          s(c.ifSyntax() ? "if-expr" : "?:", toS(c.cond()), toS(c.then()), toS(c.otherwise()));
      case Expr.Switch sw -> {
        S r = s("switch", toS(sw.selector()));
        sw.arms().forEach(a -> r.add(toS(a)));
        yield r;
      }
      case Expr.Is i -> s("is", toS(i.expr()), toS(i.pattern()));
      case Expr.As a -> s("as", toS(a.expr()), typeStr(a.type()));
      case Expr.Cast c -> s("cast", typeStr(c.type()), toS(c.expr()));
      case Expr.Lambda l -> {
        S ps = list("params", l.params());
        yield s(l.isAsync() ? "async-lambda" : "lambda", ps, toS(l.body()));
      }
      case Expr.MethodRef m ->
          s("::", m.target() != null ? toS(m.target()) : typeStr(m.typeTarget()), m.name());
      case Expr.This t -> t.qualifier() == null ? "this" : t.qualifier() + ".this";
      case Expr.Super sp -> sp.qualifier() == null ? "super" : s("super", toS(sp.qualifier()));
      case Expr.TypeOf t -> s("typeof", typeStr(t.type()));
      case Expr.NameOf n -> s("nameof", toS(n.expr()));
      case Expr.Tuple t -> list("tuple", t.elements());
      case Expr.With w -> {
        S r = s("with", toS(w.target()));
        w.inits().forEach(i -> r.add(toS(i)));
        yield r;
      }
      case Expr.Throw t -> s("throw-expr", toS(t.expr()));
      case Expr.Await a -> s("await", toS(a.expr()));
      case Expr.Checked c -> s("checked", toS(c.expr()));
      case Expr.Paren p -> s("paren", toS(p.expr()));
      case Expr.Error err -> "<error>";
    };
  }

  private static Object patS(Pattern p) {
    return switch (p) {
      case Pattern.Type t -> s("type-pat", typeStr(t.type()), t.binding());
      case Pattern.Var v -> s("var", v.name());
      case Pattern.Discard d -> "_";
      case Pattern.Constant c -> s("const", toS(c.value()));
      case Pattern.Relational r -> s(r.op().symbol(), toS(r.value()));
      case Pattern.Recursive r -> {
        S x = s("rec", r.type() == null ? "<no-type>" : typeStr(r.type()));
        if (r.positional() != null) {
          S pos = s("pos");
          r.positional().forEach(q -> pos.add(patS(q)));
          x.add(pos);
        }
        if (r.properties() != null) {
          S props = s("props");
          r.properties()
              .forEach(q -> props.add(s(String.join(".", q.path()) + ":", patS(q.pattern()))));
          x.add(props);
        }
        yield x.add(r.binding());
      }
      case Pattern.And a -> s("and", patS(a.left()), patS(a.right()));
      case Pattern.Or o -> s("or", patS(o.left()), patS(o.right()));
      case Pattern.Not nt -> s("not", patS(nt.pattern()));
      case Pattern.Paren pp -> s("paren-pat", patS(pp.pattern()));
      case Pattern.ListPattern l -> {
        S x = s("list-pat");
        l.elements().forEach(q -> x.add(patS(q)));
        yield x.add(l.binding());
      }
      case Pattern.Slice sl -> sl.pattern() == null ? ".." : s("..", patS(sl.pattern()));
    };
  }

  /** Renders a type in source-like syntax. */
  public static String typeStr(TypeNode t) {
    return switch (t) {
      case TypeNode.Named n -> {
        StringBuilder sb = new StringBuilder();
        for (TypeNode.Segment seg : n.segments()) {
          if (!sb.isEmpty()) {
            sb.append('.');
          }
          sb.append(seg.name());
          if (seg.diamond()) {
            sb.append("<>");
          } else {
            sb.append(typeArgsStr(seg.typeArgs()));
          }
        }
        yield sb.toString();
      }
      case TypeNode.Primitive p -> p.kind().keyword();
      case TypeNode.Array a -> typeStr(a.element()) + "[]";
      case TypeNode.Nullable nl -> typeStr(nl.inner()) + "?";
      case TypeNode.Tuple tp -> {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < tp.elements().size(); i++) {
          if (i > 0) {
            sb.append(", ");
          }
          TypeNode.Tuple.Element el = tp.elements().get(i);
          sb.append(typeStr(el.type()));
          if (el.name() != null) {
            sb.append(' ').append(el.name());
          }
        }
        yield sb.append(')').toString();
      }
      case TypeNode.Wildcard w ->
          w.bound() == null
              ? "?"
              : w.variance().name().toLowerCase(java.util.Locale.ROOT) + " " + typeStr(w.bound());
    };
  }

  private static String typeArgsStr(List<TypeNode> args) {
    if (args.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder("<");
    for (int i = 0; i < args.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(typeStr(args.get(i)));
    }
    return sb.append('>').toString();
  }
}

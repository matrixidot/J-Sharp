package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.ast.AstWalk;
import io.github.matrixidot.jsharp.compiler.ast.CatchClause;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.ast.LocalKind;
import io.github.matrixidot.jsharp.compiler.ast.Modifier;
import io.github.matrixidot.jsharp.compiler.ast.Node;
import io.github.matrixidot.jsharp.compiler.ast.Stmt;
import io.github.matrixidot.jsharp.compiler.ast.TypeNode;
import io.github.matrixidot.jsharp.compiler.ast.VarDeclarator;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.bound.BStmt;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import io.github.matrixidot.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Statement checking with integrated flow analysis: reachability, definite assignment, smart-cast
 * narrowing across branches and loops, and break/continue/return/throw handling.
 */
final class Stmts {
  private final Attr a;

  Stmts(Attr a) {
    this.a = a;
  }

  private Env env() {
    return a.env;
  }

  // ------------------------------------------------------------------ blocks

  BStmt.Block block(Stmt.Block b) {
    Scope saved = env().scope;
    env().scope = saved.child();
    for (Stmt s : b.stmts()) {
      if (s instanceof Stmt.LocalFunction lf) {
        env().scope.laterFunctions.add(lf.decl().name());
      }
    }
    try {
      return new BStmt.Block(statements(b.stmts(), 0), b.span());
    } finally {
      env().scope = saved;
    }
  }

  /** Statements from {@code start}; a {@code using var} wraps the rest of the block. */
  List<BStmt> statements(List<Stmt> stmts, int start) {
    List<BStmt> out = new ArrayList<>();
    boolean reportedUnreachable = false;
    for (int i = start; i < stmts.size(); i++) {
      Stmt s = stmts.get(i);
      if (!env().flow.alive && !(s instanceof Stmt.Empty) && !(s instanceof Stmt.LocalType)) {
        if (!reportedUnreachable) {
          a.error(Code.UNREACHABLE_CODE, s.span(), "unreachable code");
          reportedUnreachable = true;
        }
        env().flow.alive = true; // keep checking the rest without cascades
      }
      if (s instanceof Stmt.UsingDecl ud) {
        out.add(usingDecl(ud, stmts, i + 1));
        break;
      }
      out.add(stmt(s));
    }
    if (reportedUnreachable) {
      env().flow.alive = false; // the end of a block containing dead code is itself unreachable
    }
    return out;
  }

  BStmt stmt(Stmt s) {
    return switch (s) {
      case Stmt.Block b -> block(b);
      case Stmt.LocalVar lv -> localVar(lv);
      case Stmt.Deconstruct d -> a.patterns.deconstruct(d);
      case Stmt.LocalType lt -> a.patterns.localClass(lt);
      case Stmt.LocalFunction lf -> a.localFunctions.declare(lf);
      case Stmt.ExprStmt es -> exprStmt(es);
      case Stmt.If i -> ifStmt(i);
      case Stmt.While w -> whileStmt(w, null);
      case Stmt.DoWhile d -> doWhile(d, null);
      case Stmt.For f -> forStmt(f, null);
      case Stmt.Foreach f -> foreach(f, null);
      case Stmt.Labeled l -> labeled(l);
      case Stmt.Break b -> jump(b.label(), true, b.span());
      case Stmt.Continue c -> jump(c.label(), false, c.span());
      case Stmt.Return r -> returnStmt(r);
      case Stmt.Throw t -> throwStmt(t);
      case Stmt.Try t -> tryStmt(t);
      case Stmt.Using u -> using(u);
      case Stmt.UsingDecl ud -> usingDecl(ud, List.of(), 0);
      case Stmt.Lock l -> lock(l);
      case Stmt.Checked c -> {
        boolean saved = env().checked;
        env().checked = true;
        try {
          yield block(c.body());
        } finally {
          env().checked = saved;
        }
      }
      case Stmt.Switch sw -> a.patterns.switchStmt(sw);
      case Stmt.Empty e -> new BStmt.Empty(e.span());
    };
  }

  /** An embedded statement (loop/if body): its own scope. */
  private BStmt embedded(Stmt s) {
    Scope saved = env().scope;
    env().scope = saved.child();
    try {
      return stmt(s);
    } finally {
      env().scope = saved;
    }
  }

  // ------------------------------------------------------------------ declarations

  void checkRedeclaration(String name, Span span) {
    if (name.equals("_")) {
      return;
    }
    VarSymbol prev = env().scope.lookupWithinBoundary(name);
    if (prev == null && env().scope.lookup(name) != null) {
      prev = env().scope.lookup(name).var();
    }
    // A pattern binding that is not definitely assigned here is out of (flow) scope: reusable.
    if (prev != null
        && prev.kind() == VarSymbol.Kind.PATTERN
        && !env().flow.assigned.get(prev.id())) {
      prev = null;
    }
    if (prev != null) {
      a.report(
          a.err(
                  Code.DUPLICATE_VARIABLE,
                  span,
                  "variable '" + name + "' is already defined in this scope")
              .note("previous declaration", a.file(), prev.span()));
    }
  }

  /** Declares a local variable in the current scope. */
  VarSymbol declare(String name, Type type, long flags, VarSymbol.Kind kind, Span span) {
    checkRedeclaration(name, span);
    VarSymbol v = env().newVar(name, type, flags, kind, span);
    if (!name.equals("_")) {
      env().scope.vars.put(name, v);
    }
    return v;
  }

  private BStmt localVar(Stmt.LocalVar lv) {
    List<BStmt> out = new ArrayList<>();
    boolean isVal = lv.kind() == LocalKind.VAL || lv.modifiers().has(Modifier.FINAL);
    Type declared = lv.kind() == LocalKind.TYPED ? a.resolveType(lv.type()) : null;
    if (declared == PrimType.VOID) {
      a.error(Code.INVALID_VOID, lv.type().span(), "a variable cannot have type void");
      declared = Type.ErrorType.INSTANCE;
    }
    boolean atomic = lv.modifiers().has(Modifier.ATOMIC);
    if (atomic && isVal) {
      a.report(
          a.err(
                  Code.INVALID_MODIFIER,
                  lv.modifiers().list().stream()
                      .filter(i -> i.modifier() == Modifier.ATOMIC)
                      .findFirst()
                      .orElseThrow()
                      .span(),
                  "a 'val' never changes, so it cannot be atomic")
              .help("write 'atomic var' for a variable that lambdas may update"));
      atomic = false;
    }
    for (VarDeclarator d : lv.vars()) {
      out.add(declareLocal(d, declared, lv.kind(), isVal, atomic));
    }
    return out.size() == 1 ? out.getFirst() : new BStmt.Block(out, lv.span());
  }

  BStmt.LocalDecl declareLocal(VarDeclarator d, Type declared, LocalKind kind, boolean isVal) {
    return declareLocal(d, declared, kind, isVal, false);
  }

  /** The cell holding an atomic local of type {@code t} (D084). */
  private Type atomicCellType(Type t) {
    String cls =
        t == PrimType.INT
            ? "java/util/concurrent/atomic/AtomicInteger"
            : t == PrimType.LONG
                ? "java/util/concurrent/atomic/AtomicLong"
                : t == PrimType.BOOLEAN ? "java/util/concurrent/atomic/AtomicBoolean" : null;
    if (cls != null) {
      return a.syms.wellKnown(cls);
    }
    io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol ref =
        a.syms.lookup("java/util/concurrent/atomic/AtomicReference");
    Type elem = t instanceof PrimType p ? a.syms.boxed(p) : t;
    return new Type.ClassType(ref, List.of(elem), Nullness.NON_NULL);
  }

  private BStmt.LocalDecl declareLocal(
      VarDeclarator d, Type declared, LocalKind kind, boolean isVal, boolean atomic) {
    BExpr init = null;
    Type type = declared;
    if (d.init() != null) {
      if (declared != null) {
        init = a.exprCoerced(d.init(), declared, d.init().span());
      } else {
        init = a.value(d.init(), null);
        type = inferredLocalType(init, d);
        if (!type.isError() && init.type() != type && !init.type().isError()) {
          init = a.coerce(init, type, d.init().span());
        }
      }
    } else if (declared == null) {
      a.report(
          a.err(
                  Code.CANNOT_INFER,
                  d.nameSpan(),
                  "'"
                      + (kind == LocalKind.VAL ? "val" : "var")
                      + " "
                      + d.name()
                      + "' needs an initializer")
              .help(
                  kind == LocalKind.VAL
                      ? "give it a value: 'val " + d.name() + " = ...;'"
                      : "give it a value, or write its type: 'int " + d.name() + ";'"));
      type = Type.ErrorType.INSTANCE;
    }
    VarSymbol v =
        declare(
            d.name(),
            type,
            (isVal ? Flags.FINAL : 0) | (atomic ? Flags.ATOMIC : 0),
            VarSymbol.Kind.LOCAL,
            d.nameSpan());
    if (atomic) {
      if (init == null) {
        a.error(
            Code.UNINITIALIZED_VARIABLE,
            d.nameSpan(),
            "atomic variable '" + d.name() + "' needs an initial value");
      }
      if (!type.isError()) {
        v.setCell(
            env()
                .newVar(
                    d.name(),
                    atomicCellType(type),
                    Flags.FINAL | Flags.SYNTHETIC,
                    VarSymbol.Kind.LOCAL,
                    d.nameSpan()));
        if (!a.isSpeculative()) {
          a.atomicVars.add(v);
          a.atomicFiles.put(v, a.file());
        }
      }
    }
    if (init != null) {
      env().flow.assigned.set(v.id());
      if (!atomic
          && type.isReference()
          && type.nullness() != Nullness.NON_NULL
          && init.type().isReference()
          && init.type().nullness() == Nullness.NON_NULL
          && !(init.type() instanceof Type.NullType)) {
        env().flow.narrowed.put(v, type.withNullness(Nullness.NON_NULL));
      }
    }
    return new BStmt.LocalDecl(v, init, d.span());
  }

  private Type inferredLocalType(BExpr init, VarDeclarator d) {
    Type t = init.type();
    if (t instanceof Type.NullType) {
      a.report(
          a.err(
                  Code.CANNOT_INFER,
                  d.nameSpan(),
                  "cannot infer the type of '" + d.name() + "' from null")
              .help("declare the type: 'String? " + d.name() + " = null;'"));
      return Type.ErrorType.INSTANCE;
    }
    if (t == PrimType.VOID) {
      a.error(
          Code.VOID_VALUE,
          d.init().span(),
          "cannot assign a void expression to '" + d.name() + "'");
      return Type.ErrorType.INSTANCE;
    }
    if (t instanceof Type.NeverType) {
      return Type.ErrorType.INSTANCE;
    }
    return a.types.uncapture(t);
  }

  // ------------------------------------------------------------------ expression statements

  boolean isStatementExpression(Expr e) {
    return switch (e) {
      case Expr.Call c -> true;
      case Expr.Assign as -> true;
      case Expr.New n -> true;
      case Expr.Await aw -> true;
      case Expr.Throw t -> true;
      case Expr.Unary u ->
          u.op() == Expr.UnaryOp.PRE_INC
              || u.op() == Expr.UnaryOp.PRE_DEC
              || u.op() == Expr.UnaryOp.POST_INC
              || u.op() == Expr.UnaryOp.POST_DEC;
      case Expr.Member m -> m.nullSafe() && false;
      case Expr.Error err -> true;
      default -> false;
    };
  }

  private BStmt exprStmt(Stmt.ExprStmt es) {
    Expr e = es.expr();
    boolean ok = isStatementExpression(e) || isNullSafeCall(e);
    BExpr b = a.expr(e, null);
    if (!ok && !b.type().isError()) {
      a.report(
          a.err(
                  Code.NOT_A_STATEMENT,
                  e.span(),
                  "this expression has no effect and cannot be used as a statement")
              .help(
                  e instanceof Expr.Binary bin && bin.op() == Expr.BinaryOp.EQ
                      ? "did you mean '=' (assignment) instead of '=='?"
                      : null));
    }
    if (b.type() instanceof Type.NeverType) {
      env().flow.alive = false;
    }
    return new BStmt.ExprStmt(b, es.span());
  }

  private static boolean isNullSafeCall(Expr e) {
    return e instanceof Expr.Call c && c.callee() instanceof Expr.Member m && m.nullSafe();
  }

  // ------------------------------------------------------------------ control flow

  private BStmt ifStmt(Stmt.If i) {
    boolean reachable = env().flow.alive;
    BExpr cond = a.condition(i.cond());
    FlowState t = a.whenTrue;
    FlowState f = a.whenFalse;
    // Like Java, `if` ignores constant conditions for reachability (`if (DEBUG)` idiom); a
    // statically dead branch keeps vacuous definite assignment.
    if (reachable && !t.alive) {
      t.assigned.set(0, 1 << 16);
      t.alive = true;
    }
    if (reachable && !f.alive) {
      f.assigned.set(0, 1 << 16);
      f.alive = true;
    }
    env().flow.set(t);
    BStmt then = embedded(i.then());
    FlowState afterThen = env().flow.copy();
    env().flow.set(f);
    BStmt otherwise = i.otherwise() == null ? null : embedded(i.otherwise());
    env().flow.join(afterThen);
    return new BStmt.If(cond, then, otherwise, i.span());
  }

  /** Drops smart casts of variables assigned anywhere in {@code nodes} (loops re-enter). */
  private void invalidateAssigned(Node... nodes) {
    Set<String> names = new HashSet<>();
    for (Node n : nodes) {
      AstWalk.walk(
          n,
          x -> {
            if (x instanceof Expr.Assign as && as.target() instanceof Expr.Name nm) {
              names.add(nm.name());
            } else if (x instanceof Expr.Unary u
                && u.operand() instanceof Expr.Name nm
                && (u.op() == Expr.UnaryOp.PRE_INC
                    || u.op() == Expr.UnaryOp.PRE_DEC
                    || u.op() == Expr.UnaryOp.POST_INC
                    || u.op() == Expr.UnaryOp.POST_DEC)) {
              names.add(nm.name());
            }
            return true;
          });
    }
    for (String n : names) {
      Scope.Found f = env().scope.lookup(n);
      if (f != null) {
        env().flow.narrowed.remove(f.var());
        if (f.var().id() >= 0) {
          env().flow.maybeAssigned.set(f.var().id()); // a previous iteration may have assigned it
        }
      }
    }
  }

  private Env.Jump pushLoop(String label) {
    Env.Jump j = new Env.Jump(label, new BStmt.Label(label), true, false);
    env().jumps.push(j);
    return j;
  }

  private BStmt whileStmt(Stmt.While w, String label) {
    invalidateAssigned(w.cond(), w.body());
    Env.Jump j = pushLoop(label);
    try {
      BExpr cond = a.condition(w.cond());
      FlowState f = a.whenFalse;
      env().flow.set(a.whenTrue);
      BStmt body = embedded(w.body());
      env().flow.set(f);
      for (FlowState b : j.breaks) {
        env().flow.join(b);
      }
      return new BStmt.While(cond, body, j.label, w.span());
    } finally {
      env().jumps.pop();
    }
  }

  private BStmt doWhile(Stmt.DoWhile d, String label) {
    invalidateAssigned(d.body(), d.cond());
    Env.Jump j = pushLoop(label);
    try {
      BStmt body = embedded(d.body());
      for (FlowState c : j.continues) {
        env().flow.join(c);
      }
      BExpr cond = a.condition(d.cond());
      env().flow.set(a.whenFalse);
      for (FlowState b : j.breaks) {
        env().flow.join(b);
      }
      return new BStmt.DoWhile(body, cond, j.label, d.span());
    } finally {
      env().jumps.pop();
    }
  }

  private BStmt forStmt(Stmt.For f, String label) {
    Scope saved = env().scope;
    env().scope = saved.child();
    try {
      List<BStmt> init = new ArrayList<>();
      for (Stmt s : f.init()) {
        init.add(s instanceof Stmt.ExprStmt es ? forUpdateStmt(es.expr()) : stmt(s));
      }
      List<Node> scanned = new ArrayList<>(f.update());
      scanned.add(f.body());
      if (f.cond() != null) {
        scanned.add(f.cond());
      }
      invalidateAssigned(scanned.toArray(Node[]::new));
      Env.Jump j = pushLoop(label);
      try {
        BExpr cond;
        FlowState exit;
        if (f.cond() == null) {
          cond = null;
          exit = FlowState.dead();
        } else {
          cond = a.condition(f.cond());
          exit = a.whenFalse;
          env().flow.set(a.whenTrue);
        }
        BStmt body = embedded(f.body());
        for (FlowState c : j.continues) {
          env().flow.join(c);
        }
        List<BExpr> update = new ArrayList<>();
        for (Expr u : f.update()) {
          BStmt us = forUpdateStmt(u);
          update.add(((BStmt.ExprStmt) us).expr());
        }
        env().flow.set(exit);
        for (FlowState b : j.breaks) {
          env().flow.join(b);
        }
        return new BStmt.For(init, cond, update, body, j.label, f.span());
      } finally {
        env().jumps.pop();
      }
    } finally {
      env().scope = saved;
    }
  }

  private BStmt forUpdateStmt(Expr e) {
    if (!isStatementExpression(e)) {
      a.error(
          Code.NOT_A_STATEMENT, e.span(), "this expression cannot be used in a for-loop header");
    }
    return new BStmt.ExprStmt(a.expr(e, null), e.span());
  }

  private BStmt foreach(Stmt.Foreach f, String label) {
    BExpr iterable = a.value(f.iterable(), null);
    Type it = iterable.type();
    Type elem;
    if (it.isError()) {
      elem = Type.ErrorType.INSTANCE;
    } else {
      a.checkReceiverNullness(iterable, f.iterable().span());
      elem = a.types.iterableElement(a.types.boxIfPrimitive(it));
      if (elem == null) {
        elem = a.patterns.iterableViaExtension(iterable, f.iterable().span());
      }
      if (elem == null) {
        a.report(
            a.err(
                    Code.NOT_ITERABLE,
                    f.iterable().span(),
                    "foreach needs an array or an Iterable, found " + it.display())
                .help(
                    a.types.isSubclassOf(it, "java/util/stream/Stream")
                        ? "use stream.toList() or iterate with forEach"
                        : a.types.isSubclassOf(it, "java/util/Map")
                            ? "iterate map.entrySet(), map.keySet() or map.values()"
                            : null));
        elem = Type.ErrorType.INSTANCE;
      }
    }
    elem = a.types.uncapture(elem);
    Scope saved = env().scope;
    env().scope = saved.child();
    invalidateAssigned(f.body());
    Env.Jump j = pushLoop(label);
    FlowState before = env().flow.copy();
    try {
      VarSymbol var;
      List<BStmt> destructure = new ArrayList<>();
      boolean isVal = f.kind() == LocalKind.VAL;
      if (f.deconstruct() != null) {
        var =
            env()
                .newVar(
                    "$elem", elem, Flags.FINAL | Flags.SYNTHETIC, VarSymbol.Kind.FOREACH, f.span());
        env().flow.assigned.set(var.id());
        destructure.addAll(
            a.patterns.destructureInto(
                f.deconstruct(), new BExpr.Local(var, f.span()), f.kind(), f.span()));
      } else {
        Type vt = elem;
        if (f.type() != null) {
          vt = a.resolveType(f.type());
          if (!vt.isError()
              && !elem.isError()
              && a.types.assignConversion(elem, vt, null) == Types.Conv.NONE) {
            a.error(
                Code.TYPE_MISMATCH,
                f.type().span(),
                "elements are " + elem.display() + ", which cannot be assigned to " + vt.display());
          } else if (!vt.isError() && !elem.isError() && !a.types.nullnessCompatible(elem, vt)) {
            a.report(
                a.err(
                        Code.NULLABILITY_MISMATCH,
                        f.type().span(),
                        "elements of type "
                            + elem.display()
                            + " may be null, but "
                            + vt.display()
                            + " is non-null")
                    .help("declare the loop variable as " + vt.display() + "?"));
          }
        }
        var = declare(f.name(), vt, isVal ? Flags.FINAL : 0, VarSymbol.Kind.FOREACH, f.nameSpan());
        env().flow.assigned.set(var.id());
      }
      BStmt body = embedded(f.body());
      env().flow.join(before);
      for (FlowState c : j.continues) {
        env().flow.join(c);
      }
      for (FlowState b : j.breaks) {
        env().flow.join(b);
      }
      return new BStmt.Foreach(var, iterable, elem, destructure, body, j.label, f.span());
    } finally {
      env().jumps.pop();
      env().scope = saved;
    }
  }

  private BStmt labeled(Stmt.Labeled l) {
    for (Env.Jump j : env().jumps) {
      if (l.label().equals(j.name)) {
        a.error(
            Code.DUPLICATE_VARIABLE, l.labelSpan(), "label '" + l.label() + "' is already in use");
      }
    }
    return switch (l.body()) {
      case Stmt.While w -> whileStmt(w, l.label());
      case Stmt.DoWhile d -> doWhile(d, l.label());
      case Stmt.For f -> forStmt(f, l.label());
      case Stmt.Foreach f -> foreach(f, l.label());
      default -> {
        Env.Jump j = new Env.Jump(l.label(), new BStmt.Label(l.label()), false, false);
        env().jumps.push(j);
        try {
          BStmt body = stmt(l.body());
          for (FlowState b : j.breaks) {
            env().flow.join(b);
          }
          yield new BStmt.Labeled(j.label, body, l.span());
        } finally {
          env().jumps.pop();
        }
      }
    };
  }

  private BStmt jump(String label, boolean isBreak, Span span) {
    Env.Jump target = null;
    for (Env.Jump j : env().jumps) {
      if (label != null ? label.equals(j.name) : (isBreak ? j.isLoop || j.isSwitch : j.isLoop)) {
        target = j;
        break;
      }
    }
    if (target == null) {
      if (label != null) {
        a.error(Code.UNKNOWN_LABEL, span, "no enclosing statement is labeled '" + label + "'");
      } else {
        a.error(
            Code.INVALID_JUMP,
            span,
            "'"
                + (isBreak ? "break" : "continue")
                + "' outside of a loop"
                + (isBreak ? " or switch" : ""));
      }
      env().flow.alive = false;
      return new BStmt.Empty(span);
    }
    if (!isBreak && !target.isLoop) {
      a.error(Code.INVALID_JUMP, span, "'continue " + label + "' must name a loop");
    }
    (isBreak ? target.breaks : target.continues).add(env().flow.copy());
    env().flow.alive = false;
    return isBreak ? new BStmt.Break(target.label, span) : new BStmt.Continue(target.label, span);
  }

  private BStmt returnStmt(Stmt.Return r) {
    Env env = env();
    Type rt = env.returnType;
    BExpr value = null;
    if (env.isAsync && env.lambda == null || env.isAsync && env.lambda != null) {
      BStmt s = a.patterns.asyncReturn(r);
      if (s != null) {
        env.flow.alive = false;
        return s;
      }
    }
    if (env.method == null && env.lambda == null) {
      a.error(Code.INVALID_JUMP, r.span(), "'return' is not allowed in an initializer");
    }
    if (r.value() == null) {
      if (rt != null && rt != PrimType.VOID && !rt.isError()) {
        a.error(Code.TYPE_MISMATCH, r.span(), "missing return value of type " + rt.display());
      }
      if (rt == null && env.lambda != null) {
        env.lambda.returnsVoid = true;
      }
    } else if (rt == null) {
      value = a.value(r.value(), null);
      if (env.lambda != null) {
        env.lambda.returnTypes.add(value.type());
      }
    } else if (rt == PrimType.VOID) {
      BExpr v = a.expr(r.value(), null);
      if (!v.type().isError()) {
        a.error(
            Code.TYPE_MISMATCH,
            r.value().span(),
            env.method != null && env.method.has(Flags.ENTRY_POINT) && env.lambda == null
                ? "top-level statements cannot return a value"
                : "a void "
                    + (env.lambda != null ? "lambda" : "method")
                    + " cannot return a value");
      }
      value = null;
    } else {
      value = a.exprCoerced(r.value(), rt, r.value().span());
    }
    env.flow.alive = false;
    return new BStmt.Return(value, r.span());
  }

  private BStmt throwStmt(Stmt.Throw t) {
    BExpr ex;
    if (t.value() == null) {
      if (env().catchVar == null) {
        a.error(Code.INVALID_RETHROW, t.span(), "'throw;' can only rethrow inside a catch block");
        ex = new BExpr.Error(Type.ErrorType.INSTANCE, t.span());
      } else {
        ex = new BExpr.Local(env().catchVar, t.span());
      }
    } else {
      BExpr v = a.value(t.value(), null);
      if (!v.type().isError() && !a.types.isSubtype(v.type(), a.syms.throwableType())) {
        a.error(
            Code.TYPE_MISMATCH,
            t.value().span(),
            "can only throw Throwable values, found " + v.type().display());
      } else if (v.type().nullness() == Nullness.NULLABLE) {
        a.report(
            a.err(Code.NULLABILITY_MISMATCH, t.value().span(), "thrown value may be null")
                .help("use '!' if it cannot be null"));
      }
      ex = v;
    }
    env().flow.alive = false;
    return new BStmt.Throw(ex, t.span());
  }

  private BStmt tryStmt(Stmt.Try t) {
    FlowState start = env().flow.copy();
    BStmt.Block body = block(t.body());
    FlowState afterBody = env().flow.copy();
    FlowState joined = afterBody.copy();
    List<BStmt.Catch> catches = new ArrayList<>();
    List<Type> seen = new ArrayList<>();
    for (CatchClause c : t.catches()) {
      env().flow.set(start.copy());
      Scope saved = env().scope;
      env().scope = saved.child();
      VarSymbol savedCatch = env().catchVar;
      try {
        List<ClassType> caught = new ArrayList<>();
        Type varType;
        if (c.types().isEmpty()) {
          caught.add(a.syms.throwableType());
          varType = a.syms.throwableType();
        } else {
          for (TypeNode tn : c.types()) {
            Type ct = a.resolveType(tn);
            if (ct.isError()) {
              continue;
            }
            if (!(ct instanceof ClassType cct) || !a.types.isSubtype(ct, a.syms.throwableType())) {
              a.error(
                  Code.TYPE_MISMATCH,
                  tn.span(),
                  "can only catch Throwable types, found " + ct.display());
              continue;
            }
            for (Type prev : seen) {
              if (a.types.isSubtype(ct, prev) && c.filter() == null) {
                a.report(
                    a.err(
                        Code.UNREACHABLE_CODE,
                        tn.span(),
                        "this catch clause is unreachable: "
                            + ct.display()
                            + " is already caught by "
                            + prev.display()));
              }
            }
            caught.add(cct);
          }
          varType =
              caught.isEmpty()
                  ? Type.ErrorType.INSTANCE
                  : caught.size() == 1 ? caught.getFirst() : a.types.lub(new ArrayList<>(caught));
        }
        if (c.filter() == null) {
          seen.addAll(caught);
        }
        VarSymbol v = null;
        if (c.name() != null) {
          v =
              declare(
                  c.name(),
                  varType,
                  c.types().size() > 1 ? Flags.FINAL : 0,
                  VarSymbol.Kind.CATCH,
                  c.nameSpan());
        } else {
          v = env().newVar("$caught", varType, Flags.SYNTHETIC, VarSymbol.Kind.CATCH, c.span());
        }
        env().flow.assigned.set(v.id());
        env().catchVar = v;
        BExpr filter = null;
        if (c.filter() != null) {
          filter = a.condition(c.filter());
          env().flow.set(a.whenTrue);
        }
        BStmt.Block cbody = block(c.body());
        joined.join(env().flow);
        catches.add(new BStmt.Catch(caught, v, filter, cbody, c.span()));
      } finally {
        env().scope = saved;
        env().catchVar = savedCatch;
      }
    }
    BStmt.Block fin = null;
    if (t.finallyBlock() != null) {
      env().flow.set(start.copy());
      fin = block(t.finallyBlock());
      boolean finCompletes = env().flow.alive;
      FlowState afterFinally = env().flow.copy();
      env().flow.set(joined);
      env().flow.assigned.or(afterFinally.assigned);
      if (!finCompletes) {
        env().flow.alive = false;
      }
    } else {
      env().flow.set(joined);
    }
    return new BStmt.Try(body, catches, fin, t.span());
  }

  private BStmt using(Stmt.Using u) {
    Scope saved = env().scope;
    env().scope = saved.child();
    try {
      VarSymbol res;
      BExpr init;
      if (u.resource() instanceof Stmt.LocalVar lv) {
        if (lv.vars().size() != 1) {
          a.error(
              Code.UNSUPPORTED_FEATURE,
              lv.span(),
              "declare one resource per 'using'; nest them for several");
        }
        BStmt.LocalDecl d = (BStmt.LocalDecl) localVarSingle(lv);
        res = d.var();
        init = d.init();
      } else {
        Stmt.ExprStmt es = (Stmt.ExprStmt) u.resource();
        init = a.value(es.expr(), null);
        res =
            env()
                .newVar(
                    "$resource",
                    init.type(),
                    Flags.FINAL | Flags.SYNTHETIC,
                    VarSymbol.Kind.RESOURCE,
                    es.span());
        env().flow.assigned.set(res.id());
      }
      checkCloseable(res.type(), u.resource().span());
      BStmt body = embedded(u.body());
      return new BStmt.Using(res, init, body, u.span());
    } finally {
      env().scope = saved;
    }
  }

  private BStmt localVarSingle(Stmt.LocalVar lv) {
    boolean isVal = true; // resources are implicitly final
    Type declared = lv.kind() == LocalKind.TYPED ? a.resolveType(lv.type()) : null;
    return declareLocal(lv.vars().getFirst(), declared, lv.kind(), isVal);
  }

  private void checkCloseable(Type t, Span span) {
    if (t.isError()) {
      return;
    }
    if (!a.types.isSubclassOf(a.types.boxIfPrimitive(t), "java/lang/AutoCloseable")) {
      a.error(
          Code.TYPE_MISMATCH,
          span,
          "'using' needs an AutoCloseable resource, found " + t.display());
    }
  }

  /** {@code using var r = ...;}: the rest of the block becomes the body. */
  private BStmt usingDecl(Stmt.UsingDecl ud, List<Stmt> rest, int from) {
    BStmt.LocalDecl d = (BStmt.LocalDecl) localVarSingle(ud.decl());
    checkCloseable(d.var().type(), ud.span());
    List<BStmt> body = statements(rest, from);
    Span bodySpan =
        body.isEmpty() ? ud.span() : new Span(ud.span().end(), body.getLast().span().end());
    return new BStmt.Using(d.var(), d.init(), new BStmt.Block(body, bodySpan), ud.span());
  }

  private BStmt lock(Stmt.Lock l) {
    BExpr m = a.value(l.monitor(), null);
    if (m.type() instanceof PrimType) {
      a.error(
          Code.TYPE_MISMATCH,
          l.monitor().span(),
          "lock needs an object, found " + m.type().display());
    } else if (!m.type().isError()) {
      a.checkReceiverNullness(m, l.monitor().span());
    }
    BStmt body = block(l.body());
    return new BStmt.Sync(m, body, l.span());
  }

  /** Is a local class name in scope? (used by pattern code for local types) */
  ClassSymbol localClass(String name) {
    return env().scope.lookupClass(name);
  }
}

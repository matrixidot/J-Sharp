package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.ast.Body;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.Param;
import io.github.matrixidot.jsharp.compiler.ast.Stmt;
import io.github.matrixidot.jsharp.compiler.bound.BClass;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.bound.BStmt;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local functions (D083): {@code int sq(int x) => x * x;} inside a block.
 *
 * <p>A local function is visible from its declaration to the end of its block (so it may call
 * itself) and inside lambdas there, but not inside local class bodies. Like a lambda, it may read
 * the effectively final locals around it. It compiles to a private synthetic method of the
 * enclosing class whose leading parameters are the captured locals; every call passes their current
 * values, so a call costs no allocation. Used as a value, it is the lambda {@code (a, ...) => f(a,
 * ...)}.
 *
 * <p>The body is attributed twice: speculatively to learn what it captures, then for real, when
 * recursive calls already know the captures to pass.
 */
final class LocalFunctions {
  private final Attr a;
  private int counter;

  /** Synthetic methods for local functions, per class, collected by {@link ClassChecker}. */
  final Map<ClassSymbol, List<BClass.Method>> methods = new HashMap<>();

  LocalFunctions(Attr a) {
    this.a = a;
  }

  /** A local function in scope. */
  static final class Fn {
    /** The declared signature, for overload checks and messages. */
    final MethodSymbol sym;

    /** The hoisted method that calls invoke: the captures, then the declared parameters. */
    final MethodSymbol impl;

    /** Locals passed as the leading arguments of every call. */
    List<VarSymbol> captures = List.of();

    /** The function reads {@code this} (its method is an instance method). */
    boolean capturesThis;

    Fn(MethodSymbol sym, MethodSymbol impl) {
      this.sym = sym;
      this.impl = impl;
    }
  }

  BStmt declare(Stmt.LocalFunction lf) {
    Decl.Method d = lf.decl();
    Env env = a.env;
    String name = d.name();
    if (!d.typeParams().isEmpty()) {
      a.report(
          a.err(Code.UNSUPPORTED_FEATURE, d.nameSpan(), "local functions cannot be generic")
              .help("declare a private generic method instead"));
      return new BStmt.Empty(lf.span());
    }
    if (d.body() == null) {
      a.error(Code.EXPECTED_TOKEN, d.nameSpan(), "local function '" + name + "' needs a body");
      return new BStmt.Empty(lf.span());
    }
    if (env.scope.lookupWithinBoundary(name) != null || env.scope.functions.containsKey(name)) {
      a.error(
          Code.DUPLICATE_VARIABLE, d.nameSpan(), "'" + name + "' is already defined in this scope");
      return new BStmt.Empty(lf.span());
    }
    Type ret = a.typeResolver.resolveReturn(d.returnType(), a.typeScope());
    List<MethodSymbol.Param> params = new ArrayList<>();
    for (Param p : d.params()) {
      if (p.defaultValue() != null || p.isParams() || p.isThis()) {
        a.error(
            Code.UNSUPPORTED_FEATURE,
            p.span(),
            "parameters of local functions cannot have defaults, 'params' or 'this'");
      }
      Type t = p.type() == null ? Type.ErrorType.INSTANCE : a.resolveType(p.type());
      params.add(MethodSymbol.Param.of(p.name(), t));
    }
    MethodSymbol sym = new MethodSymbol(name, env.cls, Flags.PRIVATE);
    sym.setReturnType(ret);
    sym.setParams(params);
    MethodSymbol impl =
        new MethodSymbol(
            jvmBase(env) + "$" + name + "$" + counter++, env.cls, Flags.PRIVATE | Flags.SYNTHETIC);
    impl.setReturnType(ret);
    Fn fn = new Fn(sym, impl);
    env.scope.functions.put(name, fn);
    env.scope.laterFunctions.remove(name);

    // Pass 1: learn the captures.
    Attr.Speculation<Body1> probe = a.speculate(() -> body(fn, d));
    if (probe.result() != null) {
      classify(fn, probe.result());
    }
    // Pass 2: the real body; recursive calls now pass the captures.
    Body1 real = body(fn, d);
    List<VarSymbol> before = fn.captures;
    boolean thisBefore = fn.capturesThis;
    classify(fn, real);
    if (!before.equals(fn.captures) || thisBefore != fn.capturesThis) {
      // Recursive calls of pass 2 passed the probe's captures; this cannot happen for a body
      // attributed the same way twice, but must never produce wrong code.
      a.error(
          Code.INTERNAL_ERROR,
          d.nameSpan(),
          "inconsistent captures for local function '" + name + "'");
    }
    if (!a.isSpeculative()) {
      List<MethodSymbol.Param> all = new ArrayList<>();
      List<VarSymbol> allVars = new ArrayList<>();
      for (VarSymbol v : fn.captures) {
        VarSymbol passed = v.cell() != null ? v.cell() : v; // atomic locals pass their cell (D084)
        allVars.add(passed);
        all.add(MethodSymbol.Param.of(v.name(), passed.type()));
      }
      all.addAll(params);
      allVars.addAll(real.params());
      impl.setParams(all);
      methods
          .computeIfAbsent(env.cls, k -> new ArrayList<>())
          .add(new BClass.Method(impl, allVars, real.body(), d.span()));
    }
    return new BStmt.Empty(lf.span());
  }

  /**
   * Splits what a body captured: locals become leading parameters, except those a local class
   * already holds in fields (read through {@code this}, as lambdas do).
   */
  private void classify(Fn fn, Body1 b) {
    List<VarSymbol> params = new ArrayList<>();
    boolean viaThis = b.capturesThis();
    for (VarSymbol v : b.captures()) {
      Scope.Found f = a.env.scope.lookup(v.name());
      boolean classField =
          f != null && f.crossed().stream().anyMatch(s -> s.boundary == Scope.Boundary.CLASS);
      if (classField) {
        viaThis = true;
      } else {
        params.add(v);
      }
    }
    fn.captures = List.copyOf(params);
    fn.capturesThis = viaThis;
    long flags = Flags.PRIVATE | (viaThis ? 0 : Flags.STATIC);
    fn.sym.setFlags(flags);
    fn.impl.setFlags(flags | Flags.SYNTHETIC);
  }

  private static String jvmBase(Env env) {
    String m = env.method == null ? "static" : env.method.jvmName();
    return "local$" + m.replace('<', '$').replace('>', '$');
  }

  /** An attributed body with what it captured. */
  private record Body1(
      List<VarSymbol> params, BStmt body, Set<VarSymbol> captures, boolean capturesThis) {}

  private Body1 body(Fn fn, Decl.Method d) {
    LambdaFrame frame = new LambdaFrame();
    Env outer = a.env;
    Env e = outer.nested();
    e.scope = new Scope(outer.scope, Scope.Boundary.LAMBDA, frame, null);
    e.lambda = frame;
    e.isAsync = false;
    e.jumps = new java.util.ArrayDeque<>();
    e.inConstructor = false;
    e.catchVar = null;
    e.returnType = fn.sym.returnType();
    a.env = e;
    try {
      List<VarSymbol> params = new ArrayList<>();
      for (int i = 0; i < d.params().size(); i++) {
        Param p = d.params().get(i);
        VarSymbol v =
            e.newVar(
                p.name(), fn.sym.params().get(i).type(), 0, VarSymbol.Kind.PARAM, p.nameSpan());
        if (!p.name().equals("_")) {
          if (outer.scope.lookup(p.name()) != null) {
            a.error(
                Code.DUPLICATE_VARIABLE,
                p.nameSpan(),
                "parameter '" + p.name() + "' shadows a local variable");
          }
          e.scope.vars.put(p.name(), v);
        }
        e.flow.assigned.set(v.id());
        params.add(v);
      }
      Type ret = e.returnType;
      BStmt stmt;
      switch (d.body()) {
        case Body.ExprBody eb -> {
          if (ret == PrimType.VOID) {
            BExpr v = a.expr(eb.expr(), null);
            if (!a.stmts.isStatementExpression(eb.expr())
                && !v.type().isError()
                && v.type() != PrimType.VOID) {
              a.report(
                  a.err(
                          Code.TYPE_MISMATCH,
                          eb.expr().span(),
                          "a void function cannot return a value")
                      .help("declare a return type, or use a block body"));
            }
            stmt = new BStmt.ExprStmt(v, eb.expr().span());
          } else {
            stmt = new BStmt.Return(a.exprCoerced(eb.expr(), ret), eb.expr().span());
          }
        }
        case Body.Block bb -> {
          stmt = a.stmts.block(bb.block());
          if (e.flow.alive && ret != null && ret != PrimType.VOID && !ret.isError()) {
            a.error(
                Code.MISSING_RETURN,
                d.nameSpan(),
                "function '"
                    + d.name()
                    + "' must return a value of type "
                    + ret.display()
                    + " on every path");
          }
        }
      }
      return new Body1(params, stmt, frame.captures, frame.capturesThis);
    } finally {
      a.env = outer;
    }
  }

  /**
   * The extra leading arguments of a call to {@code fn} from the current code: the captured locals
   * (read here, which also makes enclosing lambdas capture them).
   */
  List<BExpr> captureArgs(Fn fn, Span span) {
    List<BExpr> out = new ArrayList<>();
    for (VarSymbol v : fn.captures) {
      out.add(a.captureValue(v, span));
    }
    return out;
  }
}

package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.ast.Body;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.ast.Param;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.bound.BStmt;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import io.github.matrixidot.jsharp.compiler.types.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Lambdas and method references: their functional interface target, parameter and result types
 * (inferred when the target leaves them open), the lambda body checked in its own frame, and the
 * method a reference resolves to.
 */
final class Lambdas {
  private final Attr a;

  Lambdas(Attr a) {
    this.a = a;
  }

  /** The functional interface type a lambda/method reference converts to, or null (reported). */
  ClassType functionalTarget(Type pt, Span span, String what) {
    if (pt == null) {
      a.report(
          a.err(Code.LAMBDA_MISMATCH, span, "cannot infer a function type for this " + what)
              .help("declare the variable's type, e.g. 'Function<int, int> f = x => x + 1;'"));
      return null;
    }
    if (pt.isError()) {
      return null;
    }
    if (pt instanceof ClassType ct && a.types.findSam(ct.sym()) != null) {
      return ct;
    }
    a.error(
        Code.LAMBDA_MISMATCH,
        span,
        "a "
            + what
            + " needs a functional interface type, but the expected type is "
            + pt.display());
    return null;
  }

  BExpr lambda(Expr.Lambda l, Type pt) {
    ClassType fi = functionalTarget(pt, l.span(), "lambda");
    if (fi == null) {
      // Still check the body for errors with unknown parameter types.
      if (pt == null) {
        lambdaBody(l, null, null, null);
      }
      return new BExpr.Error(pt == null ? Type.ErrorType.INSTANCE : pt, l.span());
    }
    ClassType plain = a.types.nonWildcard(fi);
    Types.MethodType ft = a.types.functionType(plain);
    MethodSymbol sam = a.types.findSam(fi.sym());
    if (ft.params().size() != l.params().size()) {
      a.error(
          Code.LAMBDA_MISMATCH,
          l.span(),
          "lambda has "
              + l.params().size()
              + " parameter"
              + (l.params().size() == 1 ? "" : "s")
              + " but "
              + fi.display()
              + " expects "
              + ft.params().size());
      return new BExpr.Error(fi, l.span());
    }
    return lambdaBody(l, plain, sam, ft);
  }

  /**
   * Attributes a lambda body against a function type (or with unknown types if {@code ft} null).
   */
  BExpr lambdaBody(Expr.Lambda l, ClassType fi, MethodSymbol sam, Types.MethodType ft) {
    LambdaFrame frame = new LambdaFrame();
    Env outer = a.env;
    Env e = a.env.nested();
    e.scope = new Scope(a.env.scope, Scope.Boundary.LAMBDA, frame, null);
    e.lambda = frame;
    e.isAsync = l.isAsync();
    e.jumps = new ArrayDeque<>();
    e.inConstructor = false;
    e.catchVar = null;
    Type ret = ft == null ? null : ft.ret();
    boolean inferRet = ret != null && ret instanceof Type.TypeVar tv && tv.sym().owner() == null;
    if (l.isAsync()) {
      ret = a.patterns.asyncLambdaResult(ret, l.span());
    }
    e.returnType = ret;
    a.env = e;
    try {
      List<VarSymbol> params = new ArrayList<>();
      for (int i = 0; i < l.params().size(); i++) {
        Param p = l.params().get(i);
        Type pt = ft == null ? Type.ErrorType.INSTANCE : ft.params().get(i);
        if (p.type() != null) {
          Type declared = a.resolveType(p.type());
          if (ft != null
              && !declared.isError()
              && !a.types.isSameType(declared, pt)
              && !a.types.isSubtype(pt, declared)) {
            a.error(
                Code.LAMBDA_MISMATCH,
                p.span(),
                "lambda parameter '"
                    + p.name()
                    + "' is declared "
                    + declared.display()
                    + " but "
                    + pt.display()
                    + " is expected");
          }
          pt = declared.isError() ? pt : declared;
        }
        VarSymbol v = a.env.newVar(p.name(), pt, 0, VarSymbol.Kind.PARAM, p.nameSpan());
        if (!p.name().equals("_")) {
          if (a.env.scope.parent.lookupWithinBoundary(p.name()) != null
              || outer.scope.lookup(p.name()) != null && a.env.scope.lookup(p.name()) != null) {
            Scope.Found prev = outer.scope.lookup(p.name());
            if (prev != null) {
              a.error(
                  Code.DUPLICATE_VARIABLE,
                  p.nameSpan(),
                  "lambda parameter '" + p.name() + "' shadows a local variable");
            }
          }
          a.env.scope.vars.put(p.name(), v);
        }
        a.env.flow.assigned.set(v.id());
        params.add(v);
      }
      BStmt body;
      Type resultType = ret;
      switch (l.body()) {
        case Body.ExprBody eb -> {
          if (ret == PrimType.VOID) {
            BExpr v = a.expr(eb.expr(), null);
            if (!a.stmts.isStatementExpression(eb.expr())
                && !v.type().isError()
                && v.type() != PrimType.VOID) {
              a.error(
                  Code.LAMBDA_MISMATCH,
                  eb.expr().span(),
                  "this lambda must not return a value (" + fi.display() + " returns void)");
            }
            body = new BStmt.ExprStmt(v, eb.expr().span());
          } else if (ret == null || inferRet) {
            BExpr v = a.value(eb.expr(), null);
            frame.returnTypes.add(v.type());
            resultType = v.type();
            body = new BStmt.Return(v, eb.expr().span());
          } else {
            BExpr v = a.exprCoerced(eb.expr(), ret);
            body = new BStmt.Return(v, eb.expr().span());
          }
        }
        case Body.Block bb -> {
          if (inferRet) {
            a.env.returnType = null;
          }
          body = a.stmts.block(bb.block());
          if (a.env.flow.alive && a.env.returnType != null && a.env.returnType != PrimType.VOID) {
            a.error(
                Code.MISSING_RETURN,
                l.span(),
                "lambda must return a value of type "
                    + a.env.returnType.display()
                    + " on every path");
          }
          if (a.env.returnType == null) {
            resultType =
                frame.returnTypes.isEmpty() ? PrimType.VOID : a.types.lub(frame.returnTypes);
          }
        }
      }
      if (fi == null) {
        return new BExpr.Error(Type.ErrorType.INSTANCE, l.span());
      }
      return new BExpr.Lambda(
          fi,
          sam,
          params,
          body,
          resultType,
          new ArrayList<>(frame.captures),
          frame.capturesThis,
          l.span());
    } finally {
      a.env = outer;
      // Captured variables of the lambda are also captured by any enclosing lambdas/classes.
      for (VarSymbol v : frame.captures) {
        Scope.Found f = a.env.scope.lookup(v.name());
        if (f != null && f.var() == v) {
          for (Scope crossed : f.crossed()) {
            if (crossed.boundary == Scope.Boundary.LAMBDA) {
              crossed.lambda.captures.add(v);
            } else if (crossed.boundary == Scope.Boundary.CLASS) {
              a.localClassCaptures
                  .computeIfAbsent(crossed.localClass, k -> new LinkedHashSet<>())
                  .add(v);
            }
          }
        }
      }
      if (frame.capturesThis) {
        a.noteThisUse();
      }
    }
  }

  /** Result type of a lambda attributed against parameter types only (for inference). */
  Type lambdaResultType(Expr.Lambda l, List<Type> paramTypes, ClassType fi) {
    MethodSymbol sam = a.types.findSam(fi.sym());
    Types.MethodType ft = new Types.MethodType(paramTypes, null);
    Attr.Speculation<BExpr> s = a.speculate(() -> lambdaBody(l, fi, sam, ft));
    if (s.hasErrors() || !(s.result() instanceof BExpr.Lambda lam)) {
      return null;
    }
    return lam.returnType();
  }

  // ------------------------------------------------------------------ method references

  BExpr methodRef(Expr.MethodRef m, Type pt) {
    if (m.target() == null && m.typeTarget() == null) {
      BExpr mismatch = functionValueMismatch(m.name(), pt, m.span());
      if (mismatch != null) {
        return mismatch;
      }
    }
    ClassType fi = functionalTarget(pt, m.span(), "method reference");
    if (fi == null) {
      return new BExpr.Error(pt == null ? Type.ErrorType.INSTANCE : pt, m.span());
    }
    return a.calls.methodRef(m, a.types.nonWildcard(fi));
  }

  /**
   * A {@code java.util.function} type for the only function named {@code name} ({@code
   * Function<int, int>}, {@code Predicate<String>}, ...), or null if there is none or several.
   */
  String functionTypeFor(String name) {
    LocalFunctions.Fn local = a.env.scope.lookupFunction(name);
    List<MethodSymbol> ms =
        local != null
            ? List.of(local.sym)
            : a.calls.functionGroup(name) == null
                ? List.of()
                : a.calls.functionGroup(name).methods();
    if (ms.size() != 1 || !ms.getFirst().typeParams().isEmpty()) {
      return null;
    }
    MethodSymbol m = ms.getFirst();
    List<String> ps = m.params().stream().map(p -> p.type().display()).toList();
    Type ret = m.returnType();
    if (ret == null) {
      return null;
    }
    boolean isVoid = ret == PrimType.VOID;
    boolean isBool = ret == PrimType.BOOLEAN;
    String r = ret.display();
    return switch (ps.size()) {
      case 0 -> isVoid ? "Runnable" : "Supplier<" + r + ">";
      case 1 ->
          isVoid
              ? "Consumer<" + ps.get(0) + ">"
              : isBool ? "Predicate<" + ps.get(0) + ">" : "Function<" + ps.get(0) + ", " + r + ">";
      case 2 -> {
        String two = ps.get(0) + ", " + ps.get(1);
        yield isVoid
            ? "BiConsumer<" + two + ">"
            : isBool ? "BiPredicate<" + two + ">" : "BiFunction<" + two + ", " + r + ">";
      }
      default -> null;
    };
  }

  /**
   * The error for a function named without a call (D083) where no function type is expected, or
   * null when {@code pt} is a functional interface.
   */
  BExpr functionValueMismatch(String name, Type pt, Span span) {
    boolean functional =
        pt instanceof ClassType pct && a.types.findSam(pct.sym()) != null
            || pt != null && pt.isError();
    if (!functional) {
      Expr.MethodRef m = new Expr.MethodRef(null, null, name, span, span);
      if (pt == null) {
        String type = functionTypeFor(name);
        a.report(
            a.err(
                    Code.LAMBDA_MISMATCH,
                    m.span(),
                    "cannot infer a function type for '" + m.name() + "'")
                .help(
                    "declare the variable's type ('"
                        + (type != null ? type : "Function<int, int>")
                        + " f = "
                        + m.name()
                        + ";'), or call it: '"
                        + m.name()
                        + "(...)'"));
      } else {
        a.report(
            a.err(
                    Code.LAMBDA_MISMATCH,
                    m.span(),
                    "function '" + m.name() + "' is not a value of type " + pt.display())
                .help("call it: '" + m.name() + "(...)'"));
      }
      return new BExpr.Error(pt == null ? Type.ErrorType.INSTANCE : pt, m.span());
    }
    return null;
  }
}

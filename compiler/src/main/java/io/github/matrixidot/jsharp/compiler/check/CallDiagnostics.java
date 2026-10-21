package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Types;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explains failed calls: ambiguous overloads, and why no candidate applies (wrong argument count,
 * which argument does not convert and why, unmet bounds, lambdas that do not fit), with the
 * candidates as notes.
 */
final class CallDiagnostics {
  private final Attr a;

  CallDiagnostics(Attr a) {
    this.a = a;
  }

  void reportAmbiguous(List<Calls.Selected> app, Span span, String what) {
    Diagnostic.Builder d = a.err(Code.AMBIGUOUS_CALL, span, "call to '" + what + "' is ambiguous");
    for (Calls.Selected s : app.subList(0, Math.min(app.size(), 4))) {
      d.note("candidate: " + s.method.signature());
    }
    d.help("add a cast to an argument to pick one overload");
    a.report(d);
  }

  void reportNotApplicable(
      List<MethodSymbol> cands,
      Type site,
      List<Calls.ArgInfo> args,
      List<Type> explicit,
      Type expected,
      Span span,
      String what,
      boolean isExtension) {
    if (args.stream().anyMatch(x -> !x.deferred && x.type.isError())) {
      return; // an argument already has an error
    }
    if (cands.size() != 1) {
      boolean reported = false;
      for (Calls.ArgInfo x : args) {
        if (x.polyCall && x.type == null) {
          a.value(x.expr, null); // its own errors are the real problem
          reported = true;
        }
      }
      if (reported) {
        return;
      }
    }
    if (cands.size() == 1) {
      explainSingle(cands.getFirst(), site, args, explicit, span, isExtension);
      return;
    }
    StringBuilder argDesc = new StringBuilder("(");
    for (int i = isExtension ? 1 : 0; i < args.size(); i++) {
      if (argDesc.length() > 1) {
        argDesc.append(", ");
      }
      Calls.ArgInfo x = args.get(i);
      argDesc.append(x.deferred ? describeDeferred(x.expr) : x.type.display());
    }
    argDesc.append(')');
    Diagnostic.Builder d =
        a.err(
            Code.NO_APPLICABLE_METHOD,
            span,
            (what.contains("constructor of ")
                    ? "no " + what.replaceFirst("^(this |super )", "")
                    : "no overload of '" + what + "'")
                + " accepts arguments "
                + argDesc);
    int shown = 0;
    for (MethodSymbol m : cands) {
      if (shown++ < 5) {
        d.note("candidate: " + m.signature());
      }
    }
    a.report(d);
  }

  static String describeDeferred(Expr e) {
    return switch (Calls.unwrap(e)) {
      case Expr.Lambda l -> "lambda";
      case Expr.MethodRef m -> "method reference";
      case Expr.New n -> "new()";
      default -> "expression";
    };
  }

  /** Explains precisely why the only candidate does not apply. */
  void explainSingle(
      MethodSymbol m,
      Type site,
      List<Calls.ArgInfo> args,
      List<Type> explicit,
      Span span,
      boolean isExtension) {
    List<MethodSymbol.Param> params = m.params();
    int offset = isExtension ? 1 : 0;
    long positional = args.stream().filter(x -> x.name == null).count();
    boolean hasNamed = args.stream().anyMatch(x -> x.name != null);
    // Simulate the argument-to-parameter mapping to explain mapping errors precisely.
    boolean[] filled = new boolean[params.size()];
    int next = 0;
    boolean sawNamed = false;
    for (Calls.ArgInfo x : args) {
      if (x.name != null) {
        sawNamed = true;
        int idx = -1;
        for (int p = 0; p < params.size(); p++) {
          if (params.get(p).name().equals(x.name)) {
            idx = p;
          }
        }
        if (idx < 0) {
          a.report(
              a.err(
                      Code.INVALID_NAMED_ARGUMENT,
                      x.span,
                      m.signature() + " has no parameter named '" + x.name + "'")
                  .help(
                      "parameters: "
                          + String.join(
                              ", ",
                              params.subList(offset, params.size()).stream()
                                  .map(MethodSymbol.Param::name)
                                  .toList())));
          return;
        }
        if (filled[idx]) {
          a.error(
              Code.INVALID_NAMED_ARGUMENT,
              x.span,
              "parameter '" + x.name + "' is given more than once");
          return;
        }
        filled[idx] = true;
        continue;
      }
      if (sawNamed) {
        a.error(
            Code.INVALID_NAMED_ARGUMENT,
            x.span,
            "positional arguments must come before named arguments");
        return;
      }
      if (next >= params.size()) {
        if (m.isVarargs()) {
          continue;
        }
        a.report(
            a.err(
                Code.ARGUMENT_COUNT,
                span,
                (m.isConstructor() ? "constructor " : "")
                    + m.signature()
                    + " takes "
                    + (params.size() - offset)
                    + " argument"
                    + (params.size() - offset == 1 ? "" : "s")
                    + ", but "
                    + (positional - offset)
                    + " "
                    + (positional - offset == 1 ? "was" : "were")
                    + " given"));
        return;
      }
      filled[next++] = true;
    }
    for (int p = 0; p < params.size(); p++) {
      MethodSymbol.Param prm = params.get(p);
      if (!filled[p] && !prm.hasDefault() && !prm.isVarargs()) {
        a.report(
            a.err(
                    Code.ARGUMENT_COUNT,
                    span,
                    "missing argument for parameter '" + prm.name() + "' of " + m.signature())
                .help(hasNamed ? "pass it by position or as '" + prm.name() + ": value'" : null));
        return;
      }
    }
    if (explicit != null && explicit.size() == m.typeParams().size()) {
      String v = a.calls.violatedBound(m, Types.zip(m.typeParams(), explicit));
      if (v != null) {
        a.error(Code.TYPE_ARGUMENT_BOUND, span, v);
        return;
      }
    }
    if (explicit != null && explicit.size() != m.typeParams().size()) {
      a.error(
          Code.WRONG_TYPE_ARG_COUNT,
          span,
          m.signature()
              + " takes "
              + m.typeParams().size()
              + (m.typeParams().size() == 1 ? " type argument" : " type arguments")
              + ", but "
              + explicit.size()
              + (explicit.size() == 1 ? " was" : " were")
              + " given");
      return;
    }
    // Find the first mismatching argument (after a best-effort inference).
    Map<TypeVarSymbol, Type> classSubst = a.calls.siteSubst(site, m);
    Map<TypeVarSymbol, Type> sol = new IdentityHashMap<>();
    if (explicit != null) {
      sol.putAll(Types.zip(m.typeParams(), explicit));
    } else if (!m.typeParams().isEmpty()) {
      Infer inf = new Infer(a.types, m.typeParams());
      for (int i = 0; i < Math.min(args.size(), params.size()); i++) {
        Calls.ArgInfo x = args.get(i);
        if (!x.deferred && x.name == null) {
          inf.subtype(
              a.types.boxIfPrimitive(x.type), Types.subst(params.get(i).type(), classSubst));
        }
      }
      sol.putAll(inf.solve(true));
    }
    for (int i = 0; i < args.size(); i++) {
      Calls.ArgInfo x = args.get(i);
      int pi = -1;
      if (x.name != null) {
        for (int p = 0; p < params.size(); p++) {
          if (params.get(p).name().equals(x.name)) {
            pi = p;
          }
        }
      } else {
        pi = Math.min(i, params.size() - 1);
      }
      if (pi < 0) {
        continue;
      }
      Type pt = Types.subst(Types.subst(params.get(pi).type(), classSubst), sol);
      if (m.isVarargs()
          && pi == params.size() - 1
          && pt instanceof Type.ArrayType at
          && (x.deferred || !(x.type instanceof Type.ArrayType))) {
        pt = at.elem();
      }
      if (x.deferred) {
        if (!a.calls.potentiallyCompatible(x.expr, pt, null)) {
          a.report(
              a.err(
                  Code.LAMBDA_MISMATCH,
                  x.span,
                  describeDeferred(x.expr)
                      + " is not compatible with parameter type "
                      + pt.display()));
          return;
        }
        // Report the lambda's own errors against the expected type.
        a.coerce(a.expr(x.expr, pt), pt, x.span);
        return;
      }
      if (a.types.assignConversion(x.type, pt, Attr.intConstant(x.bound)) == Types.Conv.NONE) {
        String which = i - offset < 0 ? "receiver" : "argument " + (i - offset + 1);
        Diagnostic.Builder d =
            a.err(
                Code.TYPE_MISMATCH,
                x.span,
                which
                    + " of "
                    + m.signature()
                    + ": expected "
                    + pt.display()
                    + ", found "
                    + x.type.display());
        if (x.expr != null && Calls.isRetargetable(x.expr)) {
          final Type target = pt;
          Attr.Speculation<BExpr> s = a.speculate(() -> a.exprCoerced(x.expr, target));
          if (!s.hasErrors()) {
            continue;
          }
        }
        a.report(d);
        return;
      }
    }
    a.report(
        a.err(
            Code.CANNOT_INFER,
            span,
            "cannot infer type arguments for " + m.signature() + " from these arguments"));
  }
}

package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.ast.Body;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Descriptors;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import io.github.matrixidot.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Overload resolution (JLS 15.12.2): which candidates apply in the strict, loose and varargs
 * phases, with generic inference from arguments and the expected type, deferred lambdas and method
 * references, default and named arguments; then the most specific candidate.
 */
final class Overloads {
  private final Attr a;

  Overloads(Attr a) {
    this.a = a;
  }

  enum Phase {
    STRICT,
    LOOSE,
    VARARGS
  }

  /** A selected method with its argument mapping and inferred type arguments. */
  static final class Selected {
    MethodSymbol method;
    Phase phase;
    Map<TypeVarSymbol, Type> solution;

    /** For each parameter: the argument indices mapped to it (varargs may have several). */
    List<List<Integer>> mapping;

    List<Type> paramTypes;
    int defaultsUsed;
    Set<TypeVarSymbol> defaultedVars = Set.of();
  }

  /**
   * Selects the most specific applicable method, or reports why none applies (when {@code report})
   * and returns null.
   *
   * @param extReceiver non-null when candidates are extension methods (argument 0 is the receiver)
   */
  Selected select(
      List<MethodSymbol> candidates,
      Type site,
      List<Calls.ArgInfo> args,
      List<Type> explicit,
      Type expected,
      BExpr extReceiver,
      Span span,
      String what,
      boolean report) {
    List<MethodSymbol> accessible = new ArrayList<>();
    MethodSymbol inaccessible = null;
    ClassSymbol from = a.calls.accessFrom != null ? a.calls.accessFrom : a.env.cls;
    for (MethodSymbol m : candidates) {
      if (a.lookup.isAccessible(m, m.owner(), m.isStatic() ? null : site, from)) {
        accessible.add(m);
      } else {
        inaccessible = m;
      }
    }
    for (Phase phase : Phase.values()) {
      List<Selected> app = new ArrayList<>();
      for (MethodSymbol m : accessible) {
        Selected s = tryApply(m, site, args, explicit, expected, phase);
        if (s != null) {
          app.add(s);
        }
      }
      if (!app.isEmpty()) {
        Selected best = mostSpecific(app, args);
        if (best == null
            && args.stream().anyMatch(x -> !x.deferred && x.type != null && x.type.isError())) {
          return app.getFirst();
        }
        if (best == null) {
          if (report) {
            a.callDiagnostics.reportAmbiguous(app, span, what);
          }
          return report ? app.getFirst() : null;
        }
        return best;
      }
    }
    if (report) {
      for (MethodSymbol m : candidates) {
        if (!accessible.contains(m)
            && tryApply(
                    m,
                    site,
                    args,
                    explicit,
                    expected,
                    Phase.VARARGS.compareTo(Phase.LOOSE) > 0 && m.isVarargs()
                        ? Phase.VARARGS
                        : Phase.LOOSE)
                != null) {
          a.error(
              Code.INACCESSIBLE_MEMBER,
              span,
              m.kindName() + " " + m.signature() + " is " + Flags.accessOf(m));
          return null;
        }
      }
      if (accessible.isEmpty() && inaccessible != null) {
        a.error(
            Code.INACCESSIBLE_MEMBER,
            span,
            inaccessible.kindName()
                + " "
                + inaccessible.signature()
                + " is "
                + Flags.accessOf(inaccessible));
      } else {
        int before = a.reported;
        a.callDiagnostics.reportNotApplicable(
            accessible, site, args, explicit, expected, span, what, extReceiver != null);
        boolean argErrors =
            args.stream().anyMatch(x -> !x.deferred && x.type != null && x.type.isError());
        if (a.reported == before && !argErrors) {
          // Every rejection must be explained; fall back to a generic message.
          a.report(
              a.err(
                  Code.NO_APPLICABLE_METHOD,
                  span,
                  "no applicable overload of '"
                      + what
                      + "' for these arguments"
                      + (expected != null && expected.isReference()
                          ? " and expected type " + expected.display()
                          : "")));
        }
      }
    }
    return null;
  }

  Selected tryApply(
      MethodSymbol m,
      Type site,
      List<Calls.ArgInfo> args,
      List<Type> explicit,
      Type expected,
      Phase phase) {
    List<MethodSymbol.Param> params = m.params();
    int n = params.size();
    boolean varargs = phase == Phase.VARARGS && m.isVarargs();
    if (phase == Phase.VARARGS && !m.isVarargs()) {
      return null;
    }
    // ---- map arguments to parameters
    List<List<Integer>> mapping = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      mapping.add(new ArrayList<>());
    }
    int pos = 0;
    boolean sawNamed = false;
    for (int i = 0; i < args.size(); i++) {
      Calls.ArgInfo arg = args.get(i);
      if (arg.name != null) {
        sawNamed = true;
        int idx = -1;
        for (int p = 0; p < n; p++) {
          if (params.get(p).name().equals(arg.name)) {
            idx = p;
          }
        }
        if (idx < 0 || !mapping.get(idx).isEmpty()) {
          return null;
        }
        mapping.get(idx).add(i);
        continue;
      }
      if (sawNamed) {
        return null; // positional after named
      }
      if (varargs && pos >= n - 1) {
        mapping.get(n - 1).add(i);
        continue;
      }
      if (pos >= n) {
        return null;
      }
      mapping.get(pos++).add(i);
    }
    int defaultsUsed = 0;
    for (int p = 0; p < n; p++) {
      if (mapping.get(p).isEmpty()) {
        if (varargs && p == n - 1) {
          continue;
        }
        if (params.get(p).hasDefault()) {
          defaultsUsed++;
          continue;
        }
        return null;
      }
    }
    // ---- instantiate parameter types
    Map<TypeVarSymbol, Type> classSubst = siteSubst(site, m);
    List<TypeVarSymbol> vars = new ArrayList<>(m.typeParams());
    vars.addAll(a.calls.extraInferenceVars);
    Map<TypeVarSymbol, Type> explicitMap = null;
    if (explicit != null) {
      if (explicit.size() != m.typeParams().size()) {
        return null;
      }
      explicitMap = Types.zip(m.typeParams(), explicit);
      vars = new ArrayList<>(a.calls.extraInferenceVars);
      Map<TypeVarSymbol, Type> both = new HashMap<>(classSubst);
      both.putAll(explicitMap);
      if (violatedBound(m, both) != null) {
        return null;
      }
    }
    List<Type> ptypes = new ArrayList<>();
    for (MethodSymbol.Param p : params) {
      Type t = Types.subst(p.type() == null ? Type.ErrorType.INSTANCE : p.type(), classSubst);
      if (explicitMap != null) {
        t = Types.subst(t, explicitMap);
      }
      ptypes.add(t);
    }
    Infer inf =
        vars.isEmpty() ? null : new Infer(a.calls.types(), vars).withBoundSubstitution(classSubst);
    Set<TypeVarSymbol> defaulted = new HashSet<>();
    // ---- check / constrain arguments
    for (int p = 0; p < n; p++) {
      for (int idx : mapping.get(p)) {
        Calls.ArgInfo arg = args.get(idx);
        Type pt = ptypes.get(p);
        if (varargs && p == n - 1) {
          pt = pt instanceof Type.ArrayType at ? at.elem() : pt;
        }
        if (arg.deferred) {
          if (!potentiallyCompatible(arg.expr, pt, inf)) {
            return null;
          }
          if (arg.polyCall && (inf == null || !inf.mentionsVars(pt))) {
            final Type target = pt;
            if (a.speculate(() -> a.exprCoerced(arg.expr, target)).hasErrors()) {
              return null;
            }
          }
          continue;
        }
        Type at = arg.type;
        if (at.isError()) {
          continue;
        }
        if (inf != null && inf.mentionsVars(pt)) {
          if (phase == Phase.STRICT && at instanceof PrimType && inf.isVar(pt)) {
            return null;
          }
          if (at instanceof Type.NullType && inf.isVar(pt)) {
            inf.subtype(at, pt);
            continue;
          }
          Type captured = arg.captured(a.calls.types());
          inf.subtype(
              phase == Phase.STRICT ? captured : a.calls.types().boxIfPrimitive(captured), pt);
          if (inf.failed) {
            return null;
          }
          continue;
        }
        if (!compatible(arg, pt, phase)) {
          return null;
        }
      }
    }
    Map<TypeVarSymbol, Type> solution =
        explicitMap != null ? new IdentityHashMap<>(explicitMap) : new IdentityHashMap<>();
    if (inf != null) {
      Type ret =
          Types.subst(
              m.returnType() == null ? Type.ErrorType.INSTANCE : m.returnType(), classSubst);
      if (explicitMap != null) {
        ret = Types.subst(ret, explicitMap);
      }
      Type exp = a.calls.extraExpected != null ? a.calls.extraExpected : expected;
      if (!a.calls.extraInferenceVars.isEmpty() && exp != null && site instanceof ClassType) {
        inf.subtype(site, exp);
      } else if (exp != null
          && exp.isReference()
          && !exp.isError()
          && inf.mentionsVars(ret)
          && !(exp instanceof Type.TypeVar etv && etv.sym().isCaptured())) {
        inf.subtype(ret, exp.withNullness(Nullness.NON_NULL));
      }
      // Lambdas: infer from their bodies once their parameter types are known.
      Done polyDone = new Done();
      // Poly call arguments wait until their target type is resolved by other arguments
      // (comparing(String::length, reverseOrder())); only when no round makes progress are they
      // typed against a target with wildcards for the unresolved variables.
      boolean eager = false;
      for (int round = 0; round < 5; round++) {
        Map<TypeVarSymbol, Type> partial = inf.solve(false);
        boolean added = false;
        for (int p = 0; p < n; p++) {
          for (int idx : mapping.get(p)) {
            Calls.ArgInfo arg = args.get(idx);
            if (!arg.deferred) {
              continue;
            }
            Type pt = ptypes.get(p);
            if (varargs && p == n - 1 && pt instanceof Type.ArrayType at) {
              pt = at.elem();
            }
            added |= constrainDeferred(arg.expr, pt, inf, partial, polyDone, eager);
          }
        }
        if (!added) {
          if (eager) {
            break;
          }
          eager = true;
        }
      }
      Map<TypeVarSymbol, Type> solvedOnly = inf.solve(false);
      Map<TypeVarSymbol, Type> sol = inf.solve(true);
      if (!inf.check(sol)) {
        return null;
      }
      for (TypeVarSymbol v : vars) {
        if (!solvedOnly.containsKey(v)) {
          defaulted.add(v);
        }
      }
      solution.putAll(sol);
      List<Type> inst = new ArrayList<>();
      for (Type t : ptypes) {
        inst.add(Types.subst(t, sol));
      }
      ptypes = inst;
      // Re-check standalone arguments against the instantiated types.
      for (int p = 0; p < n; p++) {
        for (int idx : mapping.get(p)) {
          Calls.ArgInfo arg = args.get(idx);
          if (arg.deferred || arg.type.isError()) {
            continue;
          }
          Type pt = ptypes.get(p);
          if (varargs && p == n - 1 && pt instanceof Type.ArrayType at) {
            pt = at.elem();
          }
          if (!compatible(arg, pt, phase == Phase.STRICT ? Phase.LOOSE : phase)) {
            return null;
          }
        }
      }
    }
    // Lambdas with fully known types must type-check against the parameter (overload pruning).
    for (int p = 0; p < n; p++) {
      for (int idx : mapping.get(p)) {
        Calls.ArgInfo arg = args.get(idx);
        if (arg.deferred && unwrap(arg.expr) instanceof Expr.Lambda lam) {
          Type pt = ptypes.get(p);
          if (varargs && p == n - 1 && pt instanceof Type.ArrayType at) {
            pt = at.elem();
          }
          if (pt instanceof ClassType ct && a.calls.types().findSam(ct.sym()) != null) {
            ClassType fi = a.calls.types().nonWildcard(ct);
            Types.MethodType ft = a.calls.types().functionType(fi);
            if (ft != null && ft.params().size() == lam.params().size()) {
              final ClassType fiF = fi;
              Attr.Speculation<BExpr> s =
                  a.speculate(
                      () -> a.lambdas.lambdaBody(lam, fiF, a.calls.types().findSam(fiF.sym()), ft));
              if (s.hasErrors()) {
                return null;
              }
            }
          }
        }
      }
    }
    Selected s = new Selected();
    s.method = m;
    s.phase = phase;
    s.solution = solution;
    s.mapping = mapping;
    s.paramTypes = ptypes;
    s.defaultsUsed = defaultsUsed;
    s.defaultedVars = defaulted;
    return s;
  }

  /** A message if explicit type arguments violate the method's declared bounds, else null. */
  String violatedBound(MethodSymbol m, Map<TypeVarSymbol, Type> subst) {
    for (TypeVarSymbol tv : m.typeParams()) {
      Type arg = subst.get(tv);
      for (Type b : tv.bounds()) {
        Type bound = Types.subst(b, subst);
        if (arg != null && !arg.isError() && !a.calls.types().isSubtype(arg, bound)) {
          return "type argument "
              + arg.display()
              + " does not satisfy the bound "
              + bound.display()
              + " of "
              + tv.name();
        }
      }
    }
    return null;
  }

  static Expr unwrap(Expr e) {
    return e instanceof Expr.Paren p ? unwrap(p.expr()) : e;
  }

  /** Substitution of the declaring class's type parameters as seen from {@code site}. */
  Map<TypeVarSymbol, Type> siteSubst(Type site, MethodSymbol m) {
    if (site == null) {
      return Map.of();
    }
    ClassType as = a.calls.types().asSuper(site, m.owner());
    if (as == null) {
      return Map.of();
    }
    if (as.isRaw()) {
      Map<TypeVarSymbol, Type> erased = new IdentityHashMap<>();
      for (TypeVarSymbol tv : m.owner().typeParams()) {
        erased.put(tv, tv.erasedBound().withNullness(Nullness.PLATFORM));
      }
      return erased;
    }
    return a.calls.types().typeArgMap(as);
  }

  boolean compatible(Calls.ArgInfo arg, Type pt, Phase phase) {
    Types.Conv c =
        a.calls
            .types()
            .assignConversion(arg.captured(a.calls.types()), pt, Attr.intConstant(arg.bound));
    boolean ok =
        switch (c) {
          case NONE -> false;
          // Constant narrowing (f(byte) with f(10)) ranks with boxing so that an exact or widening
          // match always wins: println(3) must not pick println(char).
          case BOX, UNBOX, NARROW_CONSTANT -> phase != Phase.STRICT;
          default -> true;
        };
    if (!ok
        && arg.expr != null
        && Calls.isRetargetable(arg.expr)
        && !pt.isError()
        && !(pt instanceof Type.TypeVar)) {
      final Type target = pt;
      Attr.Speculation<BExpr> s = a.speculate(() -> a.exprCoerced(arg.expr, target));
      return !s.hasErrors() && s.result() != null && !s.result().type().isError();
    }
    return ok;
  }

  boolean potentiallyCompatible(Expr e, Type pt, Infer inf) {
    Expr u = unwrap(e);
    if (pt.isError()) {
      return true;
    }
    if (Calls.isPolyCandidate(u) && !Calls.isDeferred(u)) {
      return true;
    }
    return switch (u) {
      case Expr.Lambda l -> {
        if (inf != null && inf.isVar(pt)) {
          yield true; // JLS 15.12.2.1: a type parameter of the candidate accepts any lambda
        }
        if (!(pt instanceof ClassType ct) || a.calls.types().findSam(ct.sym()) == null) {
          yield false;
        }
        Types.MethodType ft = a.calls.types().functionType(a.calls.types().nonWildcard(ct));
        yield ft != null && ft.params().size() == l.params().size();
      }
      case Expr.MethodRef mr -> {
        if (inf != null && inf.isVar(pt)) {
          yield true;
        }
        if (!(pt instanceof ClassType ct) || a.calls.types().findSam(ct.sym()) == null) {
          yield false;
        }
        Types.MethodType ft = a.calls.types().functionType(a.calls.types().nonWildcard(ct));
        yield ft == null || methodRefArityFits(mr, ft.params().size());
      }
      case Expr.New nw -> pt instanceof ClassType;
      case Expr.ArrayInit ai -> pt instanceof Type.ArrayType;
      case Expr.CollectionLiteral cl ->
          pt instanceof Type.ArrayType
              || inf != null && inf.isVar(pt)
              || pt instanceof ClassType ct && literalTarget(ct, false);
      case Expr.MapLiteral ml ->
          inf != null && inf.isVar(pt) || pt instanceof ClassType ct && literalTarget(ct, true);
      default -> true;
    };
  }

  /**
   * JLS 15.12.2.1: some method the reference may denote takes {@code n} arguments ({@code n - 1}
   * plus the receiver for {@code Type::instanceMethod}). True when the qualifier cannot be resolved
   * (errors are reported later).
   */
  boolean methodRefArityFits(Expr.MethodRef mr, int n) {
    if (mr.name().equals("new")) {
      if (mr.typeTarget() != null && mr.target() == null) {
        return true; // array constructor (int[]::new) or a parameterized type
      }
      Attr.Speculation<Boolean> s =
          a.speculate(
              () -> {
                Attr.Target t = a.target(mr.target(), true);
                if (!(t instanceof Attr.TypeTarget tt) || !(tt.type() instanceof ClassType c)) {
                  return true;
                }
                return c.sym().methods(MethodSymbol.CONSTRUCTOR).stream()
                    .anyMatch(m -> arityAccepts(m, n));
              });
      return s.result() == null || s.result();
    }
    if (mr.target() == null && mr.typeTarget() == null) {
      Calls.FunctionGroup g = a.calls.functionGroup(mr.name());
      return g == null || g.methods().stream().anyMatch(m -> arityAccepts(m, n));
    }
    if (mr.target() == null) {
      return true;
    }
    Attr.Speculation<Boolean> s =
        a.speculate(
            () -> {
              Attr.Target t = a.target(mr.target(), true);
              if (t instanceof Attr.TypeTarget tt) {
                return a.lookup.findMethods(tt.type(), mr.name()).stream()
                    .anyMatch(
                        m -> m.isStatic() ? arityAccepts(m, n) : n > 0 && arityAccepts(m, n - 1));
              }
              if (t instanceof Attr.ValueTarget vt && !vt.expr().type().isError()) {
                return a.lookup.findMethods(vt.expr().type(), mr.name()).stream()
                    .anyMatch(m -> arityAccepts(m, n));
              }
              return true;
            });
    return s.result() == null || s.result();
  }

  static boolean arityAccepts(MethodSymbol m, int n) {
    int size = m.params().size();
    int required = (int) m.params().stream().filter(p -> !p.hasDefault()).count();
    if (m.isVarargs()) {
      return n >= size - 1;
    }
    return n >= required && n <= size;
  }

  /**
   * {@code TreeSet::new} for a function type returning an inference variable {@code C extends
   * Collection<T>}: the class's type arguments follow from the bound ({@code C = TreeSet<T>}), the
   * way javac infers a diamond constructor reference.
   */
  boolean constrainGenericCtorRef(Expr.MethodRef mr, Types.MethodType ft, Infer inf) {
    if (!mr.name().equals("new") || mr.target() == null || !inf.isVar(ft.ret())) {
      return false;
    }
    Attr.Speculation<Type> s =
        a.speculate(
            () -> a.target(mr.target(), true) instanceof Attr.TypeTarget tt ? tt.type() : null);
    if (s.hasErrors()
        || !(s.result() instanceof ClassType ct)
        || ct.sym().typeParams().isEmpty()
        || !ct.args().isEmpty()) {
      return false;
    }
    TypeVarSymbol rv = ((Type.TypeVar) ft.ret()).sym();
    for (Type bound : rv.bounds()) {
      if (!(bound instanceof ClassType bc) || bc.sym() == a.syms.objectSym()) {
        continue;
      }
      ClassType generic = ct.sym().thisType();
      ClassType sup = a.calls.types().asSuper(generic, bc.sym());
      if (sup == null) {
        continue;
      }
      Infer local = new Infer(a.calls.types(), ct.sym().typeParams());
      local.eq(sup, bc);
      Map<TypeVarSymbol, Type> sol = local.solve(false);
      if (sol.size() == ct.sym().typeParams().size()) {
        inf.eq(Types.subst(generic, sol).withNullness(Nullness.NON_NULL), ft.ret());
        return true;
      }
    }
    return false;
  }

  /** {@code TreeSet::new}: a constructor reference of a generic class without type arguments. */
  boolean isDiamondCtorRef(Expr.MethodRef mr) {
    if (!mr.name().equals("new") || mr.target() == null) {
      return false;
    }
    Attr.Speculation<Type> s =
        a.speculate(
            () -> a.target(mr.target(), true) instanceof Attr.TypeTarget tt ? tt.type() : null);
    return !s.hasErrors()
        && s.result() instanceof ClassType ct
        && !ct.sym().typeParams().isEmpty()
        && ct.args().isEmpty();
  }

  /** A method reference that denotes exactly one method (JLS 15.13.1 "exact"). */
  record ExactRef(MethodSymbol method, Type qualifier, boolean unbound) {}

  ExactRef exactMethodRef(Expr.MethodRef mr) {
    if (mr.target() == null && mr.typeTarget() == null) {
      Calls.FunctionGroup g = a.calls.functionGroup(mr.name());
      if (g == null || g.methods().size() != 1) {
        return null;
      }
      MethodSymbol m = g.methods().getFirst();
      if (m.isVarargs() || !m.typeParams().isEmpty() || m.returnType() == null) {
        return null;
      }
      return new ExactRef(
          m, g.owner() == null ? m.owner().thisType() : g.owner().thisType(), false);
    }
    if (mr.name().equals("new") || mr.target() == null) {
      return null;
    }
    Attr.Speculation<ExactRef> s =
        a.speculate(
            () -> {
              Attr.Target t = a.target(mr.target(), true);
              Type q;
              boolean isType;
              if (t instanceof Attr.TypeTarget tt) {
                q = tt.type();
                isType = true;
              } else if (t instanceof Attr.ValueTarget vt) {
                q = vt.expr().type();
                isType = false;
              } else {
                return null;
              }
              if (q == null || q.isError()) {
                return null;
              }
              List<MethodSymbol> ms = a.lookup.findMethods(q, mr.name());
              if (ms.size() != 1) {
                return null;
              }
              MethodSymbol m = ms.getFirst();
              if (m.isVarargs() || !m.typeParams().isEmpty() || m.returnType() == null) {
                return null;
              }
              return new ExactRef(m, q, isType && !m.isStatic());
            });
    return s.hasErrors() ? null : s.result();
  }

  /**
   * Constraints from an exact method reference (JLS 18.2.1): the function type's parameters must be
   * accepted by the method (the first one is the receiver for {@code Type::instanceMethod}) and the
   * method's result must be compatible with the function type's return.
   */
  boolean constrainExactRef(
      Expr.MethodRef mr, Types.MethodType ft, Infer inf, Map<TypeVarSymbol, Type> partial) {
    ExactRef ex = exactMethodRef(mr);
    if (ex == null) {
      return false;
    }
    MethodSymbol m = ex.method();
    int off = ex.unbound() ? 1 : 0;
    if (ft.params().size() != m.params().size() + off) {
      return false;
    }
    Map<TypeVarSymbol, Type> ownerArgs = Map.of();
    if (!m.owner().typeParams().isEmpty()) {
      ClassType sup =
          ex.qualifier() instanceof ClassType qc ? a.calls.types().asSuper(qc, m.owner()) : null;
      if (sup == null
          || sup.args().size() != m.owner().typeParams().size()
          || sup.args().stream().anyMatch(x -> x instanceof Type.WildcardType)) {
        return false; // raw or wildcard qualifier: no reliable member types
      }
      ownerArgs = a.calls.types().typeArgMap(sup);
    }
    int before = boundsCount(inf);
    if (ex.unbound()) {
      inf.subtype(Types.subst(ft.params().getFirst(), partial), ex.qualifier());
    }
    for (int i = 0; i < m.params().size(); i++) {
      Type pm = Types.subst(m.params().get(i).type(), ownerArgs);
      inf.subtype(Types.subst(ft.params().get(i + off), partial), pm);
    }
    if (ft.ret() != PrimType.VOID && m.returnType() != PrimType.VOID) {
      Type r = Types.subst(m.returnType(), ownerArgs);
      inf.subtype(a.calls.types().boxIfPrimitive(r), Types.subst(ft.ret(), partial));
    }
    return boundsCount(inf) > before;
  }

  /**
   * Can a collection literal (or, with {@code map}, a map literal) be typed as {@code ct}: Object,
   * Iterable/Collection/List/Set (or Map), or a concrete class implementing them.
   */
  boolean literalTarget(ClassType ct, boolean map) {
    String n = ct.sym().binaryName();
    if (n.equals("java/lang/Object")) {
      return true;
    }
    var base = a.syms.lookup(map ? "java/util/Map" : "java/lang/Iterable");
    return base != null && a.calls.types().isSubtype(ct.erasure(), base.thisType().erasure());
  }

  /**
   * For a deferred lambda/method-ref argument whose parameter types are known, attributes it
   * speculatively and constrains the function type's return type. Returns true if new bounds were
   * added.
   */
  boolean constrainDeferred(
      Expr e, Type pt, Infer inf, Map<TypeVarSymbol, Type> partial, Done polyDone, boolean eager) {
    Expr u = unwrap(e);
    if ((u instanceof Expr.CollectionLiteral || u instanceof Expr.MapLiteral)
        && inf.mentionsVars(pt)
        && polyDone.result.add(u)) {
      // A literal's own type (List<E> / Map<K, V>) constrains the parameter: f<T>(List<T>) with
      // [1].
      Attr.Speculation<BExpr> s = a.speculate(() -> a.value(u, null));
      if (!s.hasErrors() && s.result() != null && !s.result().type().isError()) {
        int before = boundsCount(inf);
        inf.subtype(s.result().type(), pt);
        return boundsCount(inf) > before;
      }
      return false;
    }
    if (Calls.isPolyCandidate(u) && !Calls.isDeferred(u)) {
      if (!eager && inf.mentionsVars(Types.subst(pt, partial)) && !inf.isVar(pt)) {
        return false; // wait for the other arguments to resolve the target
      }
      return polyDone.poly.add(u) && constrainPolyCall(u, pt, inf, partial);
    }
    if (!(pt instanceof ClassType ct) || a.calls.types().findSam(ct.sym()) == null) {
      return false;
    }
    ClassType fi = a.calls.types().nonWildcard(ct);
    Types.MethodType ft = a.calls.types().functionType(fi);
    boolean explicitAdded = false;
    if (ft != null
        && u instanceof Expr.Lambda lam
        && lam.params().size() == ft.params().size()
        && !lam.params().isEmpty()
        && lam.params().stream().allMatch(p -> p.type() != null)
        && polyDone.explicit.add(u)) {
      // Explicitly typed lambda parameters fix the function type's parameters (as in Java).
      for (int i = 0; i < ft.params().size(); i++) {
        Type fp = Types.subst(ft.params().get(i), partial);
        if (!inf.mentionsVars(fp)) {
          continue;
        }
        var typeNode = lam.params().get(i).type();
        Attr.Speculation<Type> d = a.speculate(() -> a.resolveType(typeNode));
        if (!d.hasErrors() && d.result() != null && !d.result().isError()) {
          inf.eq(d.result(), fp);
          explicitAdded = true;
        }
      }
    }
    if (ft != null && u instanceof Expr.MethodRef emr && polyDone.exactRef.add(u)) {
      explicitAdded |= constrainExactRef(emr, ft, inf, partial);
      explicitAdded |= constrainGenericCtorRef(emr, ft, inf);
    }
    if (ft == null || !inf.mentionsVars(ft.ret())) {
      return explicitAdded;
    }
    List<Type> ps = new ArrayList<>();
    for (Type p : ft.params()) {
      Type s = Types.subst(p, partial);
      if (inf.mentionsVars(s)) {
        return explicitAdded;
      }
      ps.add(s);
    }
    Type result = null;
    if (u instanceof Expr.Lambda lam && ps.size() == lam.params().size()) {
      result = a.lambdas.lambdaResultType(lam, ps, (ClassType) Types.subst(fi, partial));
      if (result != null && lam.isAsync()) {
        result = a.patterns.asyncResultForInference(result);
      }
    } else if (u instanceof Expr.MethodRef mr) {
      if (isDiamondCtorRef(mr)) {
        // Its standalone type has defaulted arguments (TreeSet<Object>); the bound-based
        // constraint from constrainGenericCtorRef or the final target typing decides instead.
        return explicitAdded;
      }
      result = a.calls.methodRefResultType(mr, ps);
    }
    if (result == null || result.isError() || result == PrimType.VOID) {
      return explicitAdded;
    }
    // Once per argument: re-adding the same bound every round would look like progress forever.
    if (!polyDone.result.add(u)) {
      return explicitAdded;
    }
    int before = boundsCount(inf);
    inf.subtype(a.calls.types().boxIfPrimitive(result), ft.ret());
    return explicitAdded || boundsCount(inf) > before;
  }

  /** Which deferred-argument constraints were already added during one inference. */
  private static final class Done {
    final Set<Expr> poly = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<Expr> explicit = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<Expr> exactRef = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<Expr> result = Collections.newSetFromMap(new IdentityHashMap<>());
  }

  /** Types a poly call argument against the (partially inferred) parameter type. */
  boolean constrainPolyCall(Expr e, Type pt, Infer inf, Map<TypeVarSymbol, Type> partial) {
    Type target = wildcardUnresolved(Types.subst(pt, partial), inf);
    if (target == null) {
      return false;
    }
    Attr.Speculation<BExpr> s = a.speculate(() -> a.value(e, target));
    if (s.hasErrors() || s.result() == null) {
      inf.failed = true;
      return false;
    }
    Type t = s.result().type();
    if (t instanceof ClassType ct) {
      t = a.calls.types().capture(ct);
    }
    int before = boundsCount(inf);
    inf.subtype(a.calls.types().boxIfPrimitive(t), pt);
    return boundsCount(inf) > before;
  }

  /**
   * Replaces inference variables in type-argument positions by wildcards; null if pt is a bare
   * variable.
   */
  Type wildcardUnresolved(Type t, Infer inf) {
    if (inf.isVar(t)) {
      return null;
    }
    if (t instanceof ClassType ct && !ct.args().isEmpty()) {
      List<Type> args = new ArrayList<>();
      for (Type x : ct.args()) {
        if (inf.isVar(x)) {
          args.add(new Type.WildcardType(Type.WildcardType.Kind.UNBOUNDED, null));
        } else if (x instanceof Type.WildcardType w
            && w.bound() != null
            && inf.mentionsVars(w.bound())) {
          args.add(new Type.WildcardType(Type.WildcardType.Kind.UNBOUNDED, null));
        } else if (inf.mentionsVars(x)) {
          Type inner = wildcardUnresolved(x, inf);
          args.add(
              inner == null
                  ? new Type.WildcardType(Type.WildcardType.Kind.UNBOUNDED, null)
                  : inner);
        } else {
          args.add(x);
        }
      }
      return new ClassType(ct.sym(), args, ct.nullness());
    }
    return inf.mentionsVars(t) ? null : t;
  }

  static int boundsCount(Infer inf) {
    int n = 0;
    for (Infer.Bounds b : inf.bounds.values()) {
      n += b.lower.size() + b.upper.size() + b.eq.size();
    }
    return n;
  }

  /** Most specific applicable method (JLS 15.12.2.5, simplified), or null if ambiguous. */
  Selected mostSpecific(List<Selected> app, List<Calls.ArgInfo> args) {
    if (app.size() == 1) {
      return app.getFirst();
    }
    List<Selected> maximal = new ArrayList<>();
    for (Selected s : app) {
      boolean dominated = false;
      for (Selected o : app) {
        if (o != s && moreSpecific(o, s, args) && !moreSpecific(s, o, args)) {
          dominated = true;
          break;
        }
      }
      if (!dominated) {
        maximal.add(s);
      }
    }
    if (maximal.size() == 1) {
      return maximal.getFirst();
    }
    // Tie-breakers: fewer defaulted parameters, non-generic, then identical erasures (inherited
    // duplicates) resolve to the first.
    int minDefaults = maximal.stream().mapToInt(s -> s.defaultsUsed).min().orElse(0);
    List<Selected> fewer = maximal.stream().filter(s -> s.defaultsUsed == minDefaults).toList();
    if (fewer.size() == 1) {
      return fewer.getFirst();
    }
    List<Selected> nonGeneric =
        fewer.stream().filter(s -> s.method.typeParams().isEmpty()).toList();
    if (nonGeneric.size() == 1) {
      return nonGeneric.getFirst();
    }
    List<Selected> valueLambdaPreferred = preferValueReturningFunctional(fewer, args);
    if (valueLambdaPreferred.size() == 1) {
      return valueLambdaPreferred.getFirst();
    }
    String sig0 = erasedParams(fewer.getFirst().method);
    if (fewer.stream().allMatch(s -> erasedParams(s.method).equals(sig0))) {
      for (Selected s : fewer) {
        if (!s.method.isAbstract()) {
          return s;
        }
      }
      return fewer.getFirst();
    }
    return null;
  }

  /**
   * For lambdas with value-returning bodies, prefer {@code Callable}-like over {@code
   * Runnable}-like targets.
   */
  List<Selected> preferValueReturningFunctional(List<Selected> cands, List<Calls.ArgInfo> args) {
    List<Selected> out = new ArrayList<>();
    for (Selected s : cands) {
      boolean ok = true;
      for (int p = 0; p < s.mapping.size(); p++) {
        for (int idx : s.mapping.get(p)) {
          Calls.ArgInfo arg = args.get(idx);
          if (arg.deferred
              && unwrap(arg.expr) instanceof Expr.Lambda lam
              && lam.body() instanceof Body.ExprBody
              && s.paramTypes.get(p) instanceof ClassType ct) {
            Types.MethodType ft = a.calls.types().functionType(a.calls.types().nonWildcard(ct));
            if (ft != null && ft.ret() == PrimType.VOID) {
              ok = false;
            }
          }
        }
      }
      if (ok) {
        out.add(s);
      }
    }
    return out.isEmpty() ? cands : out;
  }

  static String erasedParams(MethodSymbol m) {
    return Descriptors.params(
        m.params().stream()
            .map(p -> p.type() == null ? (Type) Type.ErrorType.INSTANCE : p.type().erasure())
            .toList());
  }

  boolean moreSpecific(Selected s1, Selected s2, List<Calls.ArgInfo> args) {
    for (int i = 0; i < args.size(); i++) {
      Type t1 = paramTypeForArg(s1, i);
      Type t2 = paramTypeForArg(s2, i);
      if (t1 == null || t2 == null) {
        continue;
      }
      if (t1 instanceof PrimType p1 && t2 instanceof PrimType p2) {
        if (!Types.isWidening(p1, p2)) {
          return false;
        }
        continue;
      }
      if (t1 instanceof PrimType && !(t2 instanceof PrimType)) {
        Calls.ArgInfo arg = args.get(i);
        if (arg.type instanceof PrimType) {
          continue; // the primitive parameter matches a primitive argument better
        }
        return false;
      }
      if (!(t1 instanceof PrimType) && t2 instanceof PrimType) {
        Calls.ArgInfo arg = args.get(i);
        if (arg.type != null && arg.type.isReference()) {
          continue;
        }
        return false;
      }
      if (args.get(i).deferred
          && t1 instanceof ClassType c1
          && t2 instanceof ClassType c2
          && a.calls.types().findSam(c1.sym()) != null
          && a.calls.types().findSam(c2.sym()) != null
          && c1.sym() != c2.sym()
          && !a.calls.types().isSubtype(t1, t2)) {
        continue; // unrelated functional interfaces: decided by lambda compatibility instead
      }
      if (!a.calls.types().isSubtype(t1, t2)) {
        return false;
      }
    }
    // Variable arity (JLS 15.12.2.5): the variable-arity element types are compared too, which
    // decides calls with no variable arguments (count() with String... vs Object...).
    if (s1.phase == Phase.VARARGS
        && s2.phase == Phase.VARARGS
        && !s1.paramTypes.isEmpty()
        && !s2.paramTypes.isEmpty()
        && s1.paramTypes.getLast() instanceof Type.ArrayType a1
        && s2.paramTypes.getLast() instanceof Type.ArrayType a2) {
      Type e1 = a1.elem();
      Type e2 = a2.elem();
      if (e1 instanceof PrimType p1 && e2 instanceof PrimType p2) {
        return p1 == p2 || Types.isWidening(p1, p2);
      }
      return !(e1 instanceof PrimType)
          && !(e2 instanceof PrimType)
          && a.calls.types().isSubtype(e1, e2);
    }
    return true;
  }

  Type paramTypeForArg(Selected s, int argIndex) {
    for (int p = 0; p < s.mapping.size(); p++) {
      if (s.mapping.get(p).contains(argIndex)) {
        Type t = s.paramTypes.get(p);
        if (s.phase == Phase.VARARGS
            && p == s.mapping.size() - 1
            && t instanceof Type.ArrayType at) {
          return at.elem();
        }
        return t;
      }
    }
    return null;
  }
}

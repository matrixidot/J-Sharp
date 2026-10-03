package dev.jsharp.compiler.check;

import dev.jsharp.compiler.ast.Arg;
import dev.jsharp.compiler.ast.Decl;
import dev.jsharp.compiler.ast.DeconstructVar;
import dev.jsharp.compiler.ast.Expr;
import dev.jsharp.compiler.ast.FieldInit;
import dev.jsharp.compiler.ast.LocalKind;
import dev.jsharp.compiler.ast.Modifier;
import dev.jsharp.compiler.ast.Modifiers;
import dev.jsharp.compiler.ast.Pattern;
import dev.jsharp.compiler.ast.Stmt;
import dev.jsharp.compiler.ast.SwitchArm;
import dev.jsharp.compiler.ast.SwitchSection;
import dev.jsharp.compiler.bound.BClass;
import dev.jsharp.compiler.bound.BExpr;
import dev.jsharp.compiler.bound.BExpr.ConvKind;
import dev.jsharp.compiler.bound.BLValue;
import dev.jsharp.compiler.bound.BPattern;
import dev.jsharp.compiler.bound.BStmt;
import dev.jsharp.compiler.bound.BSwitch;
import dev.jsharp.compiler.diag.Code;
import dev.jsharp.compiler.diag.Diagnostic;
import dev.jsharp.compiler.resolve.TypeResolver;
import dev.jsharp.compiler.resolve.TypeScope;
import dev.jsharp.compiler.source.Span;
import dev.jsharp.compiler.symbols.ClassSymbol;
import dev.jsharp.compiler.symbols.FieldSymbol;
import dev.jsharp.compiler.symbols.Flags;
import dev.jsharp.compiler.symbols.MethodSymbol;
import dev.jsharp.compiler.symbols.PropertySymbol;
import dev.jsharp.compiler.symbols.TypeVarSymbol;
import dev.jsharp.compiler.symbols.VarSymbol;
import dev.jsharp.compiler.types.Descriptors;
import dev.jsharp.compiler.types.Nullness;
import dev.jsharp.compiler.types.Type;
import dev.jsharp.compiler.types.Type.ClassType;
import dev.jsharp.compiler.types.Type.PrimType;
import dev.jsharp.compiler.types.Type.TupleType;
import dev.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pattern matching ({@code is}, switch expressions and statements, exhaustiveness), tuples,
 * deconstruction, {@code with}, ranges and indices, {@code await}, and local/anonymous classes.
 */
final class Patterns {
  private final Attr a;

  Patterns(Attr a) {
    this.a = a;
  }

  private Env env() {
    return a.env;
  }

  private Types types() {
    return a.types;
  }

  // ------------------------------------------------------------------ patterns

  /** Declares a pattern binding (definitely assigned only where the pattern matched). */
  private VarSymbol binding(String name, Type type, Span span, List<VarSymbol> out) {
    if (name == null || name.equals("_")) {
      return null;
    }
    VarSymbol v = a.stmts.declare(name, type, 0, VarSymbol.Kind.PATTERN, span);
    out.add(v);
    return v;
  }

  /** Attributes a pattern matched against values of {@code input}. */
  BPattern pattern(Pattern p, Type input, List<VarSymbol> bindings) {
    Span span = p.span();
    switch (p) {
      case Pattern.Discard d -> {
        return new BPattern.Any(null, span);
      }
      case Pattern.Var v -> {
        return new BPattern.Any(binding(v.name(), input, span, bindings), span);
      }
      case Pattern.Paren pp -> {
        return pattern(pp.pattern(), input, bindings);
      }
      case Pattern.Type t -> {
        Type ty = a.resolveType(t.type(), TypeResolver.RawMode.WILDCARD);
        if (ty.isError()) {
          return new BPattern.Any(binding(t.binding(), ty, span, bindings), span);
        }
        Type tested = checkPatternType(input, ty, t.type().span());
        return new BPattern.TypeTest(
            tested,
            binding(t.binding(), tested.withNullness(Nullness.NON_NULL), span, bindings),
            span);
      }
      case Pattern.Constant c -> {
        return constantPattern(c, input, bindings);
      }
      case Pattern.Relational r -> {
        return relational(r, input);
      }
      case Pattern.Recursive r -> {
        return recursive(r, input, bindings);
      }
      case Pattern.And and -> {
        BPattern l = pattern(and.left(), input, bindings);
        Type narrowed = narrowedBy(l, input);
        BPattern r = pattern(and.right(), narrowed, bindings);
        return new BPattern.And(l, r, span);
      }
      case Pattern.Or or -> {
        List<VarSymbol> inner = new ArrayList<>();
        BPattern l = pattern(or.left(), input, inner);
        BPattern r = pattern(or.right(), input, inner);
        if (!inner.isEmpty()) {
          a.error(Code.INVALID_PATTERN, span, "'or' patterns cannot declare variables");
        }
        return new BPattern.Or(l, r, span);
      }
      case Pattern.Not n -> {
        List<VarSymbol> inner = new ArrayList<>();
        BPattern x = pattern(n.pattern(), input, inner);
        if (!inner.isEmpty()) {
          a.error(Code.INVALID_PATTERN, span, "'not' patterns cannot declare variables");
        }
        return new BPattern.Not(x, span);
      }
      case Pattern.ListPattern l -> {
        a.report(
            a.err(Code.UNSUPPORTED_FEATURE, span, "list patterns are planned for J# v0.2")
                .help("use xs.size() and indexing for now"));
        return new BPattern.Constant(new BExpr.Error(Type.ErrorType.INSTANCE, span), span);
      }
      case Pattern.Slice s -> {
        a.error(Code.INVALID_PATTERN, span, "'..' is only valid inside a list pattern");
        return new BPattern.Any(null, span);
      }
    }
  }

  /** The type a value is known to have after matching {@code p} (for {@code and} chains). */
  private Type narrowedBy(BPattern p, Type input) {
    return switch (p) {
      case BPattern.TypeTest t -> t.type().withNullness(Nullness.NON_NULL);
      case BPattern.Recursive r when r.type() != null -> r.type().withNullness(Nullness.NON_NULL);
      case BPattern.Not n
          when n.pattern() instanceof BPattern.Constant c
              && c.value().type() instanceof Type.NullType ->
          input.withNullness(Nullness.NON_NULL);
      default -> input;
    };
  }

  /** Checks that a value of {@code input} could be a {@code tested}; returns the tested type. */
  private Type checkPatternType(Type input, Type tested, Span span) {
    if (input.isError()) {
      return tested;
    }
    Type in = types().boxIfPrimitive(input);
    Type t = tested instanceof PrimType p ? a.syms.boxed(p) : tested;
    if (!types().isCastable(in, t)) {
      a.error(
          Code.INVALID_PATTERN,
          span,
          "a value of type " + input.display() + " can never be " + tested.display());
      return t;
    }
    if (t instanceof ClassType ct
        && !ct.args().isEmpty()
        && ct.args().stream().anyMatch(x -> !(x instanceof Type.WildcardType))) {
      ClassType known = types().asSuper(in, ct.sym());
      ClassType implied = known == null ? impliedType(in, ct.sym()) : null;
      boolean checked = implied != null && types().isSubtype(implied, ct);
      if (!checked && (known == null || !types().isSubtype(known, ct))) {
        a.report(
            a.err(
                    Code.INVALID_PATTERN,
                    span,
                    "cannot test type arguments of "
                        + ct.display()
                        + " at runtime (generics are erased)")
                .help("test " + ct.sym().name() + wildcards(ct.args().size()) + " instead"));
      }
    }
    return t;
  }

  private static String wildcards(int n) {
    return "<" + String.join(", ", java.util.Collections.nCopies(n, "?")) + ">";
  }

  /**
   * The parameterization of {@code sub} implied by a value of static type {@code in}: for {@code in
   * = Result<Integer, String>} and {@code sub = Ok<A, B> : Result<A, B>} it is {@code Ok<Integer,
   * String>} (JLS 5.1.6.1 checked narrowing, 18.5.5 record pattern inference). Null if some type
   * argument is not determined.
   */
  ClassType impliedType(Type in, ClassSymbol sub) {
    if (sub.typeParams().isEmpty()) {
      return null;
    }
    if (!(types().boxIfPrimitive(in) instanceof ClassType ic)
        || ic.args().stream().anyMatch(x -> x instanceof Type.WildcardType)) {
      return null;
    }
    ClassType generic = sub.thisType();
    ClassType sup = types().asSuper(generic, ic.sym());
    if (sup == null) {
      return null;
    }
    Infer inf = new Infer(types(), sub.typeParams());
    inf.eq(sup, ic.withNullness(Nullness.NON_NULL));
    java.util.Map<TypeVarSymbol, Type> sol = inf.solve(false);
    if (sol.size() != sub.typeParams().size()) {
      return null;
    }
    return (ClassType) Types.subst(generic, sol).withNullness(Nullness.NON_NULL);
  }

  /** A raw or all-wildcard generic type in a pattern gets the arguments implied by the input. */
  private Type inferPatternType(Type input, Type declared) {
    if (declared instanceof ClassType ct
        && !ct.args().isEmpty()
        && ct.args().stream()
            .allMatch(
                x ->
                    x instanceof Type.WildcardType w
                        && w.kind() == Type.WildcardType.Kind.UNBOUNDED)) {
      ClassType implied = impliedType(input, ct.sym());
      if (implied == null) {
        ClassType same = types().asSuper(types().boxIfPrimitive(input), ct.sym());
        if (same != null && same.args().stream().noneMatch(x -> x instanceof Type.WildcardType)) {
          implied = same;
        }
      }
      if (implied != null) {
        return implied;
      }
    }
    return declared;
  }

  private BPattern constantPattern(Pattern.Constant c, Type input, List<VarSymbol> bindings) {
    Expr e = c.value();
    // `case North:` on an enum selector names the constant without qualification (Java rule).
    if (e instanceof Expr.Name n
        && n.typeArgs().isEmpty()
        && input instanceof ClassType ict
        && ict.sym().isEnum()) {
      for (FieldSymbol f : ict.sym().fields()) {
        if (f.has(Flags.ENUM_CONSTANT) && f.name().equals(n.name())) {
          return new BPattern.Constant(
              a.fieldGet(null, ict.withNullness(Nullness.NON_NULL), f, e.span()), c.span());
        }
      }
    }
    // A bare (qualified) name may denote a type: `case Circle:` / `is Circle`.
    if (e instanceof Expr.Name || e instanceof Expr.Member) {
      Attr.Speculation<Attr.Target> s = a.speculate(() -> a.target(e, true));
      if (!s.hasErrors() && s.result() instanceof Attr.TypeTarget tt) {
        Type tested = checkPatternType(input, tt.type(), e.span());
        return new BPattern.TypeTest(tested, null, c.span());
      }
    }
    BExpr v = a.value(e, input);
    if (v.type().isError()) {
      return new BPattern.Constant(v, c.span());
    }
    if (v.type() instanceof Type.NullType) {
      if (input instanceof PrimType) {
        a.error(
            Code.INVALID_PATTERN,
            c.span(),
            "a value of type " + input.display() + " is never null");
      }
      return new BPattern.Constant(v, c.span());
    }
    boolean isConst =
        ConstFold.isConst(v) || v instanceof BExpr.Field f && f.field().has(Flags.ENUM_CONSTANT);
    if (!isConst) {
      a.report(
          a.err(
                  Code.INVALID_PATTERN,
                  e.span(),
                  "a pattern must be a constant (literal, enum constant or constant field)")
              .help("use a guard instead: 'case var x when x == value'"));
      return new BPattern.Constant(v, c.span());
    }
    PrimType ip = types().primitiveView(input);
    PrimType vp = types().primitiveView(v.type());
    if (ip != null && vp != null && ip.isNumeric() && vp.isNumeric()) {
      PrimType opType = ip;
      if (!Types.isWidening(vp, ip) && Attr.intConstant(v) == null) {
        a.error(
            Code.INVALID_PATTERN,
            e.span(),
            "constant of type " + v.type().display() + " cannot match " + input.display());
      }
      if (Attr.intConstant(v) != null
          && !Types.isWidening(vp, ip)
          && types().assignConversion(v.type(), ip, Attr.intConstant(v)) == Types.Conv.NONE) {
        a.error(
            Code.INVALID_PATTERN,
            e.span(),
            "constant " + ConstFold.valueOf(v) + " is out of range for " + input.display());
      }
      return new BPattern.Constant(a.primConv(v, opType, e.span()), c.span());
    }
    if (!types().isCastable(types().boxIfPrimitive(input), types().boxIfPrimitive(v.type()))) {
      a.error(
          Code.INVALID_PATTERN,
          e.span(),
          "constant of type "
              + v.type().display()
              + " can never match a value of type "
              + input.display());
    }
    return new BPattern.Constant(v, c.span());
  }

  private BPattern relational(Pattern.Relational r, Type input) {
    PrimType ip = types().primitiveView(input);
    BExpr v = a.value(r.value(), null);
    if (v.type().isError() || input.isError()) {
      return new BPattern.Relational(BExpr.BinOp.EQ, v, input, r.span());
    }
    PrimType vp = types().primitiveView(v.type());
    if (ip == null || !ip.isNumeric() || vp == null || !vp.isNumeric()) {
      a.error(
          Code.INVALID_PATTERN,
          r.span(),
          "relational patterns need numeric values, found " + input.display());
      return new BPattern.Relational(BExpr.BinOp.EQ, v, input, r.span());
    }
    if (!ConstFold.isConst(v)) {
      a.error(Code.INVALID_PATTERN, r.value().span(), "a relational pattern needs a constant");
    }
    PrimType opType = Types.promote(ip, vp);
    BExpr.BinOp op =
        switch (r.op()) {
          case LT -> BExpr.BinOp.LT;
          case LE -> BExpr.BinOp.LE;
          case GT -> BExpr.BinOp.GT;
          default -> BExpr.BinOp.GE;
        };
    return new BPattern.Relational(op, a.primConv(v, opType, r.value().span()), opType, r.span());
  }

  private BPattern recursive(Pattern.Recursive r, Type input, List<VarSymbol> bindings) {
    Span span = r.span();
    Type t = input;
    if (r.type() != null) {
      Type declared = a.resolveType(r.type(), TypeResolver.RawMode.WILDCARD);
      if (declared.isError()) {
        return new BPattern.Any(null, span);
      }
      t = checkPatternType(input, inferPatternType(input, declared), r.type().span());
    } else if (r.positional() != null && input.isError()) {
      for (Pattern sub : r.positional()) {
        pattern(sub, Type.ErrorType.INSTANCE, bindings);
      }
      return new BPattern.Any(null, span);
    }
    Type site = t.withNullness(Nullness.NON_NULL);
    if (site instanceof PrimType p) {
      site = a.syms.boxed(p);
    }
    List<MethodSymbol> accessors = new ArrayList<>();
    List<Type> accTypes = new ArrayList<>();
    List<BPattern> subs = new ArrayList<>();
    if (r.positional() != null) {
      List<Accessor> pos = positionalAccessors(site, span);
      if (pos != null && pos.size() != r.positional().size()) {
        a.error(
            Code.INVALID_PATTERN,
            span,
            site.display()
                + " has "
                + pos.size()
                + " component"
                + (pos.size() == 1 ? "" : "s")
                + ", but the pattern has "
                + r.positional().size());
        pos = null;
      }
      for (int i = 0; i < r.positional().size(); i++) {
        if (pos == null) {
          pattern(r.positional().get(i), Type.ErrorType.INSTANCE, bindings);
          continue;
        }
        Accessor acc = pos.get(i);
        accessors.add(acc.method());
        accTypes.add(acc.type());
        subs.add(pattern(r.positional().get(i), acc.type(), bindings));
      }
    }
    if (r.properties() != null) {
      for (Pattern.PropertySub ps : r.properties()) {
        addPropertySub(
            site, ps.path(), 0, ps.pathSpan(), ps.pattern(), accessors, accTypes, subs, bindings);
      }
    }
    VarSymbol b = binding(r.binding(), site, span, bindings);
    return new BPattern.Recursive(
        t.withNullness(Nullness.NON_NULL), accessors, accTypes, subs, b, span);
  }

  private void addPropertySub(
      Type site,
      List<String> path,
      int i,
      Span span,
      Pattern p,
      List<MethodSymbol> accessors,
      List<Type> accTypes,
      List<BPattern> subs,
      List<VarSymbol> bindings) {
    String name = path.get(i);
    Accessor acc = propertyAccessor(site, name, span);
    if (acc == null) {
      pattern(p, Type.ErrorType.INSTANCE, bindings);
      return;
    }
    accessors.add(acc.method());
    accTypes.add(acc.type());
    if (i == path.size() - 1) {
      subs.add(pattern(p, acc.type(), bindings));
    } else {
      List<MethodSymbol> innerAcc = new ArrayList<>();
      List<Type> innerTypes = new ArrayList<>();
      List<BPattern> innerSubs = new ArrayList<>();
      Type innerSite = acc.type().withNullness(Nullness.NON_NULL);
      addPropertySub(innerSite, path, i + 1, span, p, innerAcc, innerTypes, innerSubs, bindings);
      subs.add(new BPattern.Recursive(innerSite, innerAcc, innerTypes, innerSubs, null, span));
    }
  }

  /** An accessor method and the (instantiated) type it returns. */
  record Accessor(MethodSymbol method, Type type) {}

  /** Record components (or tuple elements, or Map.Entry key/value) of {@code site}, or null. */
  List<Accessor> positionalAccessors(Type site, Span span) {
    if (site instanceof TupleType tt) {
      List<Accessor> out = new ArrayList<>();
      for (int i = 0; i < tt.elems().size(); i++) {
        MethodSymbol m = tt.erasedSym().methods("item" + (i + 1)).getFirst();
        out.add(new Accessor(m, tt.elems().get(i)));
      }
      return out;
    }
    if (site instanceof ClassType ct && ct.sym().isRecord()) {
      List<Accessor> out = new ArrayList<>();
      for (FieldSymbol f : ct.sym().recordComponents()) {
        MethodSymbol m = null;
        for (MethodSymbol x : ct.sym().methods(f.name())) {
          if (x.params().isEmpty()) {
            m = x;
          }
        }
        if (m == null) {
          return null;
        }
        out.add(
            new Accessor(
                m, types().uncapture(a.memberType(a.captureSite(ct), ct.sym(), f.type()))));
      }
      return out;
    }
    ClassSymbol entry = a.syms.lookup("java/util/Map$Entry");
    ClassType asEntry = entry == null ? null : types().asSuper(site, entry);
    if (asEntry != null) {
      List<Accessor> out = new ArrayList<>();
      for (String n : List.of("getKey", "getValue")) {
        MethodSymbol m = entry.methods(n).getFirst();
        out.add(
            new Accessor(
                m, types().uncapture(a.memberType(a.captureSite(site), entry, m.returnType()))));
      }
      return out;
    }
    if (!site.isError()) {
      a.report(
          a.err(
                  Code.INVALID_PATTERN,
                  span,
                  "a positional pattern needs a record or tuple, but "
                      + site.display()
                      + " is neither")
              .help(
                  "use a property pattern: "
                      + (site instanceof ClassType c ? c.sym().name() : "T")
                      + " { name: pattern }"));
    }
    return null;
  }

  private Accessor propertyAccessor(Type site, String name, Span span) {
    PropertySymbol p = a.lookup.findProperty(site, name);
    if (p != null && p.getter() != null) {
      return new Accessor(
          p.getter(), types().uncapture(a.memberType(a.captureSite(site), p.owner(), p.type())));
    }
    MethodSymbol m = a.lookup.findRecordAccessor(site, name);
    if (m == null) {
      m = a.lookup.findGetter(site, name);
    }
    if (m == null) {
      for (MethodSymbol x : a.lookup.findMethods(site, name)) {
        if (x.params().isEmpty() && x.returnType() != PrimType.VOID && !x.isStatic()) {
          m = x;
        }
      }
    }
    if (m == null) {
      if (site instanceof Type.ArrayType && name.equals("length")) {
        a.error(
            Code.INVALID_PATTERN,
            span,
            "property patterns on array length are not supported; use a guard");
        return null;
      }
      a.reportNoMember(site, name, span);
      return null;
    }
    return new Accessor(
        m, types().uncapture(a.memberType(a.captureSite(site), m.owner(), m.returnType())));
  }

  // ------------------------------------------------------------------ is

  BExpr isExpr(Expr.Is i) {
    BExpr e = a.value(i.expr(), null);
    Type input = e.type();
    FlowState before = env().flow.copy();
    List<VarSymbol> bindings = new ArrayList<>();
    BPattern p = pattern(i.pattern(), input, bindings);
    FlowState t = env().flow.copy();
    FlowState f = before.copy();
    f.alive = env().flow.alive;
    for (VarSymbol b : bindings) {
      t.assigned.set(b.id());
    }
    VarSymbol v = Attr.localOf(e);
    if (v != null && !input.isError()) {
      switch (p) {
        case BPattern.TypeTest tt when tt.binding() == null ->
            t.narrowed.put(v, tt.type().withNullness(Nullness.NON_NULL));
        case BPattern.Recursive r when r.type() != null ->
            t.narrowed.put(v, r.type().withNullness(Nullness.NON_NULL));
        case BPattern.Constant c when c.value().type() instanceof Type.NullType ->
            f.narrowed.put(v, input.withNullness(Nullness.NON_NULL));
        case BPattern.Not n
            when n.pattern() instanceof BPattern.Constant c
                && c.value().type() instanceof Type.NullType ->
            t.narrowed.put(v, input.withNullness(Nullness.NON_NULL));
        case BPattern.Not n
            when n.pattern() instanceof BPattern.TypeTest tt && tt.binding() == null ->
            f.narrowed.put(v, tt.type().withNullness(Nullness.NON_NULL));
        default -> {}
      }
    }
    BExpr result = new BExpr.IsPattern(e, p, PrimType.BOOLEAN, i.span());
    if (isAlwaysTrue(p, input) && !input.isError() && !a.isSpeculative()) {
      a.warn(
          Code.REDUNDANT_NON_NULL_ASSERTION,
          i.span(),
          "this pattern always matches a value of type " + input.display());
    }
    a.narrowCondition(result, t, f);
    env().flow.set(t.copy());
    env().flow.join(f);
    return result;
  }

  private boolean isAlwaysTrue(BPattern p, Type input) {
    return p instanceof BPattern.Any
        || (p instanceof BPattern.TypeTest tt
            && input.nullness() == Nullness.NON_NULL
            && !(input instanceof PrimType)
            && types().isSubtype(input, tt.type()));
  }

  // ------------------------------------------------------------------ switch

  BExpr switchExpr(Expr.Switch s, Type pt) {
    BExpr sel = a.value(s.selector(), null);
    Type input = sel.type();
    VarSymbol selVar =
        env()
            .newVar(
                "$selector",
                input,
                Flags.FINAL | Flags.SYNTHETIC,
                VarSymbol.Kind.LOCAL,
                s.selector().span());
    env().flow.assigned.set(selVar.id());
    FlowState start = env().flow.copy();
    List<BSwitch.Case> cases = new ArrayList<>();
    List<FlowState> ends = new ArrayList<>();
    List<BExpr> values = new ArrayList<>();
    List<SwitchArm> arms = s.arms();
    Type target = pt == PrimType.VOID ? null : pt;
    for (SwitchArm arm : arms) {
      env().flow.set(start.copy());
      Scope saved = env().scope;
      env().scope = saved.child();
      try {
        List<VarSymbol> bindings = new ArrayList<>();
        BPattern p =
            arm.pattern() instanceof Pattern.Discard
                ? null
                : pattern(arm.pattern(), input, bindings);
        for (VarSymbol b : bindings) {
          env().flow.assigned.set(b.id());
        }
        narrowSelectorLocal(sel, p);
        BExpr guard = null;
        if (arm.guard() != null) {
          guard = a.condition(arm.guard());
          env().flow.set(a.whenTrue);
        }
        BExpr value = a.value(arm.body(), target);
        values.add(value);
        ends.add(env().flow.copy());
        cases.add(new BSwitch.Case(p, guard, List.of(), value));
        // After an unguarded `null =>` arm the selector local is non-null in later arms.
        VarSymbol selLocal = Attr.localOf(sel);
        if (selLocal != null
            && arm.guard() == null
            && p instanceof BPattern.Constant pc
            && pc.value().type() instanceof Type.NullType) {
          start.narrowed.put(selLocal, selLocal.type().withNullness(Nullness.NON_NULL));
        }
      } finally {
        env().scope = saved;
      }
    }
    Type type;
    if (target != null) {
      type = target;
    } else {
      List<Type> ts = new ArrayList<>();
      for (BExpr v : values) {
        ts.add(v.type());
      }
      type = ts.isEmpty() ? Type.ErrorType.INSTANCE : types().lub(ts);
      if (type instanceof Type.NullType) {
        type = a.syms.objectType().withNullness(Nullness.NULLABLE);
      }
    }
    List<BSwitch.Case> coerced = new ArrayList<>();
    for (int i = 0; i < cases.size(); i++) {
      BSwitch.Case c = cases.get(i);
      BExpr v = c.value();
      if (!(type instanceof Type.NeverType)) {
        v = a.coerce(v, type, arms.get(i).body().span());
      }
      coerced.add(new BSwitch.Case(c.pattern(), c.guard(), c.body(), v));
    }
    checkDominance(coerced, arms.stream().map(SwitchArm::span).toList(), input);
    boolean exhaustive = checkExhaustive(coerced, input, s.span(), true);
    env().flow.set(FlowState.dead());
    for (FlowState f : ends) {
      env().flow.join(f);
    }
    if (ends.isEmpty()) {
      env().flow.set(start);
    }
    return new BExpr.Switch(new BSwitch(sel, selVar, input, coerced, exhaustive), type, s.span());
  }

  private void narrowSelectorLocal(BExpr sel, BPattern p) {
    VarSymbol v = Attr.localOf(sel);
    if (v == null || p == null) {
      return;
    }
    if (p instanceof BPattern.TypeTest tt) {
      env().flow.narrowed.put(v, tt.type().withNullness(Nullness.NON_NULL));
    } else if (p instanceof BPattern.Recursive r && r.type() != null) {
      env().flow.narrowed.put(v, r.type().withNullness(Nullness.NON_NULL));
    }
  }

  BStmt switchStmt(Stmt.Switch sw) {
    BExpr sel = a.value(sw.selector(), null);
    Type input = sel.type();
    VarSymbol selVar =
        env()
            .newVar(
                "$selector",
                input,
                Flags.FINAL | Flags.SYNTHETIC,
                VarSymbol.Kind.LOCAL,
                sw.selector().span());
    env().flow.assigned.set(selVar.id());
    FlowState start = env().flow.copy();
    Env.Jump jump = new Env.Jump(null, new BStmt.Label(null), false, true);
    env().jumps.push(jump);
    List<BSwitch.Case> cases = new ArrayList<>();
    List<Span> caseSpans = new ArrayList<>();
    List<FlowState> ends = new ArrayList<>();
    boolean hasDefault = false;
    try {
      for (int si = 0; si < sw.sections().size(); si++) {
        SwitchSection sec = sw.sections().get(si);
        env().flow.set(start.copy());
        Scope saved = env().scope;
        env().scope = saved.child();
        try {
          BPattern combined = null;
          BExpr guard = null;
          boolean isDefault = false;
          List<VarSymbol> bindings = new ArrayList<>();
          for (SwitchSection.Label l : sec.labels()) {
            if (l.pattern() == null) {
              if (hasDefault) {
                a.error(Code.DUPLICATE_CASE, l.span(), "duplicate 'default' label");
              }
              hasDefault = true;
              isDefault = true;
              continue;
            }
            List<VarSymbol> lb = new ArrayList<>();
            BPattern p = pattern(l.pattern(), input, lb);
            if (sec.labels().size() > 1 && !lb.isEmpty()) {
              a.error(
                  Code.INVALID_PATTERN,
                  l.span(),
                  "a case with several labels cannot declare pattern variables");
            }
            bindings.addAll(lb);
            for (VarSymbol b : lb) {
              env().flow.assigned.set(b.id());
            }
            if (l.guard() != null) {
              if (sec.labels().size() > 1) {
                a.error(
                    Code.INVALID_PATTERN,
                    l.span(),
                    "a guarded case must be the only label of its section");
              }
              guard = a.condition(l.guard());
              env().flow.set(a.whenTrue);
            }
            combined = combined == null ? p : new BPattern.Or(combined, p, l.span());
          }
          for (VarSymbol b : bindings) {
            env().flow.assigned.set(b.id());
          }
          if (sec.labels().size() == 1) {
            narrowSelectorLocal(sel, combined);
          }
          List<BStmt> body = a.stmts.statements(sec.body(), 0);
          if (env().flow.alive && si < sw.sections().size() - 1) {
            a.report(
                a.err(
                        Code.SWITCH_FALLTHROUGH,
                        sec.labels().getLast().span(),
                        "this switch section falls through to the next one")
                    .help("end it with 'break', 'return', 'continue' or 'throw'"));
          }
          ends.add(env().flow.copy());
          cases.add(new BSwitch.Case(isDefault ? null : combined, guard, body, null));
          caseSpans.add(sec.span());
        } finally {
          env().scope = saved;
        }
      }
    } finally {
      env().jumps.pop();
    }
    checkDominance(cases, caseSpans, input);
    // Java rule: only an "enhanced" switch statement (type/record patterns or `case null`) must be
    // and is treated as exhaustive; a classic constant switch (case North:) without default is
    // not, so the code after it stays reachable.
    boolean enhanced =
        cases.stream().anyMatch(c -> c.pattern() != null && isEnhancedLabel(c.pattern()));
    boolean exhaustive = hasDefault || enhanced && checkExhaustive(cases, input, sw.span(), false);
    env().flow.set(exhaustive ? FlowState.dead() : start.copy());
    for (FlowState f : ends) {
      env().flow.join(f);
    }
    for (FlowState b : jump.breaks) {
      env().flow.join(b);
    }
    return new BStmt.Switch(
        new BSwitch(sel, selVar, input, cases, exhaustive), jump.label, sw.span());
  }

  private static boolean isEnhancedLabel(BPattern p) {
    return switch (p) {
      case BPattern.Constant c -> c.value().type() instanceof Type.NullType;
      case BPattern.Or or -> isEnhancedLabel(or.left()) || isEnhancedLabel(or.right());
      default -> true;
    };
  }

  // ------------------------------------------------------------------ exhaustiveness

  /** Does pattern {@code p} match every non-null value of {@code t}? */
  boolean isTotal(BPattern p, Type t) {
    return switch (p) {
      case null -> true;
      case BPattern.Any any -> true;
      case BPattern.TypeTest tt ->
          types().isSubtype(types().boxIfPrimitive(t).withNullness(Nullness.NON_NULL), tt.type())
              || t.isError();
      case BPattern.Recursive r -> {
        if (r.type() != null && !types().isSubtype(types().boxIfPrimitive(t), r.type())) {
          yield false;
        }
        for (int i = 0; i < r.subpatterns().size(); i++) {
          if (!isTotal(r.subpatterns().get(i), r.accessorTypes().get(i))
              && !coversAll(List.of(r.subpatterns().get(i)), r.accessorTypes().get(i))) {
            yield false;
          }
        }
        yield true;
      }
      case BPattern.And and -> isTotal(and.left(), t) && isTotal(and.right(), t);
      case BPattern.Or or ->
          isTotal(or.left(), t)
              || isTotal(or.right(), t)
              || coversAll(List.of(or.left(), or.right()), t);
      default -> false;
    };
  }

  /** Do the patterns together cover every non-null value of {@code t}? */
  private boolean coversAll(List<BPattern> pats, Type t) {
    List<BPattern> flat = new ArrayList<>();
    for (BPattern p : pats) {
      flatten(p, flat);
    }
    for (BPattern p : flat) {
      if (isTotal(p, t)) {
        return true;
      }
    }
    return missing(flat, t).isEmpty();
  }

  private static void flatten(BPattern p, List<BPattern> out) {
    if (p instanceof BPattern.Or or) {
      flatten(or.left(), out);
      flatten(or.right(), out);
    } else {
      out.add(p);
    }
  }

  /** Values (described) of {@code t} not covered by the patterns; empty if covered. */
  private List<String> missing(List<BPattern> pats, Type t) {
    for (BPattern p : pats) {
      if (isTotal(p, t)) {
        return List.of();
      }
    }
    PrimType prim = types().primitiveView(t);
    if (prim == PrimType.BOOLEAN) {
      boolean hasTrue = false;
      boolean hasFalse = false;
      for (BPattern p : pats) {
        if (p instanceof BPattern.Constant c && ConstFold.valueOf(c.value()) instanceof Boolean b) {
          hasTrue |= b;
          hasFalse |= !b;
        }
      }
      List<String> out = new ArrayList<>();
      if (!hasTrue) {
        out.add("true");
      }
      if (!hasFalse) {
        out.add("false");
      }
      return out;
    }
    if (!(t instanceof ClassType ct)) {
      return List.of("_");
    }
    ClassSymbol c = ct.sym();
    if (c.isEnum()) {
      Set<String> covered = new HashSet<>();
      for (BPattern p : pats) {
        if (p instanceof BPattern.Constant k && k.value() instanceof BExpr.Field f) {
          covered.add(f.field().name());
        }
      }
      List<String> out = new ArrayList<>();
      for (FieldSymbol f : c.enumConstants()) {
        if (!covered.contains(f.name())) {
          out.add(c.name() + "." + f.name());
        }
      }
      return out;
    }
    if (c.has(Flags.SEALED) && !c.permitted().isEmpty()) {
      List<String> out = new ArrayList<>();
      if (!c.isAbstract() && !c.isInterface()) {
        out.add(c.name());
      }
      for (ClassSymbol sub : c.permitted()) {
        ClassType subType = subtypeOf(ct, sub);
        List<BPattern> applicable = new ArrayList<>();
        for (BPattern p : pats) {
          if (appliesTo(p, subType)) {
            applicable.add(p);
          }
        }
        List<String> m = missing(applicable, subType);
        if (!m.isEmpty()) {
          out.add(m.equals(List.of("_")) || m.size() > 3 ? sub.name() : String.join(", ", m));
        }
      }
      return out;
    }
    return List.of("_");
  }

  /** Can pattern {@code p} match some value of {@code t}? (Used to restrict to a subtype.) */
  private boolean appliesTo(BPattern p, Type t) {
    return switch (p) {
      case BPattern.TypeTest tt -> types().isCastable(t, tt.type());
      case BPattern.Recursive r -> r.type() == null || types().isCastable(t, r.type());
      default -> true;
    };
  }

  /** {@code Sub} instantiated as a subtype of {@code sup} (type arguments where derivable). */
  private ClassType subtypeOf(ClassType sup, ClassSymbol sub) {
    if (sub.typeParams().isEmpty()) {
      return ClassType.of(sub);
    }
    ClassType generic = sub.thisType();
    ClassType asSup = types().asSuper(generic, sup.sym());
    if (asSup != null && asSup.args().size() == sup.args().size()) {
      java.util.Map<dev.jsharp.compiler.symbols.TypeVarSymbol, Type> m =
          new java.util.IdentityHashMap<>();
      for (int i = 0; i < asSup.args().size(); i++) {
        if (asSup.args().get(i) instanceof Type.TypeVar tv
            && !(sup.args().get(i) instanceof Type.WildcardType)) {
          m.put(tv.sym(), sup.args().get(i));
        }
      }
      return (ClassType) Types.subst(generic, m);
    }
    return generic;
  }

  /** Reports a non-exhaustive switch expression; returns whether it is exhaustive. */
  private boolean checkExhaustive(
      List<BSwitch.Case> cases, Type input, Span span, boolean required) {
    if (input.isError()) {
      return true;
    }
    List<BPattern> unguarded = new ArrayList<>();
    boolean handlesNull = false;
    boolean hasDefault = false;
    for (BSwitch.Case c : cases) {
      if (c.pattern() == null) {
        hasDefault = true;
        continue;
      }
      List<BPattern> flat = new ArrayList<>();
      flatten(c.pattern(), flat);
      for (BPattern p : flat) {
        if (p instanceof BPattern.Constant k && k.value().type() instanceof Type.NullType) {
          handlesNull = true;
        }
        if (p instanceof BPattern.Any && c.guard() == null) {
          handlesNull = true;
        }
      }
      if (c.guard() == null) {
        unguarded.add(c.pattern());
      }
    }
    if (hasDefault) {
      return true;
    }
    Type nonNull = input.withNullness(Nullness.NON_NULL);
    List<String> miss = missing(flatAll(unguarded), nonNull);
    boolean nullOk = input.nullness() != Nullness.NULLABLE || handlesNull;
    if (miss.isEmpty() && nullOk) {
      return true;
    }
    if (required) {
      Diagnostic.Builder d =
          a.err(
              Code.NOT_EXHAUSTIVE,
              span,
              "switch expression does not handle all values of " + input.display());
      if (!miss.isEmpty()) {
        d.note(
            "not handled: "
                + String.join(", ", miss.subList(0, Math.min(miss.size(), 6)))
                + (miss.size() > 6 ? ", ..." : ""));
      }
      if (!nullOk) {
        d.note("not handled: null");
      }
      d.help(
          miss.equals(List.of("_"))
              ? "add a default arm: '_ => ...'"
              : "add the missing cases, or a default arm '_ => ...'");
      a.report(d);
    }
    return false;
  }

  private static List<BPattern> flatAll(List<BPattern> pats) {
    List<BPattern> out = new ArrayList<>();
    for (BPattern p : pats) {
      flatten(p, out);
    }
    return out;
  }

  /** Reports cases that can never be reached because an earlier unguarded case covers them. */
  private void checkDominance(List<BSwitch.Case> cases, List<Span> spans, Type input) {
    List<BPattern> earlier = new ArrayList<>();
    Set<Object> constants = new HashSet<>();
    boolean catchAll = false;
    for (int i = 0; i < cases.size(); i++) {
      BSwitch.Case c = cases.get(i);
      if (catchAll) {
        a.error(
            Code.DUPLICATE_CASE,
            spans.get(i),
            "this case can never match: an earlier case matches everything");
        continue;
      }
      if (c.pattern() == null && c.guard() == null && c.value() != null) {
        catchAll = true; // `_ =>` arm of a switch expression
      }
      if (c.pattern() != null) {
        List<BPattern> flat = new ArrayList<>();
        flatten(c.pattern(), flat);
        for (BPattern p : flat) {
          Object key = constantKey(p);
          if (key != null && !constants.add(key)) {
            a.error(Code.DUPLICATE_CASE, spans.get(i), "duplicate case " + key);
          }
        }
        boolean dominated = false;
        for (BPattern e : earlier) {
          if (dominates(e, c.pattern(), input)) {
            dominated = true;
          }
        }
        if (dominated) {
          a.error(
              Code.DUPLICATE_CASE,
              spans.get(i),
              "this case can never match: an earlier case already covers it");
        }
      }
      if (c.guard() == null && c.pattern() != null) {
        earlier.add(c.pattern());
      }
    }
  }

  private static Object constantKey(BPattern p) {
    if (p instanceof BPattern.Constant c) {
      if (c.value() instanceof BExpr.Field f) {
        return f.field().name();
      }
      Object v = ConstFold.valueOf(c.value());
      if (v != null) {
        return v instanceof String s ? "\"" + s + "\"" : v;
      }
      if (c.value().type() instanceof Type.NullType) {
        return "null";
      }
    }
    return null;
  }

  private boolean dominates(BPattern earlier, BPattern later, Type input) {
    if (earlier instanceof BPattern.Any) {
      return true;
    }
    Object k = later instanceof BPattern.Constant lc ? ConstFold.valueOf(lc.value()) : null;
    if (k != null && earlier instanceof BPattern.TypeTest et) {
      BExpr kv = ((BPattern.Constant) later).value();
      Type kt = kv.type() instanceof PrimType p ? a.syms.boxed(p) : kv.type();
      return types().isSubtype(kt, et.type());
    }
    if (k != null
        && earlier instanceof BPattern.Relational rel
        && rel.operandType() instanceof PrimType pt) {
      Object bound = ConstFold.valueOf(rel.value());
      Object v = k instanceof Character ch ? (Object) (int) ch.charValue() : k;
      if (bound != null && (v instanceof Number)) {
        return Boolean.TRUE.equals(ConstFold.binary(rel.op(), ConstFold.convert(v, pt), bound, pt));
      }
    }
    if (earlier instanceof BPattern.TypeTest e) {
      Type lt =
          switch (later) {
            case BPattern.TypeTest l -> l.type();
            case BPattern.Recursive r -> r.type();
            default -> null;
          };
      return lt != null && types().isSubtype(lt, e.type());
    }
    return false;
  }

  // ------------------------------------------------------------------ tuples

  BExpr tuple(Expr.Tuple t, Type pt) {
    int n = t.elements().size();
    TupleType target = pt instanceof TupleType tt && tt.elems().size() == n ? tt : null;
    ClassSymbol sym = a.typeResolver.tupleClass(n);
    if (sym == null) {
      a.error(
          Code.INVALID_TUPLE_USE,
          t.span(),
          n > 8
              ? "tuples have at most 8 elements"
              : "tuple support needs the J# runtime on the class path");
      for (Arg x : t.elements()) {
        a.value(x.value(), null);
      }
      return new BExpr.Error(Type.ErrorType.INSTANCE, t.span());
    }
    List<Type> elemTypes = new ArrayList<>();
    List<String> names = new ArrayList<>();
    List<BExpr> args = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Arg x = t.elements().get(i);
      Type et = target == null ? null : target.elems().get(i);
      BExpr v = et == null ? a.value(x.value(), null) : a.exprCoerced(x.value(), et);
      Type vt = et != null ? et : v.type();
      if (vt instanceof Type.NullType) {
        vt = a.syms.objectType().withNullness(Nullness.NULLABLE);
      }
      elemTypes.add(a.types.uncapture(vt));
      names.add(x.name() != null ? x.name() : target == null ? null : target.names().get(i));
      args.add(v);
    }
    TupleType tt = new TupleType(elemTypes, names, sym, Nullness.NON_NULL);
    ClassType ct = types().tupleClassType(tt);
    List<BExpr> boxed = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      boxed.add(
          a.coerce(
              args.get(i), types().boxIfPrimitive(elemTypes.get(i)), t.elements().get(i).span()));
    }
    MethodSymbol ctor = sym.methods(MethodSymbol.CONSTRUCTOR).getFirst();
    return new BExpr.Conv(new BExpr.New(ct, ctor, boxed, t.span()), ConvKind.RETYPE, tt, t.span());
  }

  /** {@code t.item1} or a named element {@code t.id}; null if no such element. */
  BExpr tupleElement(BExpr recv, TupleType tt, String name, Span nameSpan, Span span) {
    int idx = tt.names().indexOf(name);
    if (idx < 0 && name.startsWith("item")) {
      try {
        idx = Integer.parseInt(name.substring(4)) - 1;
      } catch (NumberFormatException e) {
        idx = -1;
      }
    }
    if (idx < 0 || idx >= tt.elems().size()) {
      return null;
    }
    return elementAccess(recv, tt, idx, span);
  }

  BExpr elementAccess(BExpr recv, TupleType tt, int idx, Span span) {
    MethodSymbol m = tt.erasedSym().methods("item" + (idx + 1)).getFirst();
    Type et = tt.elems().get(idx);
    BExpr call =
        new BExpr.Call(
            recv, m, List.of(), BExpr.CallKind.VIRTUAL, types().boxIfPrimitive(et), span);
    return et instanceof PrimType ? a.coerce(call, et, span) : call;
  }

  /** {@code (a, b) = expr}: evaluates the right side once, then assigns each element. */
  BExpr tupleAssign(Expr.Tuple t, Expr valueExpr, Span span) {
    List<BLValue> targets = new ArrayList<>();
    for (Arg x : t.elements()) {
      targets.add(
          x.value() instanceof Expr.Name n && n.name().equals("_") ? null : a.lvalue(x.value()));
    }
    BExpr rhs = a.value(valueExpr, null);
    VarSymbol tmp =
        env()
            .newVar(
                "$tuple", rhs.type(), Flags.FINAL | Flags.SYNTHETIC, VarSymbol.Kind.LOCAL, span);
    List<BStmt> stmts = new ArrayList<>();
    stmts.add(new BStmt.LocalDecl(tmp, rhs, span));
    List<BExpr> elems = elements(new BExpr.Local(tmp, span), targets.size(), span);
    if (elems == null) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    List<VarSymbol> temps = new ArrayList<>();
    for (int i = 0; i < elems.size(); i++) {
      VarSymbol e =
          env()
              .newVar(
                  "$e" + i,
                  elems.get(i).type(),
                  Flags.FINAL | Flags.SYNTHETIC,
                  VarSymbol.Kind.LOCAL,
                  span);
      stmts.add(new BStmt.LocalDecl(e, elems.get(i), span));
      temps.add(e);
    }
    for (int i = 0; i < targets.size(); i++) {
      BLValue lv = targets.get(i);
      if (lv == null) {
        continue;
      }
      BExpr v =
          a.coerce(new BExpr.Local(temps.get(i), span), lv.type(), t.elements().get(i).span());
      stmts.add(new BStmt.ExprStmt(new BExpr.Assign(lv, v, lv.type(), span), span));
      if (lv instanceof BLValue.LocalLV l && l.var().id() >= 0) {
        env().flow.assigned.set(l.var().id());
      }
    }
    return new BExpr.Block(stmts, new BExpr.Local(tmp, span), span);
  }

  /** The element values of a tuple/record/entry held in a local. */
  private List<BExpr> elements(BExpr value, int expected, Span span) {
    Type t = value.type();
    if (t.isError()) {
      return null;
    }
    List<Accessor> accs = positionalAccessors(t.withNullness(Nullness.NON_NULL), span);
    if (accs == null) {
      return null;
    }
    if (accs.size() != expected) {
      a.error(
          Code.INVALID_TUPLE_USE,
          span,
          "cannot deconstruct "
              + t.display()
              + " ("
              + accs.size()
              + " elements) into "
              + expected
              + " variables");
      return null;
    }
    if (t.nullness() == Nullness.NULLABLE) {
      a.error(Code.NULLABILITY_MISMATCH, span, "cannot deconstruct a nullable " + t.display());
    }
    List<BExpr> out = new ArrayList<>();
    for (int i = 0; i < accs.size(); i++) {
      if (t instanceof TupleType tt) {
        out.add(elementAccess(value, tt, i, span));
      } else {
        Accessor acc = accs.get(i);
        out.add(
            new BExpr.Call(
                value,
                acc.method(),
                List.of(),
                Attr.callKind(acc.method(), value, false),
                acc.type(),
                span));
      }
    }
    return out;
  }

  BStmt deconstruct(Stmt.Deconstruct d) {
    BExpr init = a.value(d.init(), null);
    VarSymbol tmp =
        env()
            .newVar(
                "$value",
                init.type(),
                Flags.FINAL | Flags.SYNTHETIC,
                VarSymbol.Kind.LOCAL,
                d.span());
    env().flow.assigned.set(tmp.id());
    List<BStmt> stmts = new ArrayList<>();
    stmts.add(new BStmt.LocalDecl(tmp, init, d.span()));
    stmts.addAll(destructureInto(d.vars(), new BExpr.Local(tmp, d.span()), d.kind(), d.span()));
    return new BStmt.Block(stmts, d.span());
  }

  /** Declares the deconstruction targets and initializes them from {@code value} (a local). */
  List<BStmt> destructureInto(List<DeconstructVar> vars, BExpr value, LocalKind kind, Span span) {
    List<BStmt> out = new ArrayList<>();
    List<BExpr> elems = elements(value, vars.size(), span);
    for (int i = 0; i < vars.size(); i++) {
      DeconstructVar dv = vars.get(i);
      BExpr elem =
          elems == null ? new BExpr.Error(Type.ErrorType.INSTANCE, dv.span()) : elems.get(i);
      if (dv.nested() != null) {
        VarSymbol tmp =
            env()
                .newVar(
                    "$nested",
                    elem.type(),
                    Flags.FINAL | Flags.SYNTHETIC,
                    VarSymbol.Kind.LOCAL,
                    dv.span());
        env().flow.assigned.set(tmp.id());
        out.add(new BStmt.LocalDecl(tmp, elem, dv.span()));
        out.addAll(destructureInto(dv.nested(), new BExpr.Local(tmp, dv.span()), kind, dv.span()));
        continue;
      }
      if (dv.name().equals("_")) {
        continue;
      }
      Type type = dv.type() != null ? a.resolveType(dv.type()) : a.types.uncapture(elem.type());
      BExpr init = a.coerce(elem, type, dv.span());
      VarSymbol v =
          a.stmts.declare(
              dv.name(),
              type,
              kind == LocalKind.VAL ? Flags.FINAL : 0,
              VarSymbol.Kind.LOCAL,
              dv.nameSpan());
      env().flow.assigned.set(v.id());
      out.add(new BStmt.LocalDecl(v, init, dv.span()));
    }
    return out;
  }

  // ------------------------------------------------------------------ with

  BExpr with(Expr.With w) {
    BExpr recv = a.value(w.target(), null);
    Type t = recv.type();
    if (t.isError()) {
      for (FieldInit fi : w.inits()) {
        a.value(fi.value(), null);
      }
      return recv;
    }
    if (!(t instanceof ClassType ct) || !ct.sym().isRecord()) {
      a.report(
          a.err(Code.INVALID_WITH, w.span(), "'with' needs a record, found " + t.display())
              .help("declare the type as a record"));
      return new BExpr.Error(t, w.span());
    }
    a.checkReceiverNullness(recv, w.target().span());
    ClassSymbol c = ct.sym();
    List<FieldSymbol> comps = c.recordComponents();
    MethodSymbol canonical = null;
    String desc = Descriptors.params(comps.stream().map(FieldSymbol::type).toList());
    for (MethodSymbol k : c.methods(MethodSymbol.CONSTRUCTOR)) {
      if (Descriptors.params(k.params().stream().map(MethodSymbol.Param::type).toList())
          .equals(desc)) {
        canonical = k;
      }
    }
    if (canonical == null) {
      a.error(Code.INVALID_WITH, w.span(), "record " + c.name() + " has no canonical constructor");
      return new BExpr.Error(t, w.span());
    }
    VarSymbol tmp =
        env()
            .newVar(
                "$with",
                t.withNullness(Nullness.NON_NULL),
                Flags.FINAL | Flags.SYNTHETIC,
                VarSymbol.Kind.LOCAL,
                w.span());
    BExpr[] args = new BExpr[comps.size()];
    for (FieldInit fi : w.inits()) {
      int idx = -1;
      for (int i = 0; i < comps.size(); i++) {
        if (comps.get(i).name().equals(fi.name())) {
          idx = i;
        }
      }
      if (idx < 0) {
        a.report(
            a.err(
                Code.INVALID_WITH,
                fi.nameSpan(),
                "record " + c.name() + " has no component '" + fi.name() + "'"));
        a.value(fi.value(), null);
        continue;
      }
      if (args[idx] != null) {
        a.error(Code.DUPLICATE_MEMBER, fi.nameSpan(), "'" + fi.name() + "' is set twice");
      }
      Type compType = a.memberType(ct, c, comps.get(idx).type());
      args[idx] = a.exprCoerced(fi.value(), compType, fi.value().span());
    }
    List<Accessor> accs = positionalAccessors(ct, w.span());
    List<BExpr> finalArgs = new ArrayList<>();
    for (int i = 0; i < comps.size(); i++) {
      if (args[i] != null) {
        finalArgs.add(args[i]);
      } else {
        Accessor acc = accs.get(i);
        finalArgs.add(
            new BExpr.Call(
                new BExpr.Local(tmp, w.span()),
                acc.method(),
                List.of(),
                BExpr.CallKind.VIRTUAL,
                acc.type(),
                w.span()));
      }
    }
    BExpr created =
        new BExpr.New(
            (ClassType) t.withNullness(Nullness.NON_NULL), canonical, finalArgs, w.span());
    return new BExpr.Let(tmp, recv, created, w.span());
  }

  // ------------------------------------------------------------------ ranges

  BExpr range(Expr.Range r) {
    a.report(
        a.err(Code.INVALID_RANGE, r.span(), "ranges are only valid inside an index, e.g. xs[1..3]")
            .help("to loop over numbers use 'for (int i = a; i < b; i++)'"));
    if (r.from() != null) {
      a.value(r.from(), null);
    }
    if (r.to() != null) {
      a.value(r.to(), null);
    }
    return new BExpr.Error(Type.ErrorType.INSTANCE, r.span());
  }

  /** {@code xs[^1]}, {@code xs[1..3]}, {@code s[..^1]} on arrays, strings and lists. */
  BExpr rangeIndex(BExpr recv, Expr.Index i) {
    Span span = i.span();
    Type t = recv.type();
    enum Kind {
      ARRAY,
      STRING,
      LIST
    }
    Kind kind;
    ClassSymbol list = a.syms.lookup("java/util/List");
    if (t instanceof Type.ArrayType) {
      kind = Kind.ARRAY;
    } else if (types().isSubclassOf(t, "java/lang/String")) {
      kind = Kind.STRING;
    } else if (types().asSuper(t, list) != null) {
      kind = Kind.LIST;
    } else {
      a.error(
          Code.INVALID_RANGE,
          span,
          "ranges and '^' indices work on arrays, strings and lists, not " + t.display());
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    VarSymbol tmp =
        env()
            .newVar(
                "$seq",
                t.withNullness(Nullness.NON_NULL),
                Flags.FINAL | Flags.SYNTHETIC,
                VarSymbol.Kind.LOCAL,
                span);
    BExpr seq = new BExpr.Local(tmp, span);
    BExpr length =
        switch (kind) {
          case ARRAY -> new BExpr.ArrayLength(seq, PrimType.INT, span);
          case STRING -> call(seq, "length", List.of(), PrimType.INT, span);
          case LIST -> call(seq, "size", List.of(), PrimType.INT, span);
        };
    BExpr body;
    if (i.index() instanceof Expr.Range r) {
      BExpr from =
          r.from() == null ? new BExpr.Const(0, PrimType.INT, span) : indexValue(r.from(), length);
      BExpr to = r.to() == null ? length : indexValue(r.to(), length);
      body =
          switch (kind) {
            case STRING -> call(seq, "substring", List.of(from, to), a.syms.stringType(), span);
            case LIST -> {
              Type elem =
                  types()
                      .uncapture(
                          a.memberType(
                              a.captureSite(t), list, list.typeParams().getFirst().asType()));
              Type result = new ClassType(list, List.of(elem), Nullness.NON_NULL);
              yield call(seq, "subList", List.of(from, to), result, span);
            }
            case ARRAY -> {
              ClassSymbol arrays = a.syms.lookup("java/util/Arrays");
              List<Calls.ArgInfo> argTypes =
                  List.of(
                      Calls.ArgInfo.ofType(t, span),
                      Calls.ArgInfo.ofType(PrimType.INT, span),
                      Calls.ArgInfo.ofType(PrimType.INT, span));
              Calls.Selected sel =
                  a.calls.select(
                      arrays.methods("copyOfRange"),
                      ClassType.of(arrays),
                      argTypes,
                      null,
                      null,
                      null,
                      span,
                      "copyOfRange",
                      true);
              yield sel == null
                  ? new BExpr.Error(t, span)
                  : new BExpr.Call(
                      null,
                      sel.method,
                      List.of(seq, from, to),
                      BExpr.CallKind.STATIC,
                      t.withNullness(Nullness.NON_NULL),
                      span);
            }
          };
    } else {
      BExpr idx = indexValue(i.index(), length);
      body =
          switch (kind) {
            case ARRAY -> new BExpr.ArrayElem(seq, idx, ((Type.ArrayType) t).elem(), span);
            case STRING -> call(seq, "charAt", List.of(idx), PrimType.CHAR, span);
            case LIST -> {
              Type elem =
                  types()
                      .uncapture(
                          a.memberType(
                              a.captureSite(t), list, list.typeParams().getFirst().asType()));
              yield call(seq, "get", List.of(idx), elem, span);
            }
          };
    }
    return new BExpr.Let(tmp, recv, body, span);
  }

  private BExpr indexValue(Expr e, BExpr length) {
    if (e instanceof Expr.Unary u && u.op() == Expr.UnaryOp.FROM_END) {
      BExpr n = a.exprCoerced(u.operand(), PrimType.INT);
      return new BExpr.Binary(BExpr.BinOp.SUB, length, n, PrimType.INT, false, e.span());
    }
    return a.exprCoerced(e, PrimType.INT);
  }

  private BExpr call(BExpr recv, String name, List<BExpr> args, Type type, Span span) {
    for (MethodSymbol m : a.lookup.findMethods(recv.type(), name)) {
      if (m.params().size() == args.size()
          && !m.isStatic()
          && (args.isEmpty() || m.params().getFirst().type() == PrimType.INT)) {
        return new BExpr.Call(recv, m, args, Attr.callKind(m, recv, false), type, span);
      }
    }
    throw new IllegalStateException("missing " + name + " on " + recv.type().display());
  }

  // ------------------------------------------------------------------ async

  BExpr await(Expr.Await aw) {
    Env env = env();
    if (!env.isAsync) {
      a.report(
          a.err(
                  Code.AWAIT_OUTSIDE_ASYNC,
                  aw.span(),
                  "'await' is only allowed in async methods, async lambdas and top-level statements")
              .help(env.method != null ? "mark the method 'async' and return Task<T>" : null));
    }
    BExpr v = a.value(aw.expr(), null);
    if (v.type().isError()) {
      return v;
    }
    ClassSymbol future = a.syms.lookup("java/util/concurrent/Future");
    ClassType asFuture = types().asSuper(v.type(), future);
    if (asFuture == null) {
      a.error(
          Code.TYPE_MISMATCH,
          aw.expr().span(),
          "'await' needs a Task or Future, found " + v.type().display());
      return new BExpr.Error(Type.ErrorType.INSTANCE, aw.span());
    }
    a.checkReceiverNullness(v, aw.expr().span());
    Type result =
        asFuture.args().isEmpty()
            ? a.syms.objectType().withNullness(Nullness.PLATFORM)
            : asFuture.args().getFirst();
    if (result instanceof Type.WildcardType w) {
      result = w.kind() == Type.WildcardType.Kind.EXTENDS ? w.bound() : a.syms.objectType();
    }
    if (result instanceof ClassType rc && rc.sym().binaryName().equals("java/lang/Void")) {
      result = PrimType.VOID;
    }
    MethodSymbol join = null;
    ClassSymbol task = a.syms.lookup("jsharp/core/Task");
    if (task != null) {
      for (MethodSymbol m : task.methods("await")) {
        if (m.isStatic() && m.params().size() == 1) {
          join = m;
        }
      }
    }
    if (join == null) {
      a.error(
          Code.INVALID_ASYNC,
          aw.span(),
          "'await' needs the J# runtime (jsharp.core.Task) on the class path");
      return new BExpr.Error(Type.ErrorType.INSTANCE, aw.span());
    }
    return new BExpr.Await(v, join, types().uncapture(result), aw.span());
  }

  /** The payload type {@code T} of an async function returning {@code Task<T>}. */
  Type asyncPayload(Type declared, Span span, String what) {
    if (declared == null) {
      return null;
    }
    if (declared == PrimType.VOID) {
      a.report(
          a.err(
                  Code.INVALID_ASYNC,
                  span,
                  "an async " + what + " must return Task<T> (or Task<Void>)")
              .help("declare the return type as Task<Void>"));
      return PrimType.VOID;
    }
    ClassSymbol task = a.syms.lookup("jsharp/core/Task");
    if (declared instanceof ClassType ct && task != null && ct.sym() == task) {
      Type arg = ct.args().isEmpty() ? a.syms.objectType() : ct.args().getFirst();
      if (arg instanceof ClassType ac && ac.sym().binaryName().equals("java/lang/Void")) {
        return PrimType.VOID;
      }
      return arg instanceof Type.WildcardType w && w.bound() != null ? w.bound() : arg;
    }
    if (!declared.isError()) {
      a.error(
          Code.INVALID_ASYNC,
          span,
          "an async " + what + " must return Task<T>, not " + declared.display());
    }
    return Type.ErrorType.INSTANCE;
  }

  /** Expected body result for an async lambda whose function type returns {@code ret}. */
  Type asyncLambdaResult(Type ret, Span span) {
    if (ret == null || ret instanceof Type.TypeVar tv && tv.sym().owner() == null) {
      return null;
    }
    ClassSymbol future = a.syms.lookup("java/util/concurrent/Future");
    ClassType asFuture = ret instanceof ClassType ? types().asSuper(ret, future) : null;
    if (asFuture == null) {
      if (!ret.isError()) {
        a.error(
            Code.INVALID_ASYNC,
            span,
            "an async lambda must produce a Task or Future, but " + ret.display() + " is expected");
      }
      return Type.ErrorType.INSTANCE;
    }
    Type arg = asFuture.args().isEmpty() ? a.syms.objectType() : asFuture.args().getFirst();
    if (arg instanceof Type.WildcardType w) {
      arg = w.bound() != null ? w.bound() : a.syms.objectType();
    }
    if (arg instanceof ClassType ac && ac.sym().binaryName().equals("java/lang/Void")) {
      return PrimType.VOID;
    }
    return arg instanceof Type.TypeVar tv && tv.sym().owner() == null ? null : arg;
  }

  /** {@code Task<R>} for an async lambda whose body produced {@code R} (for inference). */
  Type asyncResultForInference(Type result) {
    ClassSymbol task = a.syms.lookup("jsharp/core/Task");
    if (task == null) {
      return null;
    }
    Type arg =
        result == PrimType.VOID
            ? a.syms.wellKnown("java/lang/Void")
            : types().boxIfPrimitive(result);
    return new ClassType(task, List.of(arg), Nullness.NON_NULL);
  }

  /** Async returns are handled by the normal return path with the payload type. */
  BStmt asyncReturn(Stmt.Return r) {
    return null;
  }

  /** Element type for foreach over non-Iterable values via extensions (none yet). */
  Type iterableViaExtension(BExpr iterable, Span span) {
    return null;
  }

  // ------------------------------------------------------------------ local & anonymous classes

  private String nextLocalName(String suffix) {
    ClassSymbol owner = env().cls;
    int[] counter = a.anonCounters.computeIfAbsent(owner, k -> new int[1]);
    counter[0]++;
    return owner.binaryName() + "$" + counter[0] + suffix;
  }

  /** Whether code at this point has an enclosing instance ({@code this}). */
  private boolean hasThis() {
    return !env().isStatic;
  }

  BStmt localClass(Stmt.LocalType lt) {
    Decl.TypeDecl decl = lt.decl();
    Span span = lt.span();
    if (env().scope.classes.containsKey(decl.name())) {
      a.error(
          Code.DUPLICATE_TYPE,
          decl.nameSpan(),
          "local type '" + decl.name() + "' is already declared in this scope");
    }
    for (Decl m : decl.members()) {
      if (m instanceof Decl.TypeDecl nested) {
        a.error(
            Code.UNSUPPORTED_FEATURE, nested.nameSpan(), "local types cannot contain nested types");
      }
    }
    ClassSymbol c =
        new ClassSymbol(
            nextLocalName(decl.name()), decl.name(), env().cls.packageName(), a.memberEnter);
    long flags = Flags.SOURCE | Flags.LOCAL;
    Modifiers mods = decl.modifiers();
    switch (decl.kind()) {
      case CLASS -> {
        if (mods.has(Modifier.ABSTRACT)) {
          flags |= Flags.ABSTRACT;
        }
        if (mods.has(Modifier.OPEN)) {
          flags |= Flags.OPEN;
        }
        if (!mods.has(Modifier.OPEN) && !mods.has(Modifier.ABSTRACT)) {
          flags |= Flags.FINAL;
        }
        if (!hasThis()) {
          flags |= Flags.STATIC;
        }
      }
      case RECORD -> flags |= Flags.FINAL | Flags.RECORD | Flags.STATIC;
      case ENUM -> flags |= Flags.FINAL | Flags.ENUM | Flags.STATIC;
      case INTERFACE -> flags |= Flags.INTERFACE | Flags.ABSTRACT | Flags.STATIC;
    }
    for (Modifiers.Item item : mods.list()) {
      if (item.modifier() != Modifier.ABSTRACT
          && item.modifier() != Modifier.OPEN
          && item.modifier() != Modifier.FINAL) {
        a.error(
            Code.INVALID_MODIFIER,
            item.span(),
            "modifier '" + item.modifier().keyword() + "' is not allowed on a local type");
      }
    }
    c.setFlags(flags);
    c.setSource(decl, env().cls.outermost().unit());
    c.setOuter(env().cls);
    c.setSourceFileName(env().cls.sourceFileName());
    if (!a.isSpeculative()) {
      a.syms.enterSource(c);
    }
    env().scope.classes.put(decl.name(), c);
    a.memberEnter.setClassScope(
        c, new TypeScope.ClassLevel(c, a.typeScope(), !c.has(Flags.STATIC)));
    c.completeAll();
    BClass bc = ClassChecker.checkLocal(a, c, env().scope);
    if (!a.isSpeculative() && env().localClasses != null) {
      env().localClasses.add(bc);
    }
    return new BStmt.Empty(span);
  }

  BExpr anonymousClass(Expr.New n, ClassType base, Type pt) {
    Span span = n.span();
    ClassSymbol bs = base.sym();
    if (bs.isFinal() || bs.isEnum() || bs.isRecord()) {
      var d =
          a.err(
              Code.CANNOT_INHERIT_FINAL,
              n.type() != null ? n.type().span() : span,
              "cannot create an anonymous subclass of final "
                  + bs.kindName()
                  + " "
                  + bs.displayName());
      if (bs.isSource() && !bs.isEnum() && !bs.isRecord()) {
        d.help("declare it 'open class " + bs.name() + "'");
      }
      a.report(d);
      return new BExpr.Error(base, span);
    }
    ClassSymbol c = new ClassSymbol(nextLocalName(""), "", env().cls.packageName(), a.memberEnter);
    long flags = Flags.SOURCE | Flags.LOCAL | Flags.ANONYMOUS | Flags.FINAL;
    if (!hasThis()) {
      flags |= Flags.STATIC;
    }
    c.setFlags(flags);
    Decl.TypeDecl decl =
        new Decl.TypeDecl(
            Decl.TypeKind.CLASS,
            Modifiers.empty(span.start()),
            "",
            span,
            List.of(),
            null,
            List.of(),
            null,
            List.of(),
            n.anonBody(),
            span);
    c.setSource(decl, env().cls.outermost().unit());
    c.setOuter(env().cls);
    c.setSourceFileName(env().cls.sourceFileName());
    if (bs.isInterface()) {
      c.setSuperclass(a.syms.objectType());
      c.setInterfaces(List.of((ClassType) base.withNullness(Nullness.NON_NULL)));
    } else {
      c.setSuperclass((ClassType) base.withNullness(Nullness.NON_NULL));
      c.setInterfaces(List.of());
    }
    if (!a.isSpeculative()) {
      a.syms.enterSource(c);
    }
    a.memberEnter.setClassScope(
        c, new TypeScope.ClassLevel(c, a.typeScope(), !c.has(Flags.STATIC)));
    c.completeAll();
    // Constructor: mirrors the chosen superclass constructor.
    List<Calls.ArgInfo> infos = a.calls.prepare(n.args() == null ? List.of() : n.args());
    ClassType superType = c.superclass();
    Calls.Selected sel;
    if (bs.isInterface()) {
      if (!infos.isEmpty()) {
        a.error(
            Code.ARGUMENT_COUNT,
            span,
            "an anonymous class implementing an interface takes no constructor arguments");
      }
      sel =
          a.calls.select(
              a.syms.objectSym().methods(MethodSymbol.CONSTRUCTOR),
              a.syms.objectType(),
              List.of(),
              null,
              null,
              null,
              span,
              "Object",
              true);
    } else {
      // The implicit super(...) call is made by the anonymous subclass: protected constructors
      // of a superclass in another package are accessible.
      ClassSymbol savedFrom = a.calls.accessFrom;
      a.calls.accessFrom = c;
      try {
        sel =
            a.calls.select(
                bs.methods(MethodSymbol.CONSTRUCTOR),
                superType,
                infos,
                null,
                null,
                null,
                span,
                "constructor of " + bs.name(),
                true);
      } finally {
        a.calls.accessFrom = savedFrom;
      }
    }
    if (sel == null) {
      return new BExpr.Error(base, span);
    }
    MethodSymbol ctor = new MethodSymbol(MethodSymbol.CONSTRUCTOR, c, Flags.GENERATED);
    List<MethodSymbol.Param> ps = new ArrayList<>();
    for (int i = 0; i < sel.paramTypes.size(); i++) {
      ps.add(MethodSymbol.Param.of("arg" + i, sel.paramTypes.get(i)));
    }
    ctor.setParams(ps);
    ctor.setReturnType(PrimType.VOID);
    c.addMethod(ctor);
    superCtors.put(c, sel.method);
    BExpr created =
        a.calls.finish(sel, null, superType, bs.isInterface() ? List.of() : infos, span, false);
    List<BExpr> args = created instanceof BExpr.Call call ? call.args() : List.of();
    BClass bc = ClassChecker.checkLocal(a, c, env().scope);
    if (!a.isSpeculative() && env().localClasses != null) {
      env().localClasses.add(bc);
    }
    a.checkRequiredMembers(base, List.of(), span);
    BExpr nw = new BExpr.New(ClassType.of(c), ctor, args, span);
    return new BExpr.Conv(nw, ConvKind.RETYPE, base.withNullness(Nullness.NON_NULL), span);
  }

  /** Superclass constructor each anonymous class's constructor delegates to. */
  final java.util.Map<ClassSymbol, MethodSymbol> superCtors = new java.util.IdentityHashMap<>();

  /** Unused helper to keep imports tidy for future list patterns. */
  static Set<String> names(List<VarSymbol> vs) {
    Set<String> out = new LinkedHashSet<>();
    for (VarSymbol v : vs) {
      out.add(v.name());
    }
    return out;
  }
}

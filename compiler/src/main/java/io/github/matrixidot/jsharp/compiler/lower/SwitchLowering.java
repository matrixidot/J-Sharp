package io.github.matrixidot.jsharp.compiler.lower;

import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.bound.BExpr.BinOp;
import io.github.matrixidot.jsharp.compiler.bound.BExpr.CallKind;
import io.github.matrixidot.jsharp.compiler.bound.BExpr.ConvKind;
import io.github.matrixidot.jsharp.compiler.bound.BLValue;
import io.github.matrixidot.jsharp.compiler.bound.BPattern;
import io.github.matrixidot.jsharp.compiler.bound.BStmt;
import io.github.matrixidot.jsharp.compiler.bound.BSwitch;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
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
 * Lowers pattern switches and {@code is} patterns to tests, casts and jumps: type tests and
 * deconstruction of records and tuples, constants and enum labels (with an {@code int} switch where
 * cases allow), guards, and the bindings a match introduces.
 */
final class SwitchLowering {
  private final Lowerer l;

  SwitchLowering(Lowerer l) {
    this.l = l;
  }

  /**
   * Minimum number of constant labels for which an int switch compiles to {@code
   * tableswitch}/{@code lookupswitch}; below it, a compare chain is faster (D065).
   */
  static final int SWITCH_INSTRUCTION_THRESHOLD = Integer.getInteger("jsharp.switch.threshold", 20);

  /** Labels per case if {@code s} can be an int switch instruction, else null. */
  static List<List<Integer>> intSwitchKeys(BSwitch s) {
    if (!(s.selectorType() instanceof PrimType p)
        || !(p == PrimType.INT
            || p == PrimType.CHAR
            || p == PrimType.SHORT
            || p == PrimType.BYTE)) {
      return null;
    }
    List<List<Integer>> keys = new ArrayList<>();
    Set<Integer> seen = new HashSet<>();
    int total = 0;
    for (BSwitch.Case c : s.cases()) {
      if (c.pattern() == null) {
        continue;
      }
      List<Integer> ks = new ArrayList<>();
      if (c.guard() != null || !constantKeys(c.pattern(), ks)) {
        return null;
      }
      for (int k : ks) {
        if (!seen.add(k)) {
          return null;
        }
      }
      keys.add(ks);
      total += ks.size();
    }
    return total >= SWITCH_INSTRUCTION_THRESHOLD ? keys : null;
  }

  static boolean constantKeys(BPattern p, List<Integer> out) {
    return switch (p) {
      case BPattern.Constant c when c.value() instanceof BExpr.Const k -> {
        Object v = k.value();
        if (v instanceof Character ch) {
          out.add((int) ch);
          yield true;
        }
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
          out.add(((Number) v).intValue());
          yield true;
        }
        yield false;
      }
      case BPattern.Or or -> constantKeys(or.left(), out) && constantKeys(or.right(), out);
      default -> false;
    };
  }

  BStmt switchStmt(BStmt.Switch sw) {
    BSwitch s = sw.sw();
    Span span = sw.span();
    List<List<Integer>> intKeys = intSwitchKeys(s);
    if (intKeys != null) {
      List<BStmt> bodies = new ArrayList<>();
      BStmt dflt = null;
      for (BSwitch.Case c : s.cases()) {
        if (c.pattern() == null) {
          dflt = new BStmt.Block(l.lsAll(c.body()), span);
        } else {
          bodies.add(new BStmt.Block(l.lsAll(c.body()), span));
        }
      }
      return new BStmt.Labeled(
          sw.label(), new BStmt.IntSwitch(l.lx(s.selector()), intKeys, bodies, dflt, span), span);
    }
    VarSymbol sel = s.selectorVar();
    List<BStmt> out = new ArrayList<>();
    out.add(new BStmt.LocalDecl(sel, l.lx(s.selector()), span));
    BStmt chain = null;
    BStmt defaultBody = null;
    List<BSwitch.Case> cases = s.cases();
    for (BSwitch.Case c : cases) {
      if (c.pattern() == null) {
        defaultBody = new BStmt.Block(l.lsAll(c.body()), span);
      }
    }
    if (defaultBody == null && s.exhaustive() && !cases.isEmpty()) {
      defaultBody = new BStmt.Throw(matchException(span), span);
    }
    chain = defaultBody;
    for (int i = cases.size() - 1; i >= 0; i--) {
      BSwitch.Case c = cases.get(i);
      if (c.pattern() == null) {
        continue;
      }
      BExpr cond = match(c.pattern(), new BExpr.Local(sel, span), s.selectorType());
      if (c.guard() != null) {
        cond = and(cond, l.lx(c.guard()));
      }
      chain = new BStmt.If(cond, new BStmt.Block(l.lsAll(c.body()), span), chain, span);
    }
    if (chain != null) {
      out.add(chain);
    }
    return new BStmt.Labeled(sw.label(), new BStmt.Block(out, span), span);
  }

  BExpr switchExpr(BExpr.Switch sx) {
    BSwitch s = sx.sw();
    Span span = sx.span();
    List<List<Integer>> intKeys = intSwitchKeys(s);
    if (intKeys != null) {
      List<BExpr> values = new ArrayList<>();
      BExpr dflt = null;
      for (BSwitch.Case c : s.cases()) {
        if (c.pattern() == null) {
          dflt = l.adapt(l.lx(c.value()), sx.type());
        } else {
          values.add(l.adapt(l.lx(c.value()), sx.type()));
        }
      }
      if (dflt == null) {
        dflt = new BExpr.Throw(matchException(span), sx.type(), span);
      }
      return new BExpr.IntSwitch(l.lx(s.selector()), intKeys, values, dflt, sx.type(), span);
    }
    VarSymbol sel = s.selectorVar();
    BExpr chain = null;
    for (BSwitch.Case c : s.cases()) {
      if (c.pattern() == null) {
        chain = l.adapt(l.lx(c.value()), sx.type());
      }
    }
    if (chain == null) {
      chain = new BExpr.Throw(matchException(span), sx.type(), span);
    }
    List<BSwitch.Case> cases = s.cases();
    for (int i = cases.size() - 1; i >= 0; i--) {
      BSwitch.Case c = cases.get(i);
      if (c.pattern() == null) {
        continue;
      }
      BExpr cond = match(c.pattern(), new BExpr.Local(sel, span), s.selectorType());
      if (c.guard() != null) {
        cond = and(cond, l.lx(c.guard()));
      }
      chain =
          new BExpr.Conditional(cond, l.adapt(l.lx(c.value()), sx.type()), chain, sx.type(), span);
    }
    return new BExpr.Let(sel, l.lx(s.selector()), chain, span);
  }

  BExpr matchException(Span span) {
    ClassSymbol me = l.syms.lookup("java/lang/MatchException");
    MethodSymbol ctor = me.methods(MethodSymbol.CONSTRUCTOR).getFirst();
    return new BExpr.New(
        ClassType.of(me), ctor, List.of(Lowerer.nullConst(span), Lowerer.nullConst(span)), span);
  }

  static BExpr and(BExpr a, BExpr b) {
    if (a instanceof BExpr.Const c && Boolean.TRUE.equals(c.value())) {
      return b;
    }
    return new BExpr.Binary(BinOp.COND_AND, a, b, PrimType.BOOLEAN, false, a.span());
  }

  static BExpr or(BExpr a, BExpr b) {
    return new BExpr.Binary(BinOp.COND_OR, a, b, PrimType.BOOLEAN, false, a.span());
  }

  static BExpr trueConst(Span span) {
    return new BExpr.Const(true, PrimType.BOOLEAN, span);
  }

  /** Assigns a binding and yields true (usable inside conditions). */
  BExpr bind(VarSymbol v, BExpr value, Span span) {
    BStmt assign =
        new BStmt.ExprStmt(
            new BExpr.Assign(new BLValue.LocalLV(v), l.adapt(value, v.type()), v.type(), span),
            span);
    return new BExpr.Block(List.of(assign), trueConst(span), span);
  }

  /**
   * A boolean expression testing pattern {@code p} against {@code subject} (a side-effect free
   * expression of type {@code type}), assigning bindings when it matches.
   */
  BExpr match(BPattern p, BExpr subject, Type type) {
    Span span = p.span();
    return switch (p) {
      case BPattern.Any any ->
          any.binding() == null ? trueConst(span) : bind(any.binding(), subject, span);
      case BPattern.TypeTest tt -> {
        Type target = tt.type();
        BExpr test = typeTest(subject, type, target, span);
        if (tt.binding() == null) {
          yield test;
        }
        BExpr cast = castTo(subject, type, target, span);
        yield and(test, bind(tt.binding(), cast, span));
      }
      case BPattern.Constant c -> constantTest(subject, type, l.lx(c.value()), span);
      case BPattern.Relational r -> {
        BExpr v = subject;
        BExpr nonNull = null;
        if (!(type instanceof PrimType)) {
          nonNull =
              new BExpr.Binary(
                  BinOp.REF_NE, subject, Lowerer.nullConst(span), PrimType.BOOLEAN, false, span);
        }
        BExpr cmp =
            new BExpr.Binary(
                r.op(),
                l.adapt(v, r.operandType()),
                l.lx(r.value()),
                PrimType.BOOLEAN,
                false,
                span);
        yield nonNull == null ? cmp : and(nonNull, cmp);
      }
      case BPattern.Recursive r -> {
        Type t = r.type() == null ? type : r.type();
        BExpr test = typeTest(subject, type, t, span);
        VarSymbol tmp = l.temp("rec", t.withNullness(Nullness.NON_NULL), span);
        BExpr body = trueConst(span);
        // Build sub-matches from the last one backwards so each accessor result is bound once.
        for (int i = r.subpatterns().size() - 1; i >= 0; i--) {
          MethodSymbol acc = r.accessors().get(i);
          Type accType = r.accessorTypes().get(i);
          BExpr call =
              new BExpr.Call(
                  new BExpr.Local(tmp, span),
                  acc,
                  List.of(),
                  acc.owner().isInterface() ? CallKind.INTERFACE : CallKind.VIRTUAL,
                  accType,
                  span);
          BPattern sub = r.subpatterns().get(i);
          if (sub instanceof BPattern.Any any && any.binding() == null) {
            continue;
          }
          VarSymbol v = l.temp("c", accType, span);
          BExpr subMatch = match(sub, new BExpr.Local(v, span), accType);
          body = new BExpr.Let(v, call, and(subMatch, body), span);
        }
        if (r.binding() != null) {
          body = and(bind(r.binding(), new BExpr.Local(tmp, span), span), body);
        }
        BExpr cast = castTo(subject, type, t, span);
        yield and(test, new BExpr.Let(tmp, cast, body, span));
      }
      case BPattern.ListPat lp -> listMatch(lp, subject, span);
      case BPattern.And a -> and(match(a.left(), subject, type), match(a.right(), subject, type));
      case BPattern.Or o -> or(match(o.left(), subject, type), match(o.right(), subject, type));
      case BPattern.Not n ->
          new BExpr.Unary(
              BExpr.UnOp.NOT, match(n.pattern(), subject, type), PrimType.BOOLEAN, span);
    };
  }

  /**
   * {@code subject is [p0, .., q0]}: non-null, length check (exact, or a minimum with {@code ..}),
   * then each element read once by index (discards are not read) and the slice as {@code
   * subList}/{@code Arrays.copyOfRange}.
   */
  BExpr listMatch(BPattern.ListPat lp, BExpr subject, Span span) {
    int k = lp.prefix().size();
    int m = lp.suffix().size();
    VarSymbol size = l.temp("size", PrimType.INT, span);
    ClassSymbol list = l.syms.lookup("java/util/List");
    BExpr body = trueConst(span);
    if (lp.slice() != null && !(lp.slice() instanceof BPattern.Any any && any.binding() == null)) {
      BExpr from = new BExpr.Const(k, PrimType.INT, span);
      BExpr to = minus(new BExpr.Local(size, span), m, span);
      BExpr sliceExpr;
      if (lp.array()) {
        sliceExpr = copyOfRange(subject, (Type.ArrayType) lp.sliceType(), from, to, span);
      } else {
        MethodSymbol subList = listMethod(list, "subList", 2);
        sliceExpr =
            new BExpr.Call(
                subject, subList, List.of(from, to), CallKind.INTERFACE, lp.sliceType(), span);
      }
      VarSymbol sv = l.temp("slice", lp.sliceType(), span);
      body =
          new BExpr.Let(
              sv,
              sliceExpr,
              and(match(lp.slice(), new BExpr.Local(sv, span), lp.sliceType()), body),
              span);
    }
    for (int j = m - 1; j >= 0; j--) {
      body =
          elementMatch(
              lp,
              list,
              subject,
              lp.suffix().get(j),
              minus(new BExpr.Local(size, span), m - j, span),
              body,
              span);
    }
    for (int i = k - 1; i >= 0; i--) {
      body =
          elementMatch(
              lp,
              list,
              subject,
              lp.prefix().get(i),
              new BExpr.Const(i, PrimType.INT, span),
              body,
              span);
    }
    if (lp.binding() != null) {
      body = and(bind(lp.binding(), subject, span), body);
    }
    BExpr sizeExpr =
        lp.array()
            ? new BExpr.ArrayLength(subject, PrimType.INT, span)
            : new BExpr.Call(
                subject,
                listMethod(list, "size", 0),
                List.of(),
                CallKind.INTERFACE,
                PrimType.INT,
                span);
    BExpr lenTest =
        new BExpr.Binary(
            lp.hasSlice() ? BinOp.GE : BinOp.EQ,
            new BExpr.Local(size, span),
            new BExpr.Const(k + m, PrimType.INT, span),
            PrimType.BOOLEAN,
            false,
            span);
    BExpr nonNull =
        new BExpr.Binary(
            BinOp.REF_NE, subject, Lowerer.nullConst(span), PrimType.BOOLEAN, false, span);
    return and(nonNull, new BExpr.Let(size, sizeExpr, and(lenTest, body), span));
  }

  BExpr elementMatch(
      BPattern.ListPat lp,
      ClassSymbol list,
      BExpr subject,
      BPattern sub,
      BExpr index,
      BExpr body,
      Span span) {
    if (sub instanceof BPattern.Any any && any.binding() == null) {
      return body; // `_`: the element is not read
    }
    Type et = lp.elementType();
    BExpr access =
        lp.array()
            ? new BExpr.ArrayElem(subject, index, et, span)
            : new BExpr.Call(
                subject, listMethod(list, "get", 1), List.of(index), CallKind.INTERFACE, et, span);
    VarSymbol v = l.temp("e", et, span);
    return new BExpr.Let(v, access, and(match(sub, new BExpr.Local(v, span), et), body), span);
  }

  static BExpr minus(BExpr a, int n, Span span) {
    if (n == 0) {
      return a;
    }
    return new BExpr.Binary(
        BinOp.SUB, a, new BExpr.Const(n, PrimType.INT, span), PrimType.INT, false, span);
  }

  static MethodSymbol listMethod(ClassSymbol list, String name, int arity) {
    for (MethodSymbol m : list.methods(name)) {
      if (m.params().size() == arity && !m.isStatic()) {
        return m;
      }
    }
    throw new IllegalStateException("java.util.List." + name);
  }

  /** {@code Arrays.copyOfRange(array, from, to)} for primitive or reference arrays. */
  BExpr copyOfRange(BExpr array, Type.ArrayType at, BExpr from, BExpr to, Span span) {
    ClassSymbol arrays = l.syms.lookup("java/util/Arrays");
    for (MethodSymbol m : arrays.methods("copyOfRange")) {
      if (m.params().size() != 3 || !(m.params().getFirst().type() instanceof Type.ArrayType pa)) {
        continue;
      }
      boolean fits =
          at.elem() instanceof PrimType p ? pa.elem() == p : !(pa.elem() instanceof PrimType);
      if (fits) {
        return new BExpr.Call(null, m, List.of(array, from, to), CallKind.STATIC, at, span);
      }
    }
    throw new IllegalStateException("Arrays.copyOfRange for " + at.display());
  }

  /** {@code subject instanceof target}, simplified when statically known. */
  BExpr typeTest(BExpr subject, Type subjectType, Type target, Span span) {
    if (subjectType instanceof PrimType sp) {
      PrimType tp = l.types.primitiveView(target);
      return new BExpr.Const(
          sp == tp
              || tp == null
              || target.isReference() && l.types.isSubtype(l.syms.boxed(sp), target),
          PrimType.BOOLEAN,
          span);
    }
    if (l.types.isSubtype(subjectType.erasure(), target.erasure())) {
      return new BExpr.Binary(
          BinOp.REF_NE, subject, Lowerer.nullConst(span), PrimType.BOOLEAN, false, span);
    }
    Type erased = target instanceof PrimType p ? l.syms.boxed(p) : target.erasure();
    return new BExpr.InstanceOf(subject, erased, PrimType.BOOLEAN, span);
  }

  BExpr castTo(BExpr subject, Type subjectType, Type target, Span span) {
    if (target instanceof PrimType tp) {
      return l.adapt(subject, tp);
    }
    if (subjectType instanceof PrimType) {
      return l.adapt(subject, target);
    }
    if (l.types.isSubtype(subjectType.erasure(), target.erasure())) {
      return new BExpr.Conv(subject, ConvKind.RETYPE, target.withNullness(Nullness.NON_NULL), span);
    }
    return new BExpr.Conv(
        subject, ConvKind.CHECKCAST, target.withNullness(Nullness.NON_NULL), span);
  }

  BExpr constantTest(BExpr subject, Type type, BExpr value, Span span) {
    if (value.type() instanceof Type.NullType) {
      if (type instanceof PrimType) {
        return new BExpr.Const(false, PrimType.BOOLEAN, span);
      }
      return new BExpr.Binary(BinOp.REF_EQ, subject, value, PrimType.BOOLEAN, false, span);
    }
    if (value instanceof BExpr.Field f && f.field().has(Flags.ENUM_CONSTANT)) {
      return new BExpr.Binary(BinOp.REF_EQ, subject, value, PrimType.BOOLEAN, false, span);
    }
    PrimType vp = l.types.primitiveView(value.type());
    if (type instanceof PrimType tp) {
      PrimType op = vp == null ? tp : Types.promote(tp, vp);
      if (tp == PrimType.BOOLEAN) {
        op = PrimType.BOOLEAN;
      }
      return new BExpr.Binary(
          BinOp.EQ, l.adapt(subject, op), l.adapt(value, op), PrimType.BOOLEAN, false, span);
    }
    if (vp != null && value.type() instanceof PrimType) {
      // Boxed or Object subject against a primitive constant: box the constant, compare by equals.
      value = l.adapt(value, l.syms.boxed(vp));
    }
    return objectsEquals(value, subject, span);
  }

  BExpr objectsEquals(BExpr a, BExpr b, Span span) {
    ClassSymbol objects = l.syms.lookup("java/util/Objects");
    MethodSymbol equals = null;
    for (MethodSymbol m : objects.methods("equals")) {
      if (m.params().size() == 2) {
        equals = m;
      }
    }
    return new BExpr.Call(null, equals, List.of(a, b), CallKind.STATIC, PrimType.BOOLEAN, span);
  }
}

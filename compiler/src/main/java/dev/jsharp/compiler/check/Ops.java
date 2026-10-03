package dev.jsharp.compiler.check;

import dev.jsharp.compiler.ast.Expr;
import dev.jsharp.compiler.bound.BExpr;
import dev.jsharp.compiler.bound.BExpr.BinOp;
import dev.jsharp.compiler.bound.BExpr.ConvKind;
import dev.jsharp.compiler.bound.BLValue;
import dev.jsharp.compiler.diag.Code;
import dev.jsharp.compiler.source.Span;
import dev.jsharp.compiler.symbols.ClassSymbol;
import dev.jsharp.compiler.symbols.FieldSymbol;
import dev.jsharp.compiler.symbols.Flags;
import dev.jsharp.compiler.symbols.MethodSymbol;
import dev.jsharp.compiler.symbols.PropertySymbol;
import dev.jsharp.compiler.symbols.VarSymbol;
import dev.jsharp.compiler.types.Nullness;
import dev.jsharp.compiler.types.Type;
import dev.jsharp.compiler.types.Type.ClassType;
import dev.jsharp.compiler.types.Type.PrimType;
import dev.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.List;

/** Typing of operators, assignments and assignable locations. */
final class Ops {
  private final Attr a;

  Ops(Attr a) {
    this.a = a;
  }

  private Types types() {
    return a.types;
  }

  // ------------------------------------------------------------------ unary

  BExpr unary(Expr.Unary u, Type pt) {
    Span span = u.span();
    switch (u.op()) {
      case NOT -> {
        BExpr r = a.condition(u);
        FlowState j = a.whenTrue.copy();
        j.join(a.whenFalse);
        a.env.flow.set(j);
        return r;
      }
      case NEG, PLUS, BIT_NOT -> {
        if (u.op() == Expr.UnaryOp.NEG && u.operand() instanceof Expr.Literal lit) {
          BExpr n = a.negatedLiteral(lit);
          if (n != null) {
            return n;
          }
        }
        BExpr e = a.value(u.operand(), null);
        if (e.type().isError()) {
          return e;
        }
        PrimType p = types().primitiveView(e.type());
        boolean ok =
            p != null && p.isNumeric() && (u.op() != Expr.UnaryOp.BIT_NOT || p.isIntegral());
        if (!ok) {
          a.error(
              Code.BAD_OPERANDS,
              span,
              "operator '" + u.op().symbol() + "' cannot be applied to " + e.type().display());
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        PrimType t = Types.promote(p);
        e = a.coerce(e, t, u.operand().span());
        if (u.op() == Expr.UnaryOp.PLUS) {
          return e;
        }
        BExpr.UnOp op = u.op() == Expr.UnaryOp.NEG ? BExpr.UnOp.NEG : BExpr.UnOp.BIT_NOT;
        Object k = ConstFold.valueOf(e);
        if (k != null) {
          Object folded = ConstFold.unary(op, k);
          if (folded != null) {
            return new BExpr.Const(folded, t, span);
          }
        }
        if (op == BExpr.UnOp.NEG && a.env.checked && (t == PrimType.INT || t == PrimType.LONG)) {
          return new BExpr.Binary(
              BinOp.SUB,
              new BExpr.Const(t == PrimType.INT ? (Object) 0 : (Object) 0L, t, span),
              e,
              t,
              true,
              span);
        }
        return new BExpr.Unary(op, e, t, span);
      }
      case PRE_INC, PRE_DEC, POST_INC, POST_DEC -> {
        BLValue lv = lvalue(u.operand());
        requireAssigned(lv, u.operand().span());
        // A nullable local narrowed to non-null (int? n = 9; n++) may be incremented, and stays
        // non-null afterwards.
        boolean narrowedNonNull =
            lv instanceof BLValue.LocalLV l
                && a.env.flow.narrowed.get(l.var()) instanceof Type nt
                && nt.nullness() == Nullness.NON_NULL;
        afterAssign(lv, null);
        if (narrowedNonNull) {
          VarSymbol nv = ((BLValue.LocalLV) lv).var();
          a.env.flow.narrowed.put(nv, nv.type().withNullness(Nullness.NON_NULL));
        }
        Type t = lv.type();
        if (t.isError()) {
          return new BExpr.Error(t, span);
        }
        PrimType p = types().primitiveView(t);
        if (p == null || !p.isNumeric()) {
          a.error(
              Code.BAD_OPERANDS,
              span,
              "operator '" + u.op().symbol() + "' cannot be applied to " + t.display());
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        if (t.nullness() == Nullness.NULLABLE && !narrowedNonNull) {
          a.error(Code.NULLABILITY_MISMATCH, span, "cannot increment nullable " + t.display());
        }
        boolean inc = u.op() == Expr.UnaryOp.PRE_INC || u.op() == Expr.UnaryOp.POST_INC;
        boolean prefix = u.op() == Expr.UnaryOp.PRE_INC || u.op() == Expr.UnaryOp.PRE_DEC;
        return new BExpr.IncDec(lv, inc, prefix, t, a.env.checked, span);
      }
      case NON_NULL -> {
        BExpr e = a.value(u.operand(), pt);
        Type t = e.type();
        if (t.isError()) {
          return e;
        }
        if (t instanceof PrimType
            || t.nullness() == Nullness.NON_NULL && !(t instanceof Type.NullType)) {
          if (!a.isSpeculative()) {
            a.warn(
                Code.REDUNDANT_NON_NULL_ASSERTION,
                span,
                "'!' is redundant: " + t.display() + " is never null");
          }
          return e;
        }
        if (t instanceof Type.NullType) {
          a.error(Code.NULLABILITY_MISMATCH, span, "'null!' always throws");
          return new BExpr.Throw(e, Type.NeverType.INSTANCE, span);
        }
        Type nn = t.withNullness(Nullness.NON_NULL);
        VarSymbol v = Attr.localOf(e);
        if (v != null) {
          a.env.flow.narrowed.put(v, nn);
        }
        return new BExpr.Conv(e, ConvKind.NON_NULL_ASSERT, nn, span);
      }
      case FROM_END -> {
        a.error(
            Code.INVALID_RANGE,
            span,
            "'^' (index from end) is only valid inside an index, e.g. xs[^1]");
        a.value(u.operand(), null);
        return new BExpr.Error(Type.ErrorType.INSTANCE, span);
      }
    }
    throw new IllegalStateException(u.op().toString());
  }

  // ------------------------------------------------------------------ binary

  BExpr binary(Expr.Binary b, Type pt) {
    Span span = b.span();
    switch (b.op()) {
      case AND, OR -> {
        BExpr r = a.condition(b);
        FlowState j = a.whenTrue.copy();
        j.join(a.whenFalse);
        a.env.flow.set(j);
        return r;
      }
      case COALESCE -> {
        return coalesce(b, pt);
      }
      case EQ, NE, REF_EQ, REF_NE -> {
        return equality(b);
      }
      default -> {}
    }
    BExpr left = a.value(b.left(), null);
    BExpr right = a.value(b.right(), null);
    if (left.type().isError() || right.type().isError()) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    return arithmetic(b.op(), left, right, span, b.left().span(), b.right().span());
  }

  private static BinOp binOp(Expr.BinaryOp op) {
    return switch (op) {
      case ADD -> BinOp.ADD;
      case SUB -> BinOp.SUB;
      case MUL -> BinOp.MUL;
      case DIV -> BinOp.DIV;
      case REM -> BinOp.REM;
      case SHL -> BinOp.SHL;
      case SHR -> BinOp.SHR;
      case USHR -> BinOp.USHR;
      case BIT_AND -> BinOp.AND;
      case BIT_OR -> BinOp.OR;
      case BIT_XOR -> BinOp.XOR;
      case LT -> BinOp.LT;
      case GT -> BinOp.GT;
      case LE -> BinOp.LE;
      case GE -> BinOp.GE;
      case EQ -> BinOp.EQ;
      case NE -> BinOp.NE;
      case REF_EQ -> BinOp.REF_EQ;
      case REF_NE -> BinOp.REF_NE;
      case AND -> BinOp.COND_AND;
      case OR -> BinOp.COND_OR;
      case COALESCE -> throw new IllegalArgumentException();
    };
  }

  private boolean isString(Type t) {
    return t instanceof ClassType c && c.sym().binaryName().equals("java/lang/String");
  }

  /** Arithmetic, shift, bitwise, relational and string-concatenation operators. */
  BExpr arithmetic(Expr.BinaryOp syntaxOp, BExpr left, BExpr right, Span span, Span ls, Span rs) {
    BinOp op = binOp(syntaxOp);
    Type lt = left.type();
    Type rt = right.type();
    if (op == BinOp.ADD && (isString(lt) || isString(rt))) {
      if (lt == PrimType.VOID || rt == PrimType.VOID) {
        a.error(Code.VOID_VALUE, span, "a void expression has no value");
      }
      List<BExpr> parts = new ArrayList<>();
      addConcatParts(parts, left);
      addConcatParts(parts, right);
      if (parts.stream().allMatch(ConstFold::isConst)
          && parts.stream().allMatch(p -> !(p.type() instanceof Type.NullType))) {
        StringBuilder sb = new StringBuilder();
        for (BExpr p : parts) {
          sb.append(ConstFold.valueOf(p));
        }
        return new BExpr.Const(sb.toString(), a.syms.stringType(), span);
      }
      return new BExpr.Concat(parts, a.syms.stringType(), span);
    }
    PrimType lp = types().primitiveView(lt);
    PrimType rp = types().primitiveView(rt);
    String sym = syntaxOp.symbol();
    if (lp == null || rp == null) {
      reportBadOperands(sym, lt, rt, span);
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    left = checkUnboxNullable(left, ls);
    right = checkUnboxNullable(right, rs);
    PrimType opType;
    Type result;
    switch (op) {
      case ADD, SUB, MUL, DIV, REM -> {
        if (!lp.isNumeric() || !rp.isNumeric()) {
          reportBadOperands(sym, lt, rt, span);
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        opType = Types.promote(lp, rp);
        result = opType;
      }
      case SHL, SHR, USHR -> {
        if (!lp.isIntegral() || !rp.isIntegral()) {
          reportBadOperands(sym, lt, rt, span);
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        opType = Types.promote(lp);
        result = opType;
        BExpr l2 = a.coerce(left, opType, ls);
        BExpr r2 = a.coerce(right, Types.promote(rp), rs);
        if (r2.type() == PrimType.LONG) {
          r2 = a.primConv(r2, PrimType.INT, rs);
        }
        return fold(new BExpr.Binary(op, l2, r2, result, false, span));
      }
      case AND, OR, XOR -> {
        if (lp == PrimType.BOOLEAN && rp == PrimType.BOOLEAN) {
          opType = PrimType.BOOLEAN;
        } else if (lp.isIntegral() && rp.isIntegral()) {
          opType = Types.promote(lp, rp);
        } else {
          reportBadOperands(sym, lt, rt, span);
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        result = opType;
      }
      case LT, GT, LE, GE -> {
        if (!lp.isNumeric() || !rp.isNumeric()) {
          reportBadOperands(sym, lt, rt, span);
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        opType = Types.promote(lp, rp);
        result = PrimType.BOOLEAN;
      }
      default -> throw new IllegalStateException(op.toString());
    }
    BExpr l2 = a.coerce(left, opType, ls);
    BExpr r2 = a.coerce(right, opType, rs);
    boolean checked =
        a.env.checked
            && (opType == PrimType.INT || opType == PrimType.LONG)
            && (op == BinOp.ADD || op == BinOp.SUB || op == BinOp.MUL);
    if (checked && ConstFold.isConst(l2) && ConstFold.isConst(r2)) {
      try {
        Object v = checkedFold(op, ConstFold.valueOf(l2), ConstFold.valueOf(r2), opType);
        return new BExpr.Const(v, result, span);
      } catch (ArithmeticException ex) {
        a.error(
            Code.LITERAL_OUT_OF_RANGE, span, "constant expression overflows in a checked context");
        return new BExpr.Error(result, span);
      }
    }
    return fold(new BExpr.Binary(op, l2, r2, result, checked, span));
  }

  private static Object checkedFold(BinOp op, Object x, Object y, PrimType t) {
    if (t == PrimType.INT) {
      int i = ((Number) x).intValue();
      int j = ((Number) y).intValue();
      return switch (op) {
        case ADD -> Math.addExact(i, j);
        case SUB -> Math.subtractExact(i, j);
        default -> Math.multiplyExact(i, j);
      };
    }
    long i = ((Number) x).longValue();
    long j = ((Number) y).longValue();
    return switch (op) {
      case ADD -> Math.addExact(i, j);
      case SUB -> Math.subtractExact(i, j);
      default -> Math.multiplyExact(i, j);
    };
  }

  /** Reports a nullable operand that must be unboxed; returns it retyped as non-null. */
  private BExpr checkUnboxNullable(BExpr e, Span s) {
    Type t = e.type();
    if (t.isReference() && t.nullness() == Nullness.NULLABLE) {
      a.report(
          a.err(Code.NULLABILITY_MISMATCH, s, "operand of type " + t.display() + " may be null")
              .help("use '!' or '??' to provide a non-null value"));
      return new BExpr.Conv(e, ConvKind.RETYPE, t.withNullness(Nullness.NON_NULL), s);
    }
    return e;
  }

  private void addConcatParts(List<BExpr> parts, BExpr e) {
    if (e instanceof BExpr.Concat c) {
      parts.addAll(c.parts());
    } else {
      parts.add(e);
    }
  }

  private void reportBadOperands(String op, Type l, Type r, Span span) {
    a.error(
        Code.BAD_OPERANDS,
        span,
        "operator '" + op + "' cannot be applied to " + l.display() + " and " + r.display());
  }

  BExpr fold(BExpr.Binary b) {
    Object l = ConstFold.valueOf(b.left());
    Object r = ConstFold.valueOf(b.right());
    if (l != null && r != null && !b.checked()) {
      Object v = ConstFold.binary(b.op(), l, r, b.left().type());
      if (v != null) {
        return new BExpr.Const(
            v instanceof Boolean ? v : ConstFold.convert(v, (PrimType) b.type()),
            b.type(),
            b.span());
      }
    }
    return b;
  }

  // ------------------------------------------------------------------ equality

  BExpr equality(Expr.Binary b) {
    Span span = b.span();
    boolean negate = b.op() == Expr.BinaryOp.NE || b.op() == Expr.BinaryOp.REF_NE;
    boolean identity = b.op() == Expr.BinaryOp.REF_EQ || b.op() == Expr.BinaryOp.REF_NE;
    BExpr left = a.value(b.left(), null);
    BExpr right = a.value(b.right(), null);
    Type lt = left.type();
    Type rt = right.type();
    if (lt.isError() || rt.isError()) {
      return new BExpr.Error(PrimType.BOOLEAN, span);
    }
    boolean lnull = lt instanceof Type.NullType;
    boolean rnull = rt instanceof Type.NullType;
    // Null checks: reference comparison, with smart-cast narrowing.
    if (lnull || rnull) {
      BExpr other = lnull ? right : left;
      if (other.type() instanceof PrimType) {
        a.error(
            Code.BAD_OPERANDS,
            span,
            "a value of primitive type " + other.type().display() + " is never null");
        return new BExpr.Error(PrimType.BOOLEAN, span);
      }
      BExpr cmp =
          new BExpr.Binary(
              negate ? BinOp.REF_NE : BinOp.REF_EQ, left, right, PrimType.BOOLEAN, false, span);
      VarSymbol v = Attr.localOf(other);
      FlowState t = a.env.flow.copy();
      FlowState f = a.env.flow.copy();
      if (v != null && other.type().nullness() != Nullness.NON_NULL) {
        Type nn = other.type().withNullness(Nullness.NON_NULL);
        (negate ? t : f).narrowed.put(v, nn);
      }
      a.narrowCondition(cmp, t, f);
      return cmp;
    }
    PrimType lp = types().primitiveView(lt);
    PrimType rp = types().primitiveView(rt);
    if (identity) {
      if (lt instanceof PrimType || rt instanceof PrimType) {
        a.report(
            a.err(
                Code.BAD_OPERANDS,
                span,
                "'"
                    + b.op().symbol()
                    + "' compares references; use '"
                    + (negate ? "!=" : "==")
                    + "' for primitive values"));
        return new BExpr.Error(PrimType.BOOLEAN, span);
      }
      checkComparable(lt, rt, span, b.op().symbol());
      return new BExpr.Binary(
          negate ? BinOp.REF_NE : BinOp.REF_EQ, left, right, PrimType.BOOLEAN, false, span);
    }
    // Numeric/boolean comparison when at least one side is primitive and both have primitive views.
    boolean primitiveCompare =
        (lt instanceof PrimType || rt instanceof PrimType)
            && lp != null
            && rp != null
            && !(lt.nullness() == Nullness.NULLABLE || rt.nullness() == Nullness.NULLABLE);
    if (primitiveCompare) {
      PrimType opType;
      if (lp == PrimType.BOOLEAN || rp == PrimType.BOOLEAN) {
        if (lp != rp) {
          reportBadOperands(b.op().symbol(), lt, rt, span);
          return new BExpr.Error(PrimType.BOOLEAN, span);
        }
        opType = PrimType.BOOLEAN;
      } else {
        opType = Types.promote(lp, rp);
      }
      BExpr l2 = a.coerce(left, opType, b.left().span());
      BExpr r2 = a.coerce(right, opType, b.right().span());
      return fold(
          new BExpr.Binary(negate ? BinOp.NE : BinOp.EQ, l2, r2, PrimType.BOOLEAN, false, span));
    }
    // Value equality on references (null-safe equals); box a primitive side.
    if (lt instanceof PrimType p) {
      left = a.coerce(left, a.syms.boxed(p), b.left().span());
      lt = left.type();
    }
    if (rt instanceof PrimType p) {
      right = a.coerce(right, a.syms.boxed(p), b.right().span());
      rt = right.type();
    }
    checkComparable(lt, rt, span, b.op().symbol());
    if (ConstFold.isConst(left) && ConstFold.isConst(right)) {
      boolean eq = ConstFold.valueOf(left).equals(ConstFold.valueOf(right));
      return new BExpr.Const(negate != eq, PrimType.BOOLEAN, span);
    }
    return new BExpr.ValueEquals(left, right, negate, PrimType.BOOLEAN, span);
  }

  private void checkComparable(Type lt, Type rt, Span span, String op) {
    Type l = types().boxIfPrimitive(lt);
    Type r = types().boxIfPrimitive(rt);
    if (!types().isCastable(l, r) && !types().isCastable(r, l)) {
      a.report(
          a.err(
              Code.BAD_OPERANDS,
              span,
              "'"
                  + op
                  + "' between unrelated types "
                  + lt.display()
                  + " and "
                  + rt.display()
                  + " is always false"));
    }
  }

  // ------------------------------------------------------------------ ??

  private BExpr coalesce(Expr.Binary b, Type pt) {
    Span span = b.span();
    BExpr left = a.value(b.left(), pt == null ? null : pt.withNullness(Nullness.NULLABLE));
    Type lt = left.type();
    if (lt.isError()) {
      a.value(b.right(), pt);
      return left;
    }
    if (lt instanceof PrimType) {
      a.error(
          Code.BAD_OPERANDS,
          span,
          "'??' needs a nullable left operand, but " + lt.display() + " is never null");
      return left;
    }
    if (lt.nullness() == Nullness.NON_NULL
        && !(lt instanceof Type.NullType)
        && !a.isSpeculative()) {
      a.warn(
          Code.REDUNDANT_NON_NULL_ASSERTION, b.left().span(), "left operand of '??' is never null");
    }
    FlowState before = a.env.flow.copy();
    Type nonNullLeft = lt instanceof Type.NullType ? null : lt.withNullness(Nullness.NON_NULL);
    BExpr right = a.value(b.right(), pt != null ? pt : nonNullLeft);
    a.env.flow.join(before);
    Type rt = right.type();
    Type result;
    if (pt != null && pt != PrimType.VOID) {
      result = pt;
    } else if (nonNullLeft == null) {
      result = rt;
    } else if (rt instanceof Type.NeverType) {
      result = nonNullLeft;
    } else {
      PrimType lu = types().unboxedType(nonNullLeft);
      if (lu != null && rt instanceof PrimType rp && rp.isNumeric() == lu.isNumeric()) {
        result = lu == rp ? rp : (lu.isNumeric() ? Types.promote(lu, rp) : rp);
      } else {
        result = types().lub(List.of(nonNullLeft, rt));
      }
    }
    VarSymbol tmp =
        a.env.newVar("$coalesce", lt, Flags.FINAL | Flags.SYNTHETIC, VarSymbol.Kind.LOCAL, span);
    BExpr tmpRef = new BExpr.Local(tmp, span);
    BExpr present =
        nonNullLeft == null
            ? new BExpr.Error(result, span)
            : new BExpr.Conv(tmpRef, ConvKind.RETYPE, nonNullLeft, span);
    present = a.coerce(present, result, b.left().span());
    BExpr fallback = a.coerce(right, result, b.right().span());
    BExpr test =
        new BExpr.Binary(
            BinOp.REF_NE,
            tmpRef,
            new BExpr.Const(null, Type.NullType.INSTANCE, span),
            PrimType.BOOLEAN,
            false,
            span);
    return new BExpr.Let(
        tmp, left, new BExpr.Conditional(test, present, fallback, result, span), span);
  }

  // ------------------------------------------------------------------ assignment

  BExpr assign(Expr.Assign as) {
    Span span = as.span();
    if (as.op() == Expr.AssignOp.ASSIGN && as.target() instanceof Expr.Tuple t) {
      return a.patterns.tupleAssign(t, as.value(), span);
    }
    if (as.op() == Expr.AssignOp.COALESCE) {
      return coalesceAssign(as);
    }
    BLValue lv = lvalue(as.target());
    if (as.op() != Expr.AssignOp.ASSIGN) {
      requireAssigned(lv, as.target().span());
    }
    if (as.op() == Expr.AssignOp.ASSIGN) {
      BExpr v = a.exprCoerced(as.value(), lv.type(), as.value().span());
      afterAssign(lv, v);
      return new BExpr.Assign(lv, v, lv.type(), span);
    }
    BExpr rhs = a.value(as.value(), null);
    Type t = lv.type();
    if (t.isError() || rhs.type().isError()) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    BinOp op = binOp(as.op().op());
    if (op == BinOp.ADD && isString(t)) {
      afterAssign(lv, null);
      return new BExpr.CompoundAssign(lv, op, rhs, t, t, false, span);
    }
    PrimType lp = types().primitiveView(t);
    PrimType rp = types().primitiveView(rhs.type());
    if (lp == null || rp == null) {
      reportBadOperands(as.op().symbol(), t, rhs.type(), span);
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    if (t.isReference() && t.nullness() == Nullness.NULLABLE) {
      a.report(
          a.err(
                  Code.NULLABILITY_MISMATCH,
                  as.target().span(),
                  "operand of type " + t.display() + " may be null")
              .help("use '!' or '??' to provide a non-null value"));
    }
    rhs = checkUnboxNullable(rhs, as.value().span());
    PrimType opType;
    switch (op) {
      case SHL, SHR, USHR -> {
        if (!lp.isIntegral() || !rp.isIntegral()) {
          reportBadOperands(as.op().symbol(), t, rhs.type(), span);
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        opType = Types.promote(lp);
        rhs = a.coerce(rhs, Types.promote(rp), as.value().span());
        if (rhs.type() == PrimType.LONG) {
          rhs = a.primConv(rhs, PrimType.INT, as.value().span());
        }
        afterAssign(lv, null);
        return new BExpr.CompoundAssign(lv, op, rhs, opType, t, false, span);
      }
      case AND, OR, XOR -> {
        if (lp == PrimType.BOOLEAN && rp == PrimType.BOOLEAN) {
          opType = PrimType.BOOLEAN;
        } else if (lp.isIntegral() && rp.isIntegral()) {
          opType = Types.promote(lp, rp);
        } else {
          reportBadOperands(as.op().symbol(), t, rhs.type(), span);
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
      }
      default -> {
        if (!lp.isNumeric() || !rp.isNumeric()) {
          reportBadOperands(as.op().symbol(), t, rhs.type(), span);
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        opType = Types.promote(lp, rp);
      }
    }
    rhs = a.coerce(rhs, opType, as.value().span());
    afterAssign(lv, null);
    boolean checked =
        a.env.checked
            && (opType == PrimType.INT || opType == PrimType.LONG)
            && (op == BinOp.ADD || op == BinOp.SUB || op == BinOp.MUL);
    return new BExpr.CompoundAssign(lv, op, rhs, opType, t, checked, span);
  }

  /** {@code x ??= v}: assigns only when {@code x} is null; the result is non-null. */
  private BExpr coalesceAssign(Expr.Assign as) {
    Span span = as.span();
    BLValue lv = lvalue(as.target());
    requireAssigned(lv, as.target().span());
    Type t = lv.type();
    if (t.isError()) {
      a.value(as.value(), null);
      return new BExpr.Error(t, span);
    }
    if (t instanceof PrimType) {
      a.error(
          Code.BAD_OPERANDS,
          span,
          "'??=' needs a nullable target, but " + t.display() + " is never null");
      return new BExpr.Error(t, span);
    }
    Type nn = t.withNullness(Nullness.NON_NULL);
    BExpr v = a.exprCoerced(as.value(), nn, as.value().span());
    if (lv instanceof BLValue.LocalLV l) {
      // x = x ?? v, written only when null:  x != null ? x : (x = v)
      BExpr read = new BExpr.Local(l.var(), span);
      BExpr test =
          new BExpr.Binary(
              BinOp.REF_NE,
              read,
              new BExpr.Const(null, Type.NullType.INSTANCE, span),
              PrimType.BOOLEAN,
              false,
              span);
      BExpr assign = new BExpr.Assign(lv, v, t, span);
      afterAssign(lv, v);
      return new BExpr.Conditional(
          test,
          new BExpr.Conv(read, ConvKind.RETYPE, nn, span),
          new BExpr.Conv(assign, ConvKind.RETYPE, nn, span),
          nn,
          span);
    }
    // Fields/properties/indexers: evaluate the location's receiver once via a compound node.
    afterAssign(lv, null);
    return new BExpr.CompoundAssign(lv, null, v, nn, nn, false, span);
  }

  /** Updates flow facts after assigning to {@code lv}. */
  private void afterAssign(BLValue lv, BExpr value) {
    if (lv instanceof BLValue.LocalLV l) {
      VarSymbol v = l.var();
      if (v.id() >= 0) {
        a.env.flow.assigned.set(v.id());
        a.env.flow.maybeAssigned.set(v.id());
      }
      a.env.flow.narrowed.remove(v);
      if (value != null
          && v.type().nullness() != Nullness.NON_NULL
          && value.type().isReference()
          && value.type().nullness() == Nullness.NON_NULL
          && !(value.type() instanceof Type.NullType)) {
        a.env.flow.narrowed.put(v, v.type().withNullness(Nullness.NON_NULL));
      }
    }
  }

  // ------------------------------------------------------------------ lvalues

  BLValue lvalue(Expr target) {
    Span span = target.span();
    switch (target) {
      case Expr.Paren p -> {
        return lvalue(p.expr());
      }
      case Expr.Name n -> {
        return nameLValue(n);
      }
      case Expr.Member m when !m.nullSafe() -> {
        Attr.Target q = a.target(m.target(), true);
        return switch (q) {
          case Attr.ValueTarget v -> memberLValue(v.expr(), m.name(), m.nameSpan(), false);
          case Attr.TypeTarget tt -> staticLValue(tt.type(), m.name(), m.nameSpan());
          case Attr.SuperTarget s ->
              memberLValue(new BExpr.This(s.superType(), s.span()), m.name(), m.nameSpan(), true);
          case Attr.PackageTarget p -> {
            a.error(
                Code.NOT_ASSIGNABLE, span, "cannot assign to package member '" + m.name() + "'");
            yield errorLV();
          }
        };
      }
      case Expr.Index i when !i.nullSafe() -> {
        BExpr recv = a.value(i.target(), null);
        if (recv.type().isError()) {
          a.value(i.index(), null);
          return errorLV();
        }
        a.checkReceiverNullness(recv, i.target().span());
        if (i.index() instanceof Expr.Unary fe && fe.op() == Expr.UnaryOp.FROM_END) {
          return fromEndLValue(recv, fe, i.span());
        }
        if (recv.type() instanceof Type.ArrayType at) {
          BExpr idx = a.exprCoerced(i.index(), PrimType.INT);
          return new BLValue.ArrayLV(recv, idx, at.elem());
        }
        Attr.IndexerAccess acc = a.indexer(recv, i, true);
        if (acc == null) {
          return errorLV();
        }
        Type elem = acc.type();
        if (acc.setter().name().equals("put")) {
          elem = elem.withNullness(Nullness.NON_NULL); // writes into a Map use the value type
          ClassType asMap = a.types.asSuper(recv.type(), a.syms.lookup("java/util/Map"));
          if (asMap != null
              && asMap.args().size() == 2
              && !(asMap.args().get(1) instanceof Type.WildcardType)) {
            elem = asMap.args().get(1);
          }
        }
        return new BLValue.PropertyLV(
            recv,
            null,
            acc.getter(),
            acc.setter(),
            List.of(acc.index()),
            elem,
            Attr.callKind(acc.setter(), recv, false));
      }
      default -> {
        a.error(Code.NOT_ASSIGNABLE, span, "this expression cannot be assigned to");
        a.value(target, null);
        return errorLV();
      }
    }
  }

  /** {@code xs[^n] = v} on arrays and lists (the receiver must be re-evaluable). */
  private BLValue fromEndLValue(BExpr recv, Expr.Unary fe, Span span) {
    if (!(recv instanceof BExpr.Local
        || recv instanceof BExpr.This
        || recv instanceof BExpr.Field f
            && (f.receiver() == null || f.receiver() instanceof BExpr.This))) {
      a.report(
          a.err(
                  Code.INVALID_RANGE,
                  span,
                  "'^' in an assignment needs a variable or field on the left")
              .help("store the collection in a local variable first"));
      return errorLV();
    }
    BExpr n = a.exprCoerced(fe.operand(), PrimType.INT);
    Type t = recv.type();
    if (t instanceof Type.ArrayType at) {
      BExpr len = new BExpr.ArrayLength(recv, PrimType.INT, span);
      return new BLValue.ArrayLV(
          recv, new BExpr.Binary(BExpr.BinOp.SUB, len, n, PrimType.INT, false, span), at.elem());
    }
    ClassSymbol list = a.syms.lookup("java/util/List");
    ClassType asList = a.types.asSuper(t, list);
    if (asList == null) {
      a.error(Code.INVALID_RANGE, span, "'^' indices work on arrays and lists, not " + t.display());
      return errorLV();
    }
    MethodSymbol size = null;
    MethodSymbol get = null;
    MethodSymbol set = null;
    for (MethodSymbol m : a.lookup.findMethods(t, "size")) {
      if (m.params().isEmpty()) {
        size = m;
      }
    }
    for (MethodSymbol m : a.lookup.findMethods(t, "get")) {
      if (m.params().size() == 1 && m.params().getFirst().type() == PrimType.INT) {
        get = m;
      }
    }
    for (MethodSymbol m : a.lookup.findMethods(t, "set")) {
      if (m.params().size() == 2 && m.params().getFirst().type() == PrimType.INT) {
        set = m;
      }
    }
    BExpr len =
        new BExpr.Call(recv, size, List.of(), Attr.callKind(size, recv, false), PrimType.INT, span);
    BExpr idx = new BExpr.Binary(BExpr.BinOp.SUB, len, n, PrimType.INT, false, span);
    Type elem =
        a.types.uncapture(
            a.memberType(a.captureSite(t), list, list.typeParams().getFirst().asType()));
    return new BLValue.PropertyLV(
        recv, null, get, set, List.of(idx), elem, Attr.callKind(set, recv, false));
  }

  private static BLValue errorLV() {
    return new BLValue.LocalLV(
        new VarSymbol("<error>", Type.ErrorType.INSTANCE, 0, VarSymbol.Kind.LOCAL, Span.NONE, -1));
  }

  private BLValue nameLValue(Expr.Name n) {
    String name = n.name();
    Span span = n.span();
    Env env = a.env;
    if (name.equals("field") && env.backingField != null && env.scope.lookup("field") == null) {
      FieldSymbol f = env.backingField;
      BExpr recv = f.isStatic() ? null : a.thisValue(span);
      return new BLValue.FieldLV(recv, f, f.type());
    }
    Scope.Found found = env.scope.lookup(name);
    if (found != null) {
      VarSymbol v = found.var();
      if (!found.crossed().isEmpty()) {
        a.report(
            a.err(
                    Code.CAPTURED_NOT_FINAL,
                    span,
                    "cannot assign to '" + name + "' inside a lambda or local class")
                .note("captured variables must be effectively final, as in Java"));
        return new BLValue.LocalLV(v);
      }
      boolean definitelyAssigned = env.flow.assigned.get(v.id());
      if (v.isFinal() && (definitelyAssigned || v.kind() != VarSymbol.Kind.LOCAL)) {
        a.report(
            a.err(
                    Code.FINAL_REASSIGNED,
                    span,
                    (v.kind() == VarSymbol.Kind.PARAM ? "parameter" : "val")
                        + " '"
                        + name
                        + "' cannot be reassigned")
                .note("declared here", a.file(), v.span())
                .help(
                    v.kind() == VarSymbol.Kind.PARAM
                        ? "copy it into a local 'var'"
                        : "declare it with 'var' instead of 'val'"));
      } else if (v.isFinal()
          && !definitelyAssigned
          && v.id() >= 0
          && env.flow.maybeAssigned.get(v.id())) {
        a.error(Code.FINAL_REASSIGNED, span, "val '" + name + "' might already have been assigned");
      }
      if (definitelyAssigned || v.kind() != VarSymbol.Kind.LOCAL) {
        v.markReassigned();
      }
      // Definite assignment is recorded after the right-hand side is evaluated (afterAssign).
      return new BLValue.LocalLV(v);
    }
    // Members of enclosing classes.
    boolean throughStatic = env.isStatic;
    boolean first = true;
    for (ClassSymbol c = env.cls; c != null; c = c.outer()) {
      BLValue lv = memberLValueOfClass(c, name, span, first, throughStatic);
      if (lv != null) {
        return lv;
      }
      if (!c.has(Flags.LOCAL) && !c.has(Flags.ANONYMOUS) || c.has(Flags.STATIC)) {
        throughStatic = true;
      }
      first = false;
    }
    // Top-level values of the file/package.
    ClassSymbol module = a.moduleOf(env.cls);
    if (module != null) {
      FieldSymbol f = module.field(name);
      if (f != null) {
        return fieldLValue(null, module.thisType(), f, span, false);
      }
    }
    for (ClassSymbol m : a.packageModules.getOrDefault(env.cls.packageName(), List.of())) {
      FieldSymbol f = m.field(name);
      if (f != null && !f.has(Flags.PRIVATE)) {
        return fieldLValue(null, m.thisType(), f, span, false);
      }
    }
    a.reportUnresolvedName(name, span);
    return errorLV();
  }

  /** A compound assignment or increment reads the local first: it must be definitely assigned. */
  void requireAssigned(BLValue lv, Span span) {
    if (lv instanceof BLValue.LocalLV l
        && l.var().id() >= 0
        && !a.env.flow.assigned.get(l.var().id())
        && a.env.flow.alive
        && (l.var().kind() == VarSymbol.Kind.LOCAL || l.var().kind() == VarSymbol.Kind.PATTERN)) {
      a.error(
          Code.UNINITIALIZED_VARIABLE,
          span,
          "variable '" + l.var().name() + "' might not have been initialized");
      a.env.flow.assigned.set(l.var().id());
    }
  }

  /** Tracks `val x;` declared without initializer: assignable while definitely unassigned. */
  private boolean isDefinitelyUnassigned(VarSymbol v) {
    return !v.reassigned();
  }

  private BLValue memberLValueOfClass(
      ClassSymbol c, String name, Span span, boolean isCurrent, boolean staticOnly) {
    ClassType site = c.thisType();
    PropertySymbol p = a.lookup.findProperty(site, name);
    FieldSymbol f = p == null ? a.lookup.findField(site, name) : null;
    if (f != null
        && !c.has(Flags.MODULE)
        && a.hiddenByAccessor(f, site, a.lookup.findSetter(site, name))) {
      f = null;
    }
    MethodSymbol setter =
        p == null && f == null && !c.has(Flags.MODULE) ? a.lookup.findSetter(site, name) : null;
    if (p == null && f == null && setter == null) {
      return null;
    }
    boolean isStatic = p != null ? p.isStatic() : f != null ? f.isStatic() : setter.isStatic();
    BExpr recv = null;
    if (!isStatic) {
      if (staticOnly) {
        a.error(
            Code.STATIC_CONTEXT,
            span,
            "instance member '" + name + "' cannot be used in a static context");
        return errorLV();
      }
      recv = isCurrent ? a.thisValue(span) : new BExpr.OuterThis(c, c.thisType(), span);
    }
    if (p != null) {
      return propertyLValue(recv, site, p, span, true);
    }
    if (f != null) {
      return fieldLValue(recv, site, f, span, true);
    }
    return beanLValue(recv, site, setter, name, span);
  }

  private BLValue memberLValue(BExpr recv, String name, Span span, boolean isSuper) {
    Type site = recv.type();
    if (site.isError()) {
      return errorLV();
    }
    a.checkReceiverNullness(recv, span);
    boolean onThis = recv instanceof BExpr.This;
    PropertySymbol p = a.lookup.findProperty(site, name);
    if (p != null) {
      return propertyLValue(recv, site, p, span, onThis);
    }
    if (!onThis && a.lookup.findRecordAccessor(site, name) != null
        || site instanceof Type.TupleType) {
      a.error(
          Code.NOT_ASSIGNABLE,
          span,
          "'"
              + name
              + "' of "
              + site.display()
              + " is read-only (records and tuples are immutable)");
      return errorLV();
    }
    FieldSymbol f = a.lookup.findField(site, name);
    if (f != null && !a.hiddenByAccessor(f, site, a.lookup.findSetter(site, name))) {
      return fieldLValue(recv, site, f, span, onThis);
    }
    MethodSymbol setter = a.lookup.findSetter(site, name);
    if (setter != null) {
      return beanLValue(recv, site, setter, name, span);
    }
    if (site instanceof Type.ArrayType && name.equals("length")) {
      a.error(Code.NOT_ASSIGNABLE, span, "the length of an array cannot be assigned");
      return errorLV();
    }
    if (a.lookup.findGetter(site, name) != null
        || a.lookup.findRecordAccessor(site, name) != null) {
      a.error(Code.NOT_ASSIGNABLE, span, "'" + name + "' of " + site.display() + " is read-only");
      return errorLV();
    }
    a.reportNoMember(site, name, span);
    return errorLV();
  }

  private BLValue staticLValue(Type site, String name, Span span) {
    if (site.isError()) {
      return errorLV();
    }
    PropertySymbol p = a.lookup.findProperty(site, name);
    if (p != null && p.isStatic()) {
      return propertyLValue(null, site, p, span, false);
    }
    FieldSymbol f = a.lookup.findField(site, name);
    if (f != null && f.isStatic()) {
      return fieldLValue(null, site, f, span, false);
    }
    a.reportNoMember(site, name, span);
    return errorLV();
  }

  BLValue fieldLValue(BExpr recv, Type site, FieldSymbol f, Span span, boolean onThis) {
    a.checkAccess(f, f.owner(), recv == null ? null : site, span);
    Env env = a.env;
    if (f.has(Flags.FINAL)) {
      boolean inInit =
          f.owner() == env.cls
              && (f.isStatic()
                  ? env.isStatic && env.method == null || isStaticInitializer()
                  : env.inConstructor && onThis);
      if (!inInit) {
        a.report(
            a.err(
                    Code.NOT_ASSIGNABLE,
                    span,
                    (f.has(Flags.ENUM_CONSTANT) ? "enum constant" : "final field")
                        + " '"
                        + f.name()
                        + "' cannot be assigned")
                .help(
                    f.owner().isSource() && !f.has(Flags.ENUM_CONSTANT)
                        ? "declare it with 'var' (or without 'final') to make it mutable"
                        : null));
      } else {
        if (env.flow.assignedFields.contains(f) && env.flow.alive) {
          a.error(
              Code.FINAL_REASSIGNED,
              span,
              "final field '" + f.name() + "' may already have been assigned");
        }
        env.flow.assignedFields.add(f);
      }
    }
    Type t = a.memberType(a.captureSite(site), f.owner(), a.ensureFieldType(f));
    return new BLValue.FieldLV(f.isStatic() ? null : recv, f, t);
  }

  private boolean isStaticInitializer() {
    return a.env.isStatic && a.env.method == null;
  }

  BLValue propertyLValue(BExpr recv, Type site, PropertySymbol p, Span span, boolean onThis) {
    Env env = a.env;
    Type t = a.memberType(a.captureSite(site), p.owner(), p.type());
    boolean inOwnInit =
        p.owner() == env.cls
            && onThis
            && (env.inConstructor || p.isStatic() && isStaticInitializer());
    if (p.setter() == null || p.isInitOnly()) {
      if (inOwnInit && p.backingField() != null) {
        // Get-only and init-only auto-properties are assigned through the backing field during
        // construction.
        env.flow.assignedFields.add(p.backingField());
        return new BLValue.FieldLV(p.isStatic() ? null : recv, p.backingField(), t);
      }
      if (p.setter() == null) {
        a.report(
            a.err(Code.NOT_ASSIGNABLE, span, "property '" + p.name() + "' is read-only")
                .help(
                    p.owner().isSource()
                        ? "add a 'set' accessor, or assign it in a constructor"
                        : null));
        return errorLV();
      }
      a.report(
          a.err(
                  Code.INIT_ONLY_ASSIGNMENT,
                  span,
                  "init-only property '"
                      + p.name()
                      + "' can only be set in an object initializer or constructor")
              .help(
                  "use 'new "
                      + p.owner().name()
                      + "(...) { "
                      + p.name()
                      + " = ... }' or a 'with' expression"));
      return errorLV();
    }
    a.checkAccess(p.setter(), p.owner(), recv == null ? null : site, span);
    return new BLValue.PropertyLV(
        p.isStatic() ? null : recv,
        p,
        p.getter(),
        p.setter(),
        List.of(),
        t,
        Attr.callKind(p.setter(), recv, isSuperReceiver(recv)));
  }

  /** {@code super.p = v} must call the superclass setter non-virtually. */
  private boolean isSuperReceiver(BExpr recv) {
    return recv instanceof BExpr.This th
        && th.type() instanceof ClassType ct
        && ct.sym() != a.env.cls;
  }

  private BLValue beanLValue(BExpr recv, Type site, MethodSymbol setter, String name, Span span) {
    a.checkAccess(setter, setter.owner(), recv == null ? null : site, span);
    MethodSymbol getter = a.lookup.findGetter(site, name);
    Type t = a.memberType(a.captureSite(site), setter.owner(), setter.params().getFirst().type());
    return new BLValue.PropertyLV(
        setter.isStatic() ? null : recv,
        null,
        getter,
        setter,
        List.of(),
        t,
        Attr.callKind(setter, recv, false));
  }
}

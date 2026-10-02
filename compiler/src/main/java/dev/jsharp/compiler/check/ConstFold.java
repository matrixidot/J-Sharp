package dev.jsharp.compiler.check;

import dev.jsharp.compiler.bound.BExpr;
import dev.jsharp.compiler.bound.BExpr.BinOp;
import dev.jsharp.compiler.types.Type;
import dev.jsharp.compiler.types.Type.PrimType;

/**
 * Constant folding for primitive and String constant expressions (JLS 15.29 semantics: wrapping
 * integer arithmetic, IEEE floating point). Division by zero is never folded.
 */
final class ConstFold {
  private ConstFold() {}

  /** Converts a constant value to the given primitive type (numeric conversions). */
  static Object convert(Object v, PrimType to) {
    if (v instanceof Boolean || v instanceof String || v == null) {
      return v;
    }
    if (v instanceof Character c) {
      v = (int) c;
    }
    Number n = (Number) v;
    return switch (to) {
      case BYTE -> (int) (byte) n.longValue();
      case SHORT -> (int) (short) n.longValue();
      case CHAR -> (char) n.longValue();
      case INT -> v instanceof Float || v instanceof Double ? (int) n.doubleValue() : (int) n.longValue();
      case LONG -> v instanceof Float || v instanceof Double ? (long) n.doubleValue() : n.longValue();
      case FLOAT -> v instanceof Long l ? (float) l : n.floatValue();
      case DOUBLE -> v instanceof Long l ? (double) l : n.doubleValue();
      default -> v;
    };
  }

  /** Folds a binary operation on two constants, or returns null. */
  static Object binary(BinOp op, Object a, Object b, Type operandType) {
    if (a instanceof Character c) {
      a = (int) c;
    }
    if (b instanceof Character c) {
      b = (int) c;
    }
    if (!(operandType instanceof PrimType p)) {
      if (op == BinOp.ADD && (a instanceof String || b instanceof String)) {
        return String.valueOf(a) + b;
      }
      return null;
    }
    try {
      switch (p) {
        case BOOLEAN -> {
          boolean x = (Boolean) a;
          boolean y = (Boolean) b;
          return switch (op) {
            case AND, COND_AND -> x && y;
            case OR, COND_OR -> x || y;
            case XOR, NE -> x ^ y;
            case EQ -> x == y;
            default -> null;
          };
        }
        case INT, BYTE, SHORT, CHAR -> {
          int x = ((Number) a).intValue();
          int y = ((Number) b).intValue();
          return switch (op) {
            case ADD -> x + y;
            case SUB -> x - y;
            case MUL -> x * y;
            case DIV -> y == 0 ? null : x / y;
            case REM -> y == 0 ? null : x % y;
            case SHL -> x << y;
            case SHR -> x >> y;
            case USHR -> x >>> y;
            case AND -> x & y;
            case OR -> x | y;
            case XOR -> x ^ y;
            case LT -> x < y;
            case GT -> x > y;
            case LE -> x <= y;
            case GE -> x >= y;
            case EQ -> x == y;
            case NE -> x != y;
            default -> null;
          };
        }
        case LONG -> {
          long x = ((Number) a).longValue();
          long y = ((Number) b).longValue();
          return switch (op) {
            case ADD -> x + y;
            case SUB -> x - y;
            case MUL -> x * y;
            case DIV -> y == 0 ? null : x / y;
            case REM -> y == 0 ? null : x % y;
            case SHL -> x << y;
            case SHR -> x >> y;
            case USHR -> x >>> y;
            case AND -> x & y;
            case OR -> x | y;
            case XOR -> x ^ y;
            case LT -> x < y;
            case GT -> x > y;
            case LE -> x <= y;
            case GE -> x >= y;
            case EQ -> x == y;
            case NE -> x != y;
            default -> null;
          };
        }
        case FLOAT -> {
          float x = ((Number) a).floatValue();
          float y = ((Number) b).floatValue();
          return switch (op) {
            case ADD -> x + y;
            case SUB -> x - y;
            case MUL -> x * y;
            case DIV -> x / y;
            case REM -> x % y;
            case LT -> x < y;
            case GT -> x > y;
            case LE -> x <= y;
            case GE -> x >= y;
            case EQ -> x == y;
            case NE -> x != y;
            default -> null;
          };
        }
        case DOUBLE -> {
          double x = ((Number) a).doubleValue();
          double y = ((Number) b).doubleValue();
          return switch (op) {
            case ADD -> x + y;
            case SUB -> x - y;
            case MUL -> x * y;
            case DIV -> x / y;
            case REM -> x % y;
            case LT -> x < y;
            case GT -> x > y;
            case LE -> x <= y;
            case GE -> x >= y;
            case EQ -> x == y;
            case NE -> x != y;
            default -> null;
          };
        }
        default -> {
          return null;
        }
      }
    } catch (ClassCastException e) {
      return null;
    }
  }

  static Object unary(BExpr.UnOp op, Object v) {
    if (v instanceof Character c) {
      v = (int) c;
    }
    return switch (op) {
      case NOT -> v instanceof Boolean b ? !b : null;
      case NEG ->
          switch (v) {
            case Integer i -> -i;
            case Long l -> -l;
            case Float f -> -f;
            case Double d -> -d;
            default -> null;
          };
      case BIT_NOT ->
          switch (v) {
            case Integer i -> ~i;
            case Long l -> ~l;
            default -> null;
          };
    };
  }

  /** The constant value of a bound expression, or null if it is not a constant. */
  static Object valueOf(BExpr e) {
    return e instanceof BExpr.Const c && !(c.type() instanceof Type.NullType) ? c.value() : null;
  }

  static boolean isConst(BExpr e) {
    return e instanceof BExpr.Const c && !(c.type() instanceof Type.NullType);
  }
}

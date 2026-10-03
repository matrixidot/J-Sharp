package io.github.matrixidot.jsharp.compiler.ast;

import java.util.Map;

/**
 * Overloadable operators and the JVM method names they compile to (D077). Readable names make
 * user-defined operators ordinary static methods for Java callers ({@code Money.plus(a, b)}).
 */
public final class Operators {
  private Operators() {}

  private static final Map<String, String> BINARY =
      Map.ofEntries(
          Map.entry("+", "plus"),
          Map.entry("-", "minus"),
          Map.entry("*", "times"),
          Map.entry("/", "div"),
          Map.entry("%", "rem"),
          Map.entry("&", "and"),
          Map.entry("|", "or"),
          Map.entry("^", "xor"),
          Map.entry("<<", "shl"),
          Map.entry(">>", "shr"),
          Map.entry(">>>", "ushr"),
          Map.entry("<", "lessThan"),
          Map.entry(">", "greaterThan"),
          Map.entry("<=", "lessOrEqual"),
          Map.entry(">=", "greaterOrEqual"));

  private static final Map<String, String> UNARY =
      Map.of("-", "unaryMinus", "+", "unaryPlus", "!", "not", "~", "inv");

  /** The JVM name for {@code symbol} with {@code arity} operands, or null if not overloadable. */
  public static String jvmName(String symbol, int arity) {
    return switch (arity) {
      case 1 -> UNARY.get(symbol);
      case 2 -> BINARY.get(symbol);
      default -> null;
    };
  }

  /** The operator symbol of a JVM operator method name with {@code arity} operands, or null. */
  public static String symbolOf(String jvmName, int arity) {
    Map<String, String> table = arity == 1 ? UNARY : arity == 2 ? BINARY : Map.of();
    for (var e : table.entrySet()) {
      if (e.getValue().equals(jvmName)) {
        return e.getKey();
      }
    }
    return null;
  }

  public static boolean isComparison(String symbol) {
    return symbol.equals("<") || symbol.equals(">") || symbol.equals("<=") || symbol.equals(">=");
  }

  /** The operator that must be declared together with a comparison ({@code <} with {@code >}). */
  public static String pairOf(String symbol) {
    return switch (symbol) {
      case "<" -> ">";
      case ">" -> "<";
      case "<=" -> ">=";
      case ">=" -> "<=";
      default -> null;
    };
  }

  public static boolean isUnary(String symbol) {
    return UNARY.containsKey(symbol);
  }

  public static boolean isBinary(String symbol) {
    return BINARY.containsKey(symbol);
  }
}

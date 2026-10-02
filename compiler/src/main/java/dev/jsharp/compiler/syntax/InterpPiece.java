package dev.jsharp.compiler.syntax;

/** A piece of an interpolated string literal, as found by the lexer. */
public sealed interface InterpPiece {
  /** Literal text (escapes already decoded). */
  record Text(String value) implements InterpPiece {}

  /**
   * An interpolation hole {@code {expr[:format]}}; the expression source range is lexed and parsed
   * by the parser.
   *
   * @param exprStart start offset of the expression source
   * @param exprEnd end offset of the expression source
   * @param format format specifier after {@code :}, or {@code null}
   * @param start offset of the opening brace
   * @param end offset just after the closing brace
   */
  record Hole(int exprStart, int exprEnd, String format, int start, int end)
      implements InterpPiece {}
}

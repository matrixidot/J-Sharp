package io.github.matrixidot.jsharp.compiler.syntax;

import io.github.matrixidot.jsharp.compiler.source.Span;

/**
 * A lexical token.
 *
 * @param kind token kind
 * @param start start offset (inclusive)
 * @param end end offset (exclusive)
 * @param text identifier name (without backticks), keyword spelling, or raw literal text
 * @param value decoded literal value ({@code Long}, {@code Double}, {@code Float}, {@code
 *     Character}, {@code String}, or {@code List<InterpPiece>}), else {@code null}
 * @param escaped true for backtick-escaped identifiers, which are never contextual keywords
 * @param malformed true if the lexer reported an error for this token (suppresses parser cascades)
 */
public record Token(
    TokenKind kind,
    int start,
    int end,
    String text,
    Object value,
    boolean escaped,
    boolean malformed) {

  public Token(TokenKind kind, int start, int end, String text, Object value, boolean escaped) {
    this(kind, start, end, text, value, escaped, false);
  }

  /** Returns a copy flagged as malformed (the lexer already reported an error for it). */
  public Token asMalformed() {
    return new Token(kind, start, end, text, value, escaped, true);
  }

  public Span span() {
    return new Span(start, end);
  }

  public boolean is(TokenKind k) {
    return kind == k;
  }

  /** True if this is the unescaped identifier {@code word} (a contextual keyword). */
  public boolean isContextual(String word) {
    return kind == TokenKind.IDENTIFIER && !escaped && text.equals(word);
  }

  /** How the token is shown in "found X" messages. */
  public String describe() {
    return switch (kind) {
      case EOF -> "end of file";
      case IDENTIFIER -> "identifier '" + text + "'";
      case INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL -> "number '" + text + "'";
      case STRING_LITERAL, INTERP_STRING -> "string literal";
      case CHAR_LITERAL -> "character literal";
      case ERROR -> "invalid token";
      default -> kind.isKeyword() ? "keyword '" + text + "'" : "'" + kind.text() + "'";
    };
  }
}

package dev.jsharp.compiler.diag;

import java.util.Locale;

/**
 * Stable diagnostic codes. Numbers never change once released; retired codes stay reserved.
 *
 * <p>Ranges: {@code JS0001-0099} lexical, {@code JS0100-0399} syntax, {@code JS0400-0599}
 * declarations and name resolution, {@code JS0600-0899} typing, {@code JS0900-0999} flow analysis,
 * {@code JS1000-1099} code generation and driver, {@code JS9000+} internal.
 */
public enum Code {
  // ---- lexical ----
  UNEXPECTED_CHARACTER(1, "unexpected character"),
  UNTERMINATED_STRING(2, "unterminated string literal"),
  UNTERMINATED_CHAR(3, "unterminated character literal"),
  INVALID_ESCAPE(4, "invalid escape sequence"),
  MALFORMED_NUMBER(5, "malformed numeric literal"),
  UNTERMINATED_COMMENT(6, "unterminated block comment"),
  INVALID_CHAR_LITERAL(7, "invalid character literal"),
  NUMBER_TOO_LARGE(8, "numeric literal out of range"),
  INVALID_INTERPOLATION(9, "invalid string interpolation"),
  LEADING_ZERO(10, "leading zeros are not allowed"),

  // ---- syntax ----
  EXPECTED_TOKEN(100, "expected token"),
  EXPECTED_EXPRESSION(101, "expected expression"),
  EXPECTED_TYPE(102, "expected type"),
  EXPECTED_DECLARATION(103, "expected declaration"),
  EXPECTED_STATEMENT(104, "expected statement"),
  EXPECTED_IDENTIFIER(105, "expected identifier"),
  EXPECTED_PATTERN(106, "expected pattern"),
  DUPLICATE_MODIFIER(107, "duplicate modifier"),
  MISPLACED_PACKAGE(108, "misplaced package or import declaration"),
  UNSUPPORTED_SYNTAX(109, "syntax reserved for a future version"),
  INVALID_ACCESSOR(110, "invalid property accessor"),
  MISSING_ELSE(111, "if-expression requires an else branch"),
  INVALID_LAMBDA_PARAMETERS(112, "invalid lambda parameter list"),
  UNEXPECTED_TOKEN(113, "unexpected token"),
  INVALID_TUPLE(114, "invalid tuple"),

  // ---- internal ----
  INTERNAL_ERROR(9000, "internal compiler error");

  private final int number;
  private final String title;

  Code(int number, String title) {
    this.number = number;
    this.title = title;
  }

  /** The stable identifier, e.g. {@code JS0101}. */
  public String id() {
    return String.format(Locale.ROOT, "JS%04d", number);
  }

  public int number() {
    return number;
  }

  /** A short generic description of the problem class. */
  public String title() {
    return title;
  }

  public Severity defaultSeverity() {
    return Severity.ERROR;
  }
}

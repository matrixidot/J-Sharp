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

  // ---- declarations & resolution ----
  DUPLICATE_TYPE(400, "duplicate type declaration"),
  UNRESOLVED_TYPE(401, "cannot find type"),
  UNRESOLVED_PACKAGE(402, "package does not exist"),
  UNRESOLVED_IMPORT(403, "cannot resolve import"),
  AMBIGUOUS_TYPE(404, "ambiguous type name"),
  WRONG_TYPE_ARG_COUNT(405, "wrong number of type arguments"),
  CYCLIC_INHERITANCE(406, "cyclic inheritance"),
  CANNOT_INHERIT_FINAL(407, "cannot inherit from a final class"),
  INVALID_SUPERTYPE(408, "invalid supertype"),
  DUPLICATE_MEMBER(409, "duplicate member"),
  INVALID_MODIFIER(410, "invalid modifier"),
  MULTIPLE_ENTRY_POINTS(411, "multiple files with top-level statements"),
  INVALID_PERMITS(412, "invalid sealed hierarchy"),
  MISSING_TYPE_ARGUMENTS(413, "missing type arguments"),
  INACCESSIBLE_TYPE(414, "type is not accessible"),
  INVALID_VOID(415, "void is not allowed here"),
  EXTENSION_NOT_STATIC(416, "extension method must be static"),
  INVALID_PROPERTY(417, "invalid property declaration"),
  MISSING_RETURN_TYPE(418, "missing return type"),
  DUPLICATE_PARAMETER(419, "duplicate parameter"),
  INVALID_DEFAULT_ARGUMENT(420, "invalid default argument"),
  MODULE_NAME_CLASH(421, "module class name clash"),
  INVALID_TOP_LEVEL(422, "declaration not allowed at the top level"),
  TYPE_ARGUMENT_BOUND(423, "type argument does not satisfy bound"),
  INVALID_FILE_ANNOTATION(424, "invalid file annotation"),
  NOT_A_TYPE(425, "not a type"),
  INVALID_VARIANCE(426, "invalid variance"),
  MISSING_BODY(427, "missing body"),
  UNEXPECTED_BODY(428, "unexpected body"),

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

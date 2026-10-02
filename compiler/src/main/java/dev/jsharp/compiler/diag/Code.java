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

  // ---- typing ----
  TYPE_MISMATCH(600, "type mismatch"),
  NULLABILITY_MISMATCH(601, "nullable value where a non-null value is required"),
  UNRESOLVED_NAME(602, "cannot find symbol"),
  UNRESOLVED_MEMBER(603, "no such member"),
  NO_APPLICABLE_METHOD(604, "no applicable method"),
  AMBIGUOUS_CALL(605, "ambiguous call"),
  NOT_CALLABLE(606, "not callable"),
  BAD_OPERANDS(607, "operator cannot be applied"),
  NULLABLE_RECEIVER(608, "member access on a nullable value"),
  NOT_ASSIGNABLE(609, "cannot assign"),
  INVALID_CAST(610, "invalid cast"),
  INACCESSIBLE_MEMBER(611, "member is not accessible"),
  STATIC_CONTEXT(612, "instance member used in a static context"),
  ABSTRACT_INSTANTIATION(613, "cannot instantiate abstract type"),
  ARGUMENT_COUNT(614, "wrong number of arguments"),
  LAMBDA_MISMATCH(615, "lambda or method reference not compatible with target"),
  CANNOT_INFER(616, "cannot infer type"),
  MISSING_OVERRIDE(617, "missing 'override'"),
  NOTHING_TO_OVERRIDE(618, "nothing to override"),
  CANNOT_OVERRIDE(619, "cannot override"),
  ABSTRACT_NOT_IMPLEMENTED(620, "abstract member not implemented"),
  NOT_A_STATEMENT(621, "expression is not a statement"),
  VOID_VALUE(622, "void expression used as a value"),
  LITERAL_OUT_OF_RANGE(623, "literal out of range"),
  INVALID_NAMED_ARGUMENT(624, "invalid named argument"),
  NOT_ITERABLE(626, "not iterable"),
  INIT_ONLY_ASSIGNMENT(628, "init-only property assigned outside initialization"),
  REQUIRED_MEMBER_MISSING(629, "required member not initialized"),
  INVALID_CONSTRUCTOR_CALL(630, "invalid constructor call"),
  CAPTURED_NOT_FINAL(631, "captured variable is not effectively final"),
  REDUNDANT_NON_NULL_ASSERTION(633, "redundant non-null assertion"),
  INVALID_THIS(635, "invalid use of this or super"),
  RECURSIVE_INFERENCE(636, "type inference cycle"),
  AWAIT_OUTSIDE_ASYNC(637, "await outside an async context"),
  PLATFORM_NULLNESS(638, "member access on a value of unknown nullness"),
  DEPRECATED(639, "use of deprecated API"),
  INVALID_INTERPOLATION_FORMAT(640, "invalid interpolation format"),
  NOT_EXHAUSTIVE(650, "switch is not exhaustive"),
  INVALID_PATTERN(651, "invalid pattern"),
  DUPLICATE_CASE(652, "duplicate or dominated case"),
  INVALID_SWITCH(653, "invalid switch"),
  INVALID_RANGE(654, "invalid range or index"),
  INVALID_WITH(655, "invalid with expression"),
  INVALID_TUPLE_USE(656, "invalid tuple operation"),
  INVALID_ASYNC(657, "invalid async declaration"),
  UNSUPPORTED_FEATURE(658, "feature not supported"),

  // ---- flow ----
  UNINITIALIZED_VARIABLE(900, "variable might not be initialized"),
  UNREACHABLE_CODE(901, "unreachable code"),
  MISSING_RETURN(902, "missing return"),
  FINAL_REASSIGNED(903, "final variable assigned more than once"),
  INVALID_JUMP(904, "break or continue outside a loop"),
  UNKNOWN_LABEL(905, "unknown label"),
  SWITCH_FALLTHROUGH(906, "switch section falls through"),
  FINAL_FIELD_UNINITIALIZED(907, "final field not initialized"),
  INVALID_RETHROW(908, "rethrow outside catch"),
  DUPLICATE_VARIABLE(909, "variable already defined"),

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
    return switch (this) {
      case REDUNDANT_NON_NULL_ASSERTION, PLATFORM_NULLNESS, DEPRECATED -> Severity.WARNING;
      default -> Severity.ERROR;
    };
  }
}

package io.github.matrixidot.jsharp.compiler.syntax;

import java.util.HashMap;
import java.util.Map;

/**
 * Token kinds. Contextual keywords ({@code var}, {@code val}, {@code record}, {@code is}, {@code
 * in}, {@code when}, ...) are lexed as {@link #IDENTIFIER} and recognized by the parser, so that
 * Java names such as {@code System.out} or {@code System.in} stay usable.
 *
 * <p>The lexer never produces compound {@code >} tokens ({@code >=}, {@code >>}, ...): it emits
 * single {@link #GT} tokens and the parser joins adjacent ones in expression context. This keeps
 * nested generics like {@code List<List<String>>} trivial to parse.
 */
public enum TokenKind {
  EOF("<end of file>"),
  ERROR("<error>"),
  IDENTIFIER("identifier"),
  INT_LITERAL("integer literal"),
  LONG_LITERAL("long literal"),
  FLOAT_LITERAL("float literal"),
  DOUBLE_LITERAL("double literal"),
  CHAR_LITERAL("character literal"),
  STRING_LITERAL("string literal"),
  INTERP_STRING("interpolated string"),

  // reserved keywords
  ABSTRACT("abstract", true),
  ASSERT("assert", true),
  BOOLEAN("boolean", true),
  BREAK("break", true),
  BYTE("byte", true),
  CASE("case", true),
  CATCH("catch", true),
  CHAR("char", true),
  CLASS("class", true),
  CONST("const", true),
  CONTINUE("continue", true),
  DEFAULT("default", true),
  DO("do", true),
  DOUBLE("double", true),
  ELSE("else", true),
  ENUM("enum", true),
  EXTENDS("extends", true),
  FALSE("false", true),
  FINAL("final", true),
  FINALLY("finally", true),
  FLOAT("float", true),
  FOR("for", true),
  FOREACH("foreach", true),
  GOTO("goto", true),
  IF("if", true),
  IMPLEMENTS("implements", true),
  IMPORT("import", true),
  INSTANCEOF("instanceof", true),
  INT("int", true),
  INTERFACE("interface", true),
  INTERNAL("internal", true),
  LONG("long", true),
  NATIVE("native", true),
  NEW("new", true),
  NULL("null", true),
  OPERATOR("operator", true),
  PACKAGE("package", true),
  PRIVATE("private", true),
  PROTECTED("protected", true),
  PUBLIC("public", true),
  RETURN("return", true),
  SHORT("short", true),
  STATIC("static", true),
  STRICTFP("strictfp", true),
  SUPER("super", true),
  SWITCH("switch", true),
  SYNCHRONIZED("synchronized", true),
  THIS("this", true),
  THROW("throw", true),
  THROWS("throws", true),
  TRANSIENT("transient", true),
  TRUE("true", true),
  TRY("try", true),
  TYPEOF("typeof", true),
  USING("using", true),
  VOID("void", true),
  VOLATILE("volatile", true),
  WHILE("while", true),

  // punctuation and operators
  LPAREN("("),
  RPAREN(")"),
  LBRACE("{"),
  RBRACE("}"),
  LBRACKET("["),
  RBRACKET("]"),
  SEMI(";"),
  COMMA(","),
  DOT("."),
  DOTDOT(".."),
  COLON(":"),
  COLONCOLON("::"),
  QUESTION("?"),
  QUESTION_DOT("?."),
  QUESTION_QUESTION("??"),
  QUESTION_QUESTION_EQ("??="),
  AT("@"),
  ARROW("=>"),
  EQ("="),
  EQEQ("=="),
  EQEQEQ("==="),
  BANG_EQ("!="),
  BANG_EQEQ("!=="),
  LT("<"),
  LE("<="),
  GT(">"),
  LTLT("<<"),
  LTLT_EQ("<<="),
  PLUS("+"),
  MINUS("-"),
  STAR("*"),
  SLASH("/"),
  PERCENT("%"),
  AMP("&"),
  BAR("|"),
  CARET("^"),
  TILDE("~"),
  BANG("!"),
  AMPAMP("&&"),
  BARBAR("||"),
  PLUSPLUS("++"),
  MINUSMINUS("--"),
  PLUS_EQ("+="),
  MINUS_EQ("-="),
  STAR_EQ("*="),
  SLASH_EQ("/="),
  PERCENT_EQ("%="),
  AMP_EQ("&="),
  BAR_EQ("|="),
  CARET_EQ("^=");

  private final String text;
  private final boolean keyword;

  TokenKind(String text) {
    this(text, false);
  }

  TokenKind(String text, boolean keyword) {
    this.text = text;
    this.keyword = keyword;
  }

  /** Human-readable form used in diagnostics. */
  public String text() {
    return text;
  }

  public boolean isKeyword() {
    return keyword;
  }

  public boolean isPrimitiveType() {
    return switch (this) {
      case BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
      default -> false;
    };
  }

  private static final Map<String, TokenKind> KEYWORDS = new HashMap<>();

  static {
    for (TokenKind k : values()) {
      if (k.keyword) {
        KEYWORDS.put(k.text, k);
      }
    }
  }

  /** Returns the reserved keyword spelled {@code s}, or {@code null}. */
  public static TokenKind keyword(String s) {
    return KEYWORDS.get(s);
  }
}

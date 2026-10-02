package dev.jsharp.compiler.syntax;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jsharp.compiler.diag.Code;
import dev.jsharp.compiler.diag.Diagnostic;
import dev.jsharp.compiler.diag.Diagnostics;
import dev.jsharp.compiler.source.SourceFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class LexerTest {
  private final Diagnostics diags = new Diagnostics();

  private List<Token> lex(String src) {
    return Lexer.tokenize(new SourceFile("t.jsharp", src), diags);
  }

  private Token single(String src) {
    List<Token> ts = lex(src);
    assertThat(ts).hasSize(2);
    return ts.getFirst();
  }

  private List<TokenKind> kinds(String src) {
    return lex(src).stream().map(Token::kind).toList();
  }

  private List<Code> codes() {
    return diags.all().stream().map(Diagnostic::code).toList();
  }

  @Test
  void integerForms() {
    assertThat(single("42").value()).isEqualTo(42L);
    assertThat(single("1_000_000").value()).isEqualTo(1_000_000L);
    assertThat(single("0xFF").value()).isEqualTo(255L);
    assertThat(single("0xFFFFFFFF").value()).isEqualTo(-1L);
    assertThat(single("0b1010").value()).isEqualTo(10L);
    Token l = single("42L");
    assertThat(l.kind()).isEqualTo(TokenKind.LONG_LITERAL);
    assertThat(l.value()).isEqualTo(42L);
    assertThat(single("0x7FFF_FFFF_FFFF_FFFFL").value()).isEqualTo(Long.MAX_VALUE);
    assertThat(single("2147483648").value()).isEqualTo(2147483648L);
    assertThat(diags.all()).isEmpty();
  }

  @Test
  void floatingForms() {
    assertThat(single("3.14").value()).isEqualTo(3.14);
    assertThat(single("3.14f").value()).isEqualTo(3.14f);
    assertThat(single("1e10").value()).isEqualTo(1e10);
    assertThat(single("5.97e24").value()).isEqualTo(5.97e24);
    assertThat(single("1E-5").value()).isEqualTo(1e-5);
    assertThat(single("2d").kind()).isEqualTo(TokenKind.DOUBLE_LITERAL);
    assertThat(single("2f").value()).isEqualTo(2f);
    assertThat(diags.all()).isEmpty();
  }

  @Test
  void rangeIsNotADecimalPoint() {
    assertThat(kinds("1..5"))
        .containsExactly(
            TokenKind.INT_LITERAL, TokenKind.DOTDOT, TokenKind.INT_LITERAL, TokenKind.EOF);
    assertThat(kinds("1.toString()"))
        .startsWith(TokenKind.INT_LITERAL, TokenKind.DOT, TokenKind.IDENTIFIER);
  }

  @Test
  void numberErrors() {
    lex("0123 1__ 0x 12abc 0x1_0000_0000");
    assertThat(codes())
        .containsExactly(
            Code.LEADING_ZERO,
            Code.MALFORMED_NUMBER,
            Code.MALFORMED_NUMBER,
            Code.MALFORMED_NUMBER,
            Code.NUMBER_TOO_LARGE);
  }

  @Test
  void stringsAndEscapes() {
    assertThat(single("\"a\\tb\\n\\\"q\\\" \\u0041 \\s\\\\\"").value())
        .isEqualTo("a\tb\n\"q\" A  \\");
    assertThat(single("'\\n'").value()).isEqualTo('\n');
    assertThat(single("'\\u0041'").value()).isEqualTo('A');
    assertThat(single("'\\''").value()).isEqualTo('\'');
  }

  @Test
  void rawStringsStripIndentation() {
    assertThat(single("\"\"\"\n    a\n      b\n    \"\"\"").value()).isEqualTo("a\n  b\n");
    assertThat(single("\"\"\"no \\n escapes\"\"\"").value()).isEqualTo("no \\n escapes");
    assertThat(single("\"\"\"\n  x\n  \"\"\"").value()).isEqualTo("x\n");
  }

  @Test
  void interpolationPieces() {
    Token t = single("$\"Hi {name}, {x + 1:F2} {{ok}}\"");
    assertThat(t.kind()).isEqualTo(TokenKind.INTERP_STRING);
    List<?> parts = (List<?>) t.value();
    assertThat(parts).hasSize(5);
    assertThat(parts.get(0)).isEqualTo(new InterpPiece.Text("Hi "));
    InterpPiece.Hole h = (InterpPiece.Hole) parts.get(3);
    assertThat(h.format()).isEqualTo("F2");
    assertThat(parts.get(4)).isEqualTo(new InterpPiece.Text(" {ok}"));
  }

  @Test
  void interpolationHoleMayContainStringsBracesAndMethodRefs() {
    Token t = single("$\"{map.get(\"}\")} {xs.map(String::trim)} {new int[] {1}[0]}\"");
    assertThat(diags.all()).isEmpty();
    assertThat((List<?>) t.value()).hasSize(5);
  }

  @Test
  void greaterThanIsAlwaysSingle() {
    assertThat(kinds("a >>= b >= c >>> d"))
        .containsExactly(
            TokenKind.IDENTIFIER,
            TokenKind.GT,
            TokenKind.GT,
            TokenKind.EQ,
            TokenKind.IDENTIFIER,
            TokenKind.GT,
            TokenKind.EQ,
            TokenKind.IDENTIFIER,
            TokenKind.GT,
            TokenKind.GT,
            TokenKind.GT,
            TokenKind.IDENTIFIER,
            TokenKind.EOF);
  }

  @Test
  void operators() {
    assertThat(kinds("?. ?? ??= ?.5 === !== :: .. => <<= !"))
        .containsExactly(
            TokenKind.QUESTION_DOT,
            TokenKind.QUESTION_QUESTION,
            TokenKind.QUESTION_QUESTION_EQ,
            TokenKind.QUESTION,
            TokenKind.DOT,
            TokenKind.INT_LITERAL,
            TokenKind.EQEQEQ,
            TokenKind.BANG_EQEQ,
            TokenKind.COLONCOLON,
            TokenKind.DOTDOT,
            TokenKind.ARROW,
            TokenKind.LTLT_EQ,
            TokenKind.BANG,
            TokenKind.EOF);
  }

  @Test
  void keywordsAndContextualWords() {
    assertThat(kinds("class var val record is in out"))
        .containsExactly(
            TokenKind.CLASS,
            TokenKind.IDENTIFIER,
            TokenKind.IDENTIFIER,
            TokenKind.IDENTIFIER,
            TokenKind.IDENTIFIER,
            TokenKind.IDENTIFIER,
            TokenKind.IDENTIFIER,
            TokenKind.EOF);
    Token esc = single("`class`");
    assertThat(esc.kind()).isEqualTo(TokenKind.IDENTIFIER);
    assertThat(esc.text()).isEqualTo("class");
    assertThat(esc.escaped()).isTrue();
    assertThat(esc.isContextual("class")).isFalse();
  }

  @Test
  void commentsAreSkipped() {
    assertThat(kinds("a // line\n/* block */ b /// doc\n"))
        .containsExactly(TokenKind.IDENTIFIER, TokenKind.IDENTIFIER, TokenKind.EOF);
  }

  @Test
  void positionsAreAbsolute() {
    List<Token> ts = lex("ab  cd");
    assertThat(ts.get(1).start()).isEqualTo(4);
    assertThat(ts.get(1).end()).isEqualTo(6);
  }

  @Test
  void unicodeIdentifiers() {
    Token t = single("café");
    assertThat(t.kind()).isEqualTo(TokenKind.IDENTIFIER);
    assertThat(t.text()).isEqualTo("café");
  }

  @Test
  void errorTokensAreMarkedMalformed() {
    List<Token> ts = lex("\"open");
    assertThat(ts.getFirst().malformed()).isTrue();
    assertThat(codes()).containsExactly(Code.UNTERMINATED_STRING);
  }
}

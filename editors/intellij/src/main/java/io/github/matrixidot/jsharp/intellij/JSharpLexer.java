package io.github.matrixidot.jsharp.intellij;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.syntax.InterpPiece;
import io.github.matrixidot.jsharp.compiler.syntax.Lexer;
import io.github.matrixidot.jsharp.compiler.syntax.Token;
import io.github.matrixidot.jsharp.compiler.syntax.TokenKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The editor's lexer: the J# compiler's own lexer, so colors match how J# reads the code. Gaps
 * between compiler tokens become whitespace and comments; interpolation holes are lexed as code.
 *
 * <p>The whole buffer is lexed at once. Only the first token reports the initial state, so the
 * editor restarts from the beginning after a change (fast for source-file sizes).
 */
final class JSharpLexer extends LexerBase {
  /** Words the compiler treats as keywords in context (lexed as identifiers). */
  static final Set<String> CONTEXTUAL =
      Set.of(
          "var", "val", "record", "init", "get", "set", "base", "override", "sealed", "async",
          "await", "required", "operator", "atomic", "when", "with", "nameof", "typeof", "field",
          "permits", "where", "checked", "open", "params", "value");

  private record Tok(IElementType type, int start, int end) {}

  private CharSequence buffer;
  private int endOffset;
  private List<Tok> tokens = List.of();
  private int index;

  @Override
  public void start(CharSequence buffer, int startOffset, int endOffset, int initialState) {
    this.buffer = buffer;
    this.endOffset = endOffset;
    this.tokens = lex(buffer.subSequence(0, endOffset).toString(), startOffset);
    this.index = 0;
  }

  private static List<Tok> lex(String text, int from) {
    List<Tok> out = new ArrayList<>();
    SourceFile file = new SourceFile("editor.jsharp", text);
    List<Token> ts = new Lexer(file, from, text.length(), d -> {}).tokenize();
    int pos = from;
    for (int i = 0; i < ts.size(); i++) {
      Token t = ts.get(i);
      gap(text, pos, t.start(), out);
      pos = t.start();
      if (t.kind() == TokenKind.EOF) {
        break;
      }
      if (t.kind() == TokenKind.INTERP_STRING && t.value() instanceof List<?> pieces) {
        interpolated(text, t, pieces, out);
      } else {
        IElementType type = typeOf(t);
        if (type == JSharpTokens.ANNOTATION
            && i + 1 < ts.size()
            && ts.get(i + 1).kind() == TokenKind.IDENTIFIER
            && ts.get(i + 1).start() == t.end()) {
          Token name = ts.get(++i);
          out.add(new Tok(JSharpTokens.ANNOTATION, t.start(), name.end()));
          pos = name.end();
          continue;
        }
        out.add(new Tok(type, t.start(), t.end()));
      }
      pos = t.end();
    }
    gap(text, pos, text.length(), out);
    return out;
  }

  /** {@code $"text {hole} text"}: string parts, then each hole's code between INTERPOLATION braces. */
  private static void interpolated(String text, Token t, List<?> pieces, List<Tok> out) {
    int pos = t.start();
    for (Object p : pieces) {
      if (p instanceof InterpPiece.Hole h && h.start() >= pos && h.end() <= t.end()) {
        if (h.start() > pos) {
          out.add(new Tok(JSharpTokens.STRING, pos, h.start()));
        }
        out.add(new Tok(JSharpTokens.INTERPOLATION, h.start(), h.start() + 1));
        if (h.exprEnd() > h.exprStart()) {
          for (Tok inner : lex(text.substring(0, h.exprEnd()), h.exprStart())) {
            out.add(inner);
          }
        }
        if (h.end() - 1 > h.exprEnd()) {
          out.add(new Tok(JSharpTokens.STRING, h.exprEnd(), h.end() - 1)); // ":F2" formats
        }
        out.add(new Tok(JSharpTokens.INTERPOLATION, h.end() - 1, h.end()));
        pos = h.end();
      }
    }
    if (pos < t.end()) {
      out.add(new Tok(JSharpTokens.STRING, pos, t.end()));
    }
  }

  /** Whitespace and comments between tokens. */
  private static void gap(String text, int from, int to, List<Tok> out) {
    int i = from;
    while (i < to) {
      char c = text.charAt(i);
      int start = i;
      if (Character.isWhitespace(c)) {
        while (i < to && Character.isWhitespace(text.charAt(i))) {
          i++;
        }
        out.add(new Tok(JSharpTokens.WHITE_SPACE, start, i));
      } else if (c == '/' && i + 1 < to && text.charAt(i + 1) == '/') {
        while (i < to && text.charAt(i) != '\n') {
          i++;
        }
        out.add(new Tok(JSharpTokens.LINE_COMMENT, start, i));
      } else if (c == '/' && i + 1 < to && text.charAt(i + 1) == '*') {
        int end = text.indexOf("*/", i + 2);
        i = end < 0 || end + 2 > to ? to : end + 2;
        boolean doc = start + 2 < i && text.charAt(start + 2) == '*' && i - start > 4;
        out.add(new Tok(doc ? JSharpTokens.DOC_COMMENT : JSharpTokens.BLOCK_COMMENT, start, i));
      } else {
        i++;
        out.add(new Tok(JSharpTokens.BAD_CHARACTER, start, i));
      }
    }
  }

  private static IElementType typeOf(Token t) {
    TokenKind k = t.kind();
    if (k.isKeyword()) {
      return JSharpTokens.KEYWORD;
    }
    return switch (k) {
      case IDENTIFIER ->
          !t.escaped() && CONTEXTUAL.contains(t.text())
              ? JSharpTokens.KEYWORD
              : JSharpTokens.IDENTIFIER;
      case INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL -> JSharpTokens.NUMBER;
      case STRING_LITERAL, INTERP_STRING -> JSharpTokens.STRING;
      case CHAR_LITERAL -> JSharpTokens.CHAR;
      case LBRACE -> JSharpTokens.LBRACE;
      case RBRACE -> JSharpTokens.RBRACE;
      case LPAREN -> JSharpTokens.LPAREN;
      case RPAREN -> JSharpTokens.RPAREN;
      case LBRACKET -> JSharpTokens.LBRACKET;
      case RBRACKET -> JSharpTokens.RBRACKET;
      case SEMI -> JSharpTokens.SEMI;
      case COMMA -> JSharpTokens.COMMA;
      case DOT -> JSharpTokens.DOT;
      case AT -> JSharpTokens.ANNOTATION;
      case ERROR -> JSharpTokens.BAD_CHARACTER;
      default -> JSharpTokens.OPERATOR;
    };
  }

  @Override
  public int getState() {
    return index == 0 ? 0 : 1;
  }

  @Override
  public IElementType getTokenType() {
    return index < tokens.size() ? tokens.get(index).type() : null;
  }

  @Override
  public int getTokenStart() {
    return index < tokens.size() ? tokens.get(index).start() : endOffset;
  }

  @Override
  public int getTokenEnd() {
    return index < tokens.size() ? tokens.get(index).end() : endOffset;
  }

  @Override
  public void advance() {
    index++;
  }

  @Override
  public CharSequence getBufferSequence() {
    return buffer;
  }

  @Override
  public int getBufferEnd() {
    return endOffset;
  }
}

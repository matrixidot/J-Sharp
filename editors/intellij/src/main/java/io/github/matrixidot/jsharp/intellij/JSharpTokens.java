package io.github.matrixidot.jsharp.intellij;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;

/** Token types of the editor lexer (see {@link JSharpLexer}). */
final class JSharpTokens {
  private JSharpTokens() {}

  static IElementType token(String name) {
    return new IElementType(name, JSharpLanguage.INSTANCE);
  }

  static final IElementType WHITE_SPACE = TokenType.WHITE_SPACE;
  static final IElementType BAD_CHARACTER = TokenType.BAD_CHARACTER;
  static final IElementType LINE_COMMENT = token("LINE_COMMENT");
  static final IElementType BLOCK_COMMENT = token("BLOCK_COMMENT");
  static final IElementType DOC_COMMENT = token("DOC_COMMENT");
  static final IElementType KEYWORD = token("KEYWORD");
  static final IElementType IDENTIFIER = token("IDENTIFIER");
  static final IElementType NUMBER = token("NUMBER");
  static final IElementType STRING = token("STRING");
  static final IElementType CHAR = token("CHAR");
  /** The {@code {} and {@code }} around an interpolation hole. */
  static final IElementType INTERPOLATION = token("INTERPOLATION");
  static final IElementType ANNOTATION = token("ANNOTATION");
  static final IElementType LBRACE = token("LBRACE");
  static final IElementType RBRACE = token("RBRACE");
  static final IElementType LPAREN = token("LPAREN");
  static final IElementType RPAREN = token("RPAREN");
  static final IElementType LBRACKET = token("LBRACKET");
  static final IElementType RBRACKET = token("RBRACKET");
  static final IElementType SEMI = token("SEMI");
  static final IElementType COMMA = token("COMMA");
  static final IElementType DOT = token("DOT");
  static final IElementType OPERATOR = token("OPERATOR");

  static final TokenSet COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT);
  static final TokenSet STRINGS = TokenSet.create(STRING, CHAR);
}

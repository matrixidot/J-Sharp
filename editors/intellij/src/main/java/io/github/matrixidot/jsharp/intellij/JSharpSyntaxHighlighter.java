package io.github.matrixidot.jsharp.intellij;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import java.util.Map;

/** Token colors, from the IDE's default language palette (so every color scheme works). */
public final class JSharpSyntaxHighlighter extends SyntaxHighlighterBase {
  private static TextAttributesKey key(String name, TextAttributesKey base) {
    return TextAttributesKey.createTextAttributesKey("JSHARP_" + name, base);
  }

  private static final Map<IElementType, TextAttributesKey> KEYS =
      Map.ofEntries(
          Map.entry(JSharpTokens.KEYWORD, key("KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)),
          Map.entry(JSharpTokens.NUMBER, key("NUMBER", DefaultLanguageHighlighterColors.NUMBER)),
          Map.entry(JSharpTokens.STRING, key("STRING", DefaultLanguageHighlighterColors.STRING)),
          Map.entry(JSharpTokens.CHAR, key("CHAR", DefaultLanguageHighlighterColors.STRING)),
          Map.entry(
              JSharpTokens.INTERPOLATION,
              key("INTERPOLATION", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE)),
          Map.entry(
              JSharpTokens.LINE_COMMENT,
              key("LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)),
          Map.entry(
              JSharpTokens.BLOCK_COMMENT,
              key("BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)),
          Map.entry(
              JSharpTokens.DOC_COMMENT,
              key("DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)),
          Map.entry(
              JSharpTokens.ANNOTATION, key("ANNOTATION", DefaultLanguageHighlighterColors.METADATA)),
          Map.entry(JSharpTokens.LBRACE, key("BRACES", DefaultLanguageHighlighterColors.BRACES)),
          Map.entry(JSharpTokens.RBRACE, key("BRACES", DefaultLanguageHighlighterColors.BRACES)),
          Map.entry(JSharpTokens.LPAREN, key("PARENS", DefaultLanguageHighlighterColors.PARENTHESES)),
          Map.entry(JSharpTokens.RPAREN, key("PARENS", DefaultLanguageHighlighterColors.PARENTHESES)),
          Map.entry(JSharpTokens.LBRACKET, key("BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)),
          Map.entry(JSharpTokens.RBRACKET, key("BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)),
          Map.entry(JSharpTokens.SEMI, key("SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)),
          Map.entry(JSharpTokens.COMMA, key("COMMA", DefaultLanguageHighlighterColors.COMMA)),
          Map.entry(JSharpTokens.DOT, key("DOT", DefaultLanguageHighlighterColors.DOT)),
          Map.entry(
              JSharpTokens.OPERATOR,
              key("OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)),
          Map.entry(JSharpTokens.BAD_CHARACTER, key("BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)));

  @Override
  public Lexer getHighlightingLexer() {
    return new JSharpLexer();
  }

  @Override
  public TextAttributesKey[] getTokenHighlights(IElementType type) {
    TextAttributesKey k = KEYS.get(type);
    return k == null ? TextAttributesKey.EMPTY_ARRAY : new TextAttributesKey[] {k};
  }
}

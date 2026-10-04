package io.github.matrixidot.jsharp.intellij;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiFile;

/**
 * Enter in J#: keeps the line's indentation, indents one level after an opening brace, and between
 * {@code {} and {@code }} puts the closing brace on its own line with the cursor on an indented
 * line before it. After an unmatched {@code {}, it adds the {@code }}.
 */
public final class JSharpEnterHandler extends EnterHandlerDelegateAdapter {
  @Override
  public Result preprocessEnter(
      PsiFile file,
      Editor editor,
      Ref<Integer> caretOffset,
      Ref<Integer> caretAdvance,
      DataContext dataContext,
      EditorActionHandler originalHandler) {
    if (!(file instanceof JSharpFile) || editor.getSelectionModel().hasSelection()) {
      return Result.Continue;
    }
    Document doc = editor.getDocument();
    CharSequence text = doc.getCharsSequence();
    int caret = caretOffset.get();
    int lineStart = doc.getLineStartOffset(doc.getLineNumber(caret));
    String indent = leadingWhitespace(text, lineStart, caret);
    int before = caret - 1;
    while (before >= lineStart && isBlank(text.charAt(before))) {
      before--;
    }
    int after = caret;
    while (after < text.length() && isBlank(text.charAt(after))) {
      after++;
    }
    char open = before >= lineStart ? text.charAt(before) : '\n';
    char next = after < text.length() ? text.charAt(after) : '\n';
    boolean opens = open == '{' || open == '(' || open == '[';
    boolean closesRightAfter =
        open == '{' && next == '}' || open == '(' && next == ')' || open == '[' && next == ']';
    boolean addClosingBrace =
        open == '{' && !closesRightAfter && (next == '\n') && unmatchedOpenBraces(text) > 0;
    String inner = opens ? indent + indentUnit(file) : indent;
    StringBuilder insert = new StringBuilder("\n").append(inner);
    int newCaret = before + 1 + insert.length();
    if (closesRightAfter) {
      insert.append('\n').append(indent);
    } else if (addClosingBrace) {
      insert.append('\n').append(indent).append('}');
    }
    // Replace the blanks around the caret (trailing ones on this line, leading ones on the next).
    doc.replaceString(before + 1, after, insert);
    editor.getCaretModel().moveToOffset(newCaret);
    editor.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.RELATIVE);
    return Result.Stop;
  }

  private static boolean isBlank(char c) {
    return c == ' ' || c == '\t';
  }

  private static String leadingWhitespace(CharSequence text, int lineStart, int limit) {
    int i = lineStart;
    while (i < limit && isBlank(text.charAt(i))) {
      i++;
    }
    return text.subSequence(lineStart, i).toString();
  }

  static String indentUnit(PsiFile file) {
    var options = CodeStyle.getIndentOptions(file);
    return options.USE_TAB_CHARACTER ? "\t" : " ".repeat(Math.max(1, options.INDENT_SIZE));
  }

  /** '{' minus '}' over the file's tokens (braces inside strings and comments do not count). */
  private static int unmatchedOpenBraces(CharSequence text) {
    JSharpLexer lexer = new JSharpLexer();
    lexer.start(text, 0, text.length(), 0);
    int depth = 0;
    for (; lexer.getTokenType() != null; lexer.advance()) {
      if (lexer.getTokenType() == JSharpTokens.LBRACE) {
        depth++;
      } else if (lexer.getTokenType() == JSharpTokens.RBRACE) {
        depth--;
      }
    }
    return depth;
  }
}

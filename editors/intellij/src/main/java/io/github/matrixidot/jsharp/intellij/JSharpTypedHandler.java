package io.github.matrixidot.jsharp.intellij;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;

/** Typing {@code }} alone on a line lines it up with the line of its {@code {}. */
public final class JSharpTypedHandler extends TypedHandlerDelegate {
  @Override
  public Result charTyped(char c, Project project, Editor editor, PsiFile file) {
    if (c != '}' || !(file instanceof JSharpFile)) {
      return Result.CONTINUE;
    }
    Document doc = editor.getDocument();
    CharSequence text = doc.getCharsSequence();
    int brace = editor.getCaretModel().getOffset() - 1;
    if (brace < 0 || text.charAt(brace) != '}') {
      return Result.CONTINUE;
    }
    int lineStart = doc.getLineStartOffset(doc.getLineNumber(brace));
    for (int i = lineStart; i < brace; i++) {
      if (text.charAt(i) != ' ' && text.charAt(i) != '\t') {
        return Result.CONTINUE; // not alone on its line
      }
    }
    int open = matchingOpen(text, brace);
    if (open < 0) {
      return Result.CONTINUE;
    }
    int openLine = doc.getLineStartOffset(doc.getLineNumber(open));
    int i = openLine;
    while (i < open && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
      i++;
    }
    String indent = text.subSequence(openLine, i).toString();
    doc.replaceString(lineStart, brace, indent);
    editor.getCaretModel().moveToOffset(lineStart + indent.length() + 1);
    return Result.STOP;
  }

  /** The '{' matching the '}' at {@code close}, by tokens (strings and comments skipped). */
  private static int matchingOpen(CharSequence text, int close) {
    JSharpLexer lexer = new JSharpLexer();
    lexer.start(text, 0, close, 0);
    java.util.ArrayDeque<Integer> opens = new java.util.ArrayDeque<>();
    for (; lexer.getTokenType() != null; lexer.advance()) {
      if (lexer.getTokenType() == JSharpTokens.LBRACE) {
        opens.push(lexer.getTokenStart());
      } else if (lexer.getTokenType() == JSharpTokens.RBRACE && !opens.isEmpty()) {
        opens.pop();
      }
    }
    return opens.isEmpty() ? -1 : opens.peek();
  }
}

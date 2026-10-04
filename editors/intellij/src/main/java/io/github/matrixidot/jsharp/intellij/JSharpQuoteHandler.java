package io.github.matrixidot.jsharp.intellij;

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler;

/** Auto-closing of {@code "} and {@code '}. */
public final class JSharpQuoteHandler extends SimpleTokenSetQuoteHandler {
  public JSharpQuoteHandler() {
    super(JSharpTokens.STRING, JSharpTokens.CHAR);
  }
}

package io.github.matrixidot.jsharp.intellij;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;

/** {@code {}}, {@code ()} and {@code []}: highlighting of the pair, and auto-closing. */
public final class JSharpBraceMatcher implements PairedBraceMatcher {
  private static final BracePair[] PAIRS = {
    new BracePair(JSharpTokens.LBRACE, JSharpTokens.RBRACE, true),
    new BracePair(JSharpTokens.LPAREN, JSharpTokens.RPAREN, false),
    new BracePair(JSharpTokens.LBRACKET, JSharpTokens.RBRACKET, false),
  };

  @Override
  public BracePair[] getPairs() {
    return PAIRS;
  }

  @Override
  public boolean isPairedBracesAllowedBeforeType(IElementType lbrace, IElementType next) {
    return true;
  }

  @Override
  public int getCodeConstructStart(PsiFile file, int openingBraceOffset) {
    return openingBraceOffset;
  }
}

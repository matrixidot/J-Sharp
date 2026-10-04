package io.github.matrixidot.jsharp.intellij;

import com.intellij.lang.Commenter;

/** Ctrl+/ and Ctrl+Shift+/. */
public final class JSharpCommenter implements Commenter {
  @Override
  public String getLineCommentPrefix() {
    return "//";
  }

  @Override
  public String getBlockCommentPrefix() {
    return "/*";
  }

  @Override
  public String getBlockCommentSuffix() {
    return "*/";
  }

  @Override
  public String getCommentedBlockCommentPrefix() {
    return null;
  }

  @Override
  public String getCommentedBlockCommentSuffix() {
    return null;
  }
}

package io.github.matrixidot.jsharp.intellij;

import com.intellij.lang.Language;

/** The J# language. */
public final class JSharpLanguage extends Language {
  public static final JSharpLanguage INSTANCE = new JSharpLanguage();

  private JSharpLanguage() {
    super("JSharp");
  }

  @Override
  public String getDisplayName() {
    return "J#";
  }
}

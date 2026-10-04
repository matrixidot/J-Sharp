package io.github.matrixidot.jsharp.intellij;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.util.IconLoader;
import javax.swing.Icon;

/** {@code .jsharp} files. */
public final class JSharpFileType extends LanguageFileType {
  public static final JSharpFileType INSTANCE = new JSharpFileType();
  static final Icon ICON = IconLoader.getIcon("/icons/jsharp.svg", JSharpFileType.class);

  private JSharpFileType() {
    super(JSharpLanguage.INSTANCE);
  }

  @Override
  public String getName() {
    return "J#";
  }

  @Override
  public String getDescription() {
    return "J# source";
  }

  @Override
  public String getDefaultExtension() {
    return JSharpPlugin.EXTENSION;
  }

  @Override
  public Icon getIcon() {
    return ICON;
  }
}

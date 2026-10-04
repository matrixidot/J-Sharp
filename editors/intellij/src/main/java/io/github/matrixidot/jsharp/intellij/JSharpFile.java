package io.github.matrixidot.jsharp.intellij;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;

/** A {@code .jsharp} file: a flat token tree (analysis is done by the J# language server). */
public final class JSharpFile extends PsiFileBase {
  JSharpFile(FileViewProvider viewProvider) {
    super(viewProvider, JSharpLanguage.INSTANCE);
  }

  @Override
  public FileType getFileType() {
    return JSharpFileType.INSTANCE;
  }

  @Override
  public String toString() {
    return "J# file";
  }
}

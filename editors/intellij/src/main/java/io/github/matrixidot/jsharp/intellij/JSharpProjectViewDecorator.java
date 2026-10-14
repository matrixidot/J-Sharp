package io.github.matrixidot.jsharp.intellij;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.psi.PsiFile;

/**
 * Like Java's: a file declaring the type it is named after shows as that type ({@code Point}
 * with a record icon); other J# files (scripts, functions) keep their file name.
 */
public final class JSharpProjectViewDecorator implements ProjectViewNodeDecorator {
  @Override
  public void decorate(ProjectViewNode<?> node, PresentationData data) {
    if (!(node.getValue() instanceof PsiFile file)
        || !JSharpPlugin.isJSharp(file.getVirtualFile())) {
      return;
    }
    var kind = JSharpFiles.mainType(file);
    if (kind != null) {
      data.setPresentableText(file.getVirtualFile().getNameWithoutExtension());
      data.setIcon(JSharpFiles.icon(kind));
    }
  }
}

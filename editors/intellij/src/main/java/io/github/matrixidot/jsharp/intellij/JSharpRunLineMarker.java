package io.github.matrixidot.jsharp.intellij;

import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;

/** The green ▶ in the gutter at a program's entry point (first statement or main). */
public final class JSharpRunLineMarker extends RunLineMarkerContributor {
  @Override
  public Info getInfo(PsiElement element) {
    if (element.getFirstChild() != null
        || element.getNode().getElementType() == TokenType.WHITE_SPACE
        || !(element.getContainingFile() instanceof JSharpFile file)
        || element.getTextRange().getStartOffset() != entryOffset(file)) {
      return null;
    }
    return new Info(
        AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(1), e -> "Run program");
  }

  /** The entry point's offset in {@code file}, cached until the file changes; -1 if none. */
  static int entryOffset(PsiFile file) {
    return CachedValuesManager.getCachedValue(
        file,
        () ->
            CachedValueProvider.Result.create(
                JSharpPrograms.entryOffset(file.getText()),
                PsiModificationTracker.MODIFICATION_COUNT));
  }
}

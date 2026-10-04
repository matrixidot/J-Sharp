package io.github.matrixidot.jsharp.intellij;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

/** "Run 'main.jsharp'" from the editor, the gutter, or the project view. */
public final class JSharpRunConfigurationProducer
    extends LazyRunConfigurationProducer<JSharpRunConfiguration> {
  @Override
  public ConfigurationFactory getConfigurationFactory() {
    return JSharpRunConfigurationType.getInstance().factory();
  }

  @Override
  protected boolean setupConfigurationFromContext(
      JSharpRunConfiguration configuration,
      ConfigurationContext context,
      Ref<PsiElement> sourceElement) {
    VirtualFile file = programFile(context);
    if (file == null) {
      return false;
    }
    configuration.setFile(file.getPath());
    configuration.setName(file.getName());
    return true;
  }

  @Override
  public boolean isConfigurationFromContext(
      JSharpRunConfiguration configuration, ConfigurationContext context) {
    VirtualFile file = programFile(context);
    return file != null && file.getPath().equals(configuration.getFile());
  }

  /** The context's .jsharp file, if it is a program (has an entry point). */
  private static VirtualFile programFile(ConfigurationContext context) {
    PsiElement element = context.getPsiLocation();
    PsiFile psi = element == null ? null : element.getContainingFile();
    if (!(psi instanceof JSharpFile) || psi.getVirtualFile() == null) {
      return null;
    }
    return JSharpRunLineMarker.entryOffset(psi) >= 0 ? psi.getVirtualFile() : null;
  }
}

package io.github.matrixidot.jsharp.intellij;

import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.ide.wizard.GeneratorNewProjectWizard;
import com.intellij.ide.wizard.NewProjectWizardBaseStep;
import com.intellij.ide.wizard.NewProjectWizardChainStep;
import com.intellij.ide.wizard.NewProjectWizardStep;
import com.intellij.ide.wizard.RootNewProjectWizardStep;
import javax.swing.Icon;

/**
 * File | New | Project | J#: the templates of {@code jsharp new} (an application, a library, a
 * Paper plugin, a script), with the project's name, location and package.
 */
public final class JSharpNewProjectWizard implements GeneratorNewProjectWizard {
  @Override
  public String getId() {
    return "jsharp";
  }

  @Override
  public String getName() {
    return "J#";
  }

  @Override
  public Icon getIcon() {
    return JSharpFileType.ICON;
  }

  @Override
  public String getDescription() {
    return "A J# application, library, Minecraft (Paper) plugin or script.";
  }

  @Override
  public NewProjectWizardStep createStep(WizardContext context) {
    return new NewProjectWizardChainStep<>(new RootNewProjectWizardStep(context))
        .nextStep(NewProjectWizardBaseStep::new)
        .nextStep(JSharpNewProjectStep::new);
  }
}

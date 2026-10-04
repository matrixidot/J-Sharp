package io.github.matrixidot.jsharp.intellij;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;

/** Run configurations for J# programs. */
public final class JSharpRunConfigurationType extends ConfigurationTypeBase {
  static final String ID = "JSharpRunConfiguration";

  public JSharpRunConfigurationType() {
    super(ID, "J#", "Run a J# program", JSharpFileType.ICON);
    addFactory(
        new ConfigurationFactory(this) {
          @Override
          public String getId() {
            return ID;
          }

          @Override
          public RunConfiguration createTemplateConfiguration(Project project) {
            return new JSharpRunConfiguration(project, this, "J#");
          }
        });
  }

  static JSharpRunConfigurationType getInstance() {
    return ConfigurationTypeUtil.findConfigurationType(JSharpRunConfigurationType.class);
  }

  ConfigurationFactory factory() {
    return getConfigurationFactories()[0];
  }
}

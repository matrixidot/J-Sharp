package io.github.matrixidot.jsharp.intellij;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.util.ui.FormBuilder;
import javax.swing.JComponent;

/** The editor in Run | Edit Configurations: the program file and its arguments. */
final class JSharpRunSettingsEditor extends SettingsEditor<JSharpRunConfiguration> {
  private final TextFieldWithBrowseButton file = new TextFieldWithBrowseButton();
  private final RawCommandLineEditor arguments = new RawCommandLineEditor();

  JSharpRunSettingsEditor(Project project) {
    file.addBrowseFolderListener(
        project,
        FileChooserDescriptorFactory.createSingleFileDescriptor(JSharpPlugin.EXTENSION)
            .withTitle("J# Program")
            .withDescription("The .jsharp file with the program's top-level statements or main"));
  }

  @Override
  protected void resetEditorFrom(JSharpRunConfiguration c) {
    file.setText(c.getFile());
    arguments.setText(c.getArguments());
  }

  @Override
  protected void applyEditorTo(JSharpRunConfiguration c) {
    c.setFile(file.getText().trim());
    c.setArguments(arguments.getText());
  }

  @Override
  protected JComponent createEditor() {
    return FormBuilder.createFormBuilder()
        .addLabeledComponent("Program file:", file)
        .addLabeledComponent("Program arguments:", arguments)
        .getPanel();
  }
}

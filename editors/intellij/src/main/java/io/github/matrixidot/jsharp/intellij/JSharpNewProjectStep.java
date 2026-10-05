package io.github.matrixidot.jsharp.intellij;

import com.intellij.ide.wizard.AbstractNewProjectWizardStep;
import com.intellij.ide.wizard.NewProjectWizardBaseData;
import com.intellij.ide.wizard.NewProjectWizardStep;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.dsl.builder.Panel;
import io.github.matrixidot.jsharp.cli.ProjectTemplates;
import io.github.matrixidot.jsharp.cli.ProjectTemplates.Template;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import kotlin.Unit;

/** The J# part of the New Project wizard: template and package; then creates the files. */
final class JSharpNewProjectStep extends AbstractNewProjectWizardStep {
  private final NewProjectWizardBaseData base;
  private ComboBox<Template> template;
  private JBTextField packageField;

  JSharpNewProjectStep(NewProjectWizardStep parent) {
    super(parent);
    this.base = (NewProjectWizardBaseData) parent;
  }

  @Override
  public void setupUI(Panel builder) {
    builder.row(
        "Template:",
        row -> {
          template =
              row.comboBox(
                      List.of(Template.APP, Template.PAPER, Template.LIBRARY, Template.SCRIPT),
                      new SimpleListCellRenderer<Template>() {
                        @Override
                        public void customize(
                            javax.swing.JList<? extends Template> list,
                            Template t,
                            int index,
                            boolean selected,
                            boolean focused) {
                          setText(t == null ? "" : label(t) + ": " + t.description);
                        }
                      })
                  .getComponent();
          return Unit.INSTANCE;
        });
    builder.row(
        "Package:",
        row -> {
          packageField =
              row.textField()
                  .validationInfo(
                      (v, field) -> {
                        String problem = ProjectTemplates.checkPackage(packageName());
                        return problem == null ? null : new ValidationInfo(problem, field);
                      })
                  .getComponent();
          packageField.getEmptyText().setText("default: com.example.<name>");
          packageField.setColumns(30);
          return Unit.INSTANCE;
        });
  }

  private static String label(Template t) {
    return switch (t) {
      case APP -> "Application";
      case LIBRARY -> "Library";
      case PAPER -> "Paper plugin";
      case SCRIPT -> "Script";
    };
  }

  private String packageName() {
    String typed = packageField == null ? "" : packageField.getText().strip();
    return typed.isEmpty() ? ProjectTemplates.defaultPackage(base.getName()) : typed;
  }

  @Override
  public void setupProject(Project project) {
    Template t = (Template) template.getSelectedItem();
    var p = new ProjectTemplates.Project(base.getName(), packageName());
    Path dir = Path.of(base.getPath()).resolve(base.getName());
    try {
      ProjectTemplates.create(t, p, dir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir);
    if (root != null) {
      VfsUtil.markDirtyAndRefresh(false, true, true, root);
    }
    String main = ProjectTemplates.mainFile(t, p);
    StartupManager.getInstance(project)
        .runAfterOpened(
            () -> {
              if (t != Template.SCRIPT) {
                JSharpGradle.link(project, dir);
              }
              VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve(main));
              if (file != null) {
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    .invokeLater(
                        () -> FileEditorManager.getInstance(project).openFile(file, true),
                        project.getDisposed());
              }
            });
  }
}

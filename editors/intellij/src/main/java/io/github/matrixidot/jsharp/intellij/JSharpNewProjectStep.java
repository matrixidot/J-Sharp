package io.github.matrixidot.jsharp.intellij;

import com.intellij.ide.wizard.AbstractNewProjectWizardStep;
import com.intellij.ide.wizard.NewProjectWizardBaseData;
import com.intellij.ide.wizard.NewProjectWizardStep;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.dsl.builder.Panel;
import com.intellij.ui.layout.ValidationInfoBuilder;
import io.github.matrixidot.jsharp.cli.ProjectTemplates;
import io.github.matrixidot.jsharp.cli.ProjectTemplates.Template;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import kotlin.Unit;

/**
 * The J# part of the New Project wizard: template, package and the Java version to target; then
 * creates the files.
 */
final class JSharpNewProjectStep extends AbstractNewProjectWizardStep {
  private final NewProjectWizardBaseData base;
  private ComboBox<Template> template;
  private ComboBox<Integer> javaRelease;
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
                      List.of(
                          Template.APP,
                          Template.NEOFORGE,
                          Template.PAPER,
                          Template.LIBRARY,
                          Template.SCRIPT),
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
        "Java:",
        row -> {
          javaRelease =
              row.comboBox(
                      ProjectTemplates.javaReleases(),
                      new SimpleListCellRenderer<Integer>() {
                        @Override
                        public void customize(
                            javax.swing.JList<? extends Integer> list,
                            Integer release,
                            int index,
                            boolean selected,
                            boolean focused) {
                          setText(release == null ? "" : javaLabel(release));
                        }
                      })
                  .getComponent();
          return Unit.INSTANCE;
        });
    builder.row(
        "Package:",
        row -> {
          kotlin.jvm.functions.Function2<ValidationInfoBuilder, JBTextField, ValidationInfo> check =
              (v, field) -> {
                String problem = ProjectTemplates.checkPackage(packageName());
                return problem == null ? null : new ValidationInfo(problem, field);
              };
          packageField = row.textField().validationOnInput(check).validationOnApply(check).getComponent();
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
      case NEOFORGE -> "NeoForge mod";
      case SCRIPT -> "Script";
    };
  }

  /** "Java 21 (Minecraft 1.21.1 NeoForge, 1.21.11 Paper)": what the release means for mods. */
  private static String javaLabel(int release) {
    var mc = ProjectTemplates.Minecraft.forJava(release);
    String minecraft =
        mc.neoForgeMinecraft().equals(mc.paper())
            ? "Minecraft " + mc.paper()
            : "Minecraft " + mc.neoForgeMinecraft() + " NeoForge, " + mc.paper() + " Paper";
    return "Java " + release + " (" + minecraft + ")";
  }

  private String packageName() {
    String typed = packageField == null ? "" : packageField.getText().strip();
    return typed.isEmpty() ? ProjectTemplates.defaultPackage(base.getName()) : typed;
  }

  @Override
  public void setupProject(Project project) {
    Template t = (Template) template.getSelectedItem();
    var p =
        new ProjectTemplates.Project(
            base.getName(), packageName(), (Integer) javaRelease.getSelectedItem());
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
    JSharpProjectOpened.whenOpened(
        project,
        () -> {
          if (t != Template.SCRIPT) {
            JSharpGradle.link(project, dir);
          }
          VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve(main));
          if (file != null) {
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .invokeLater(
                    () -> new OpenFileDescriptor(project, file).navigate(true),
                    project.getDisposed());
          }
        });
  }
}

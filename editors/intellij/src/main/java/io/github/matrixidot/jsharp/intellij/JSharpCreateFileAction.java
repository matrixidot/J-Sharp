package io.github.matrixidot.jsharp.intellij;

import com.intellij.ide.actions.CreateFileFromTemplateAction;
import com.intellij.ide.actions.CreateFileFromTemplateDialog;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import java.util.Map;

/** New | J# File: a class, record, interface, enum or script, in the folder's package. */
public final class JSharpCreateFileAction extends CreateFileFromTemplateAction implements DumbAware {
  public JSharpCreateFileAction() {
    super(
        () -> "J# File",
        () -> "Create a J# class, record, interface, enum or script",
        JSharpFileType.ICON);
  }

  @Override
  protected void buildDialog(
      Project project, PsiDirectory directory, CreateFileFromTemplateDialog.Builder builder) {
    builder
        .setTitle("New J# File")
        .addKind("Class", JSharpFiles.icon(Decl.TypeKind.CLASS), "J# Class")
        .addKind("Record", JSharpFiles.icon(Decl.TypeKind.RECORD), "J# Record")
        .addKind("Interface", JSharpFiles.icon(Decl.TypeKind.INTERFACE), "J# Interface")
        .addKind("Enum", JSharpFiles.icon(Decl.TypeKind.ENUM), "J# Enum")
        .addKind("Script (top-level statements)", JSharpFileType.ICON, "J# Script");
  }

  @Override
  protected String getActionName(PsiDirectory directory, String newName, String templateName) {
    return "Create J# File " + newName;
  }

  @Override
  protected PsiFile createFileFromTemplate(String name, FileTemplate template, PsiDirectory dir) {
    return createFileFromTemplate(
        name, template, dir, null, true, Map.of(), Map.of("PACKAGE_NAME", packageOf(dir)));
  }

  /**
   * The folder's package: from its source root ({@code src/main/jsharp/com/example} is {@code
   * com.example}), else none.
   */
  static String packageOf(PsiDirectory dir) {
    var folder = dir.getVirtualFile();
    var root = ProjectFileIndex.getInstance(dir.getProject()).getSourceRootForFile(folder);
    String relative = root == null ? null : com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(folder, root, '.');
    return relative == null ? "" : relative;
  }
}

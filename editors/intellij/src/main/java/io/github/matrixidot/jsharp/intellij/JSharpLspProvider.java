package io.github.matrixidot.jsharp.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspIntegrationProvider;

/** Starts the J# language server when a {@code .jsharp} file is opened. */
public final class JSharpLspProvider implements LspIntegrationProvider {
  @Override
  public void fileOpened(Project project, VirtualFile file, LspClientStarter starter) {
    if (JSharpPlugin.isJSharp(file)) {
      starter.ensureClientStarted(new JSharpServerDescriptor(project));
    }
  }
}

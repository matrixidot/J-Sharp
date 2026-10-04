package io.github.matrixidot.jsharp.intellij;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor;
import com.intellij.platform.lsp.api.customization.LspCodeLensCustomizer;
import com.intellij.platform.lsp.api.customization.LspCodeLensDisabled;
import com.intellij.platform.lsp.api.customization.LspCustomization;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport;
import com.intellij.psi.PsiFile;
import java.util.List;
import org.eclipse.lsp4j.services.LanguageServer;

/** The J# language server ({@code jsharp lsp}) for a project. */
final class JSharpServerDescriptor extends ProjectWideLspClientDescriptor {
  JSharpServerDescriptor(Project project) {
    super(project, "J#");
  }

  @Override
  public boolean isSupportedFile(VirtualFile file) {
    return JSharpPlugin.isJSharp(file);
  }

  @Override
  public GeneralCommandLine createCommandLine() {
    GeneralCommandLine cl = JSharpPlugin.cli(List.of("lsp"));
    String base = getProject().getBasePath();
    boolean exists = base != null && java.nio.file.Files.isDirectory(java.nio.file.Path.of(base));
    return exists ? cl.withWorkDirectory(base) : cl;
  }

  @Override
  public Class<? extends LanguageServer> getLsp4jServerClass() {
    return JSharpServer.class;
  }

  @Override
  public LspCustomization getLspCustomization() {
    return new LspCustomization() {
      @Override
      public LspCodeLensCustomizer getCodeLensCustomizer() {
        return LspCodeLensDisabled.INSTANCE; // the gutter ▶ and run configurations replace it
      }

      @Override
      public LspSemanticTokensCustomizer getSemanticTokensCustomizer() {
        // By default the IDE asks only for plain-text and TextMate files: J# names (classes,
        // methods, properties, parameters, ...) are colored from the server's semantic tokens.
        return new LspSemanticTokensSupport() {
          @Override
          public boolean shouldAskServerForSemanticTokens(PsiFile file) {
            return file instanceof JSharpFile;
          }
        };
      }
    };
  }
}

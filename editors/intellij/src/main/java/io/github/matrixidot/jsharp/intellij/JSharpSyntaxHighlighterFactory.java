package io.github.matrixidot.jsharp.intellij;

import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

/** Gives J# files the lexer-based {@link JSharpSyntaxHighlighter}. */
public final class JSharpSyntaxHighlighterFactory extends SyntaxHighlighterFactory {
  @Override
  public SyntaxHighlighter getSyntaxHighlighter(Project project, VirtualFile file) {
    return new JSharpSyntaxHighlighter();
  }
}

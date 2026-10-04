package io.github.matrixidot.jsharp.intellij;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;

/** A flat PSI: the file holds the tokens. Structure and errors come from the language server. */
public final class JSharpParserDefinition implements ParserDefinition {
  static final IFileElementType FILE = new IFileElementType(JSharpLanguage.INSTANCE);

  @Override
  public Lexer createLexer(Project project) {
    return new JSharpLexer();
  }

  @Override
  public PsiParser createParser(Project project) {
    return (root, builder) -> {
      var marker = builder.mark();
      while (!builder.eof()) {
        builder.advanceLexer();
      }
      marker.done(root);
      return builder.getTreeBuilt();
    };
  }

  @Override
  public IFileElementType getFileNodeType() {
    return FILE;
  }

  @Override
  public TokenSet getCommentTokens() {
    return JSharpTokens.COMMENTS;
  }

  @Override
  public TokenSet getStringLiteralElements() {
    return JSharpTokens.STRINGS;
  }

  @Override
  public PsiElement createElement(ASTNode node) {
    return new ASTWrapperPsiElement(node);
  }

  @Override
  public PsiFile createFile(FileViewProvider viewProvider) {
    return new JSharpFile(viewProvider);
  }
}

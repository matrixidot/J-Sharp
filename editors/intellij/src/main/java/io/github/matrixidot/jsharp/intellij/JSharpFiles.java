package io.github.matrixidot.jsharp.intellij;

import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import io.github.matrixidot.jsharp.compiler.ast.CompilationUnit;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.syntax.Parser;
import javax.swing.Icon;

/** What a J# file declares, for its icon and name in the project view. */
final class JSharpFiles {
  private JSharpFiles() {}

  /**
   * The kind of the type named like the file ({@code Point.jsharp} declaring {@code record
   * Point}), or null: then the file holds functions or a script and keeps its full name.
   */
  static Decl.TypeKind mainType(PsiFile file) {
    return CachedValuesManager.getCachedValue(
        file,
        () -> CachedValueProvider.Result.create(compute(file.getName(), file.getText()), file));
  }

  private static Decl.TypeKind compute(String fileName, String text) {
    String name = fileName.endsWith(".jsharp") ? fileName.substring(0, fileName.length() - 7) : fileName;
    CompilationUnit unit = Parser.parse(new SourceFile(fileName, text), d -> {});
    for (Decl d : unit.members()) {
      if (d instanceof Decl.TypeDecl t && t.name().equals(name)) {
        return t.kind();
      }
    }
    return null;
  }

  static Icon icon(Decl.TypeKind kind) {
    return switch (kind) {
      case CLASS -> AllIcons.Nodes.Class;
      case INTERFACE -> AllIcons.Nodes.Interface;
      case RECORD -> AllIcons.Nodes.Record;
      case ENUM -> AllIcons.Nodes.Enum;
    };
  }
}

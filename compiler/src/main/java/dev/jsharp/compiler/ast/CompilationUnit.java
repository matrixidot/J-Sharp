package dev.jsharp.compiler.ast;

import dev.jsharp.compiler.source.SourceFile;
import dev.jsharp.compiler.source.Span;
import java.util.List;

/**
 * One parsed source file.
 *
 * @param fileAnnotations annotations that apply to the file (e.g. {@code @file:ClassName("X")})
 * @param pkg package declaration, or null for the unnamed package
 * @param members top-level declarations and statements in source order
 */
public record CompilationUnit(
    SourceFile file,
    List<Annotation> fileAnnotations,
    PackageDecl pkg,
    List<ImportDecl> imports,
    List<Decl> members,
    Span span)
    implements Node {
  public CompilationUnit {
    fileAnnotations = List.copyOf(fileAnnotations);
    imports = List.copyOf(imports);
    members = List.copyOf(members);
  }

  public String packageName() {
    return pkg == null ? "" : pkg.name();
  }
}

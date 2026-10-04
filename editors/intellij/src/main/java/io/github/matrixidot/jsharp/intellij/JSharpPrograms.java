package io.github.matrixidot.jsharp.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import io.github.matrixidot.jsharp.compiler.ast.CompilationUnit;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.Modifier;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.syntax.Parser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Finding programs: where a file's entry point is, and which files run with it. */
final class JSharpPrograms {
  private JSharpPrograms() {}

  /**
   * The offset of the entry point in J# source {@code text}: its first top-level statement, else a
   * {@code main} function or static method; -1 if the file is not a program. Uses the J# parser.
   */
  static int entryOffset(String text) {
    CompilationUnit unit = Parser.parse(new SourceFile("entry.jsharp", text), d -> {});
    int main = -1;
    for (Decl d : unit.members()) {
      if (d instanceof Decl.TopLevelStmt s) {
        return s.span().start();
      }
      if (main < 0) {
        main = mainOffset(d, true);
      }
    }
    return main;
  }

  private static int mainOffset(Decl d, boolean topLevel) {
    return switch (d) {
      case Decl.Method m
          when m.name().equals("main") && (topLevel || m.modifiers().has(Modifier.STATIC)) ->
          m.nameSpan().start();
      case Decl.TypeDecl t -> {
        for (Decl member : t.members()) {
          int at = mainOffset(member, false);
          if (at >= 0) {
            yield at;
          }
        }
        yield -1;
      }
      default -> -1;
    };
  }

  /**
   * The files to compile with the program in {@code file}: the language server's answer (the files
   * analyzed with it), else the file and the library files (no top-level statements) next to it.
   */
  static List<String> programFiles(Project project, VirtualFile file) {
    for (LspClient client : LspClientManager.getInstance(project).getClients(JSharpLspProvider.class)) {
      try {
        List<String> files =
            client.sendRequestSync(
                5000,
                ls ->
                    ((JSharpServer) ls)
                        .programFiles(
                            new JSharpServer.DocumentParams(client.getDocumentIdentifier(file))));
        if (files != null && !files.isEmpty()) {
          return files;
        }
      } catch (RuntimeException e) {
        // the server is starting or gone: decide locally
      }
    }
    return siblings(Path.of(file.getPath()));
  }

  static List<String> siblings(Path program) {
    List<String> out = new ArrayList<>(List.of(program.toString()));
    Path dir = program.getParent();
    if (dir == null) {
      return out;
    }
    try (Stream<Path> s = Files.list(dir)) {
      for (Path p : s.sorted().toList()) {
        String name = p.getFileName().toString();
        if (p.equals(program) || !Files.isRegularFile(p)) {
          continue;
        }
        if (name.endsWith(".java")
            || name.endsWith("." + JSharpPlugin.EXTENSION)
                && !hasTopLevelStatements(Files.readString(p, StandardCharsets.UTF_8))) {
          out.add(p.toString());
        }
      }
    } catch (IOException e) {
      // run the file alone
    }
    return out;
  }

  private static boolean hasTopLevelStatements(String text) {
    return Parser.parse(new SourceFile("x.jsharp", text), d -> {}).members().stream()
        .anyMatch(d -> d instanceof Decl.TopLevelStmt);
  }

  static VirtualFile find(String path) {
    return path == null || path.isBlank()
        ? null
        : LocalFileSystem.getInstance().findFileByPath(path);
  }
}

package io.github.matrixidot.jsharp.compiler.driver;

import io.github.matrixidot.jsharp.compiler.LanguageInfo;
import io.github.matrixidot.jsharp.compiler.diag.DiagnosticRenderer;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Minimal batch entry point used by the build to compile J# parts of the standard library: {@code
 * Batch [-cp path] -d outDir srcDir...}. Kept in the compiler module (not the CLI) so the runtime
 * can be compiled without depending on itself.
 */
public final class Batch {
  private Batch() {}

  public static void main(String[] args) throws IOException {
    List<Path> cp = new ArrayList<>();
    Path out = null;
    List<Path> roots = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "-cp" -> {
          for (String p : args[++i].split(java.io.File.pathSeparator)) {
            if (!p.isEmpty()) {
              cp.add(Path.of(p));
            }
          }
        }
        case "-d" -> out = Path.of(args[++i]);
        default -> roots.add(Path.of(args[i]));
      }
    }
    if (out == null || roots.isEmpty()) {
      System.err.println("usage: Batch [-cp path] -d outDir srcDir...");
      System.exit(2);
    }
    List<SourceFile> files = new ArrayList<>();
    for (Path root : roots) {
      try (Stream<Path> s = Files.walk(root)) {
        for (Path p :
            s.filter(x -> x.toString().endsWith(LanguageInfo.FILE_EXTENSION)).sorted().toList()) {
          files.add(new SourceFile(root.relativize(p).toString(), Files.readString(p)));
        }
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }
    Compilation comp =
        new Compilation(
            files, CompilerOptions.defaults().withClassPath(cp).withOutputDir(out), null);
    comp.compile();
    comp.close();
    var ds = comp.diagnostics().sorted();
    if (!ds.isEmpty()) {
      System.err.print(new DiagnosticRenderer(false).renderAll(ds));
    }
    if (comp.diagnostics().hasErrors()) {
      System.exit(1);
    }
  }
}

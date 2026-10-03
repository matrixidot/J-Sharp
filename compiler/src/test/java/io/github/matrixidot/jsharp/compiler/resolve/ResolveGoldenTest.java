package io.github.matrixidot.jsharp.compiler.resolve;

import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.driver.Compilation;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.testing.Golden;
import io.github.matrixidot.jsharp.compiler.testing.SymbolPrinter;
import io.github.matrixidot.jsharp.compiler.testing.TestCompiler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Golden tests for declarations and name resolution. Each case is {@code resolve/X.jsharp} or a
 * directory {@code resolve/X/} of files; {@code resolve/X.expected} holds the diagnostics followed
 * by a dump of all source class symbols.
 */
class ResolveGoldenTest {

  @TestFactory
  Stream<DynamicTest> cases() throws IOException {
    Path dir = Golden.resourceDir("resolve");
    List<Path> cases = new ArrayList<>();
    try (Stream<Path> s = Files.list(dir)) {
      s.filter(p -> Files.isDirectory(p) || p.toString().endsWith(".jsharp"))
          .sorted()
          .forEach(cases::add);
    }
    return cases.stream()
        .map(c -> DynamicTest.dynamicTest(c.getFileName().toString(), () -> run(c)));
  }

  private static void run(Path c) {
    List<SourceFile> files = new ArrayList<>();
    if (Files.isDirectory(c)) {
      try (Stream<Path> s = Files.list(c)) {
        s.filter(p -> p.toString().endsWith(".jsharp"))
            .sorted()
            .forEach(
                p ->
                    files.add(
                        new SourceFile(c.getFileName() + "/" + p.getFileName(), Golden.read(p))));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    } else {
      files.add(new SourceFile(c.getFileName().toString(), Golden.read(c)));
    }
    Compilation comp = TestCompiler.analyze(files);
    StringBuilder sb = new StringBuilder();
    for (Diagnostic d : comp.diagnostics().sorted()) {
      sb.append(d.file() == null ? "" : d.file().path() + ":")
          .append(d.line())
          .append(':')
          .append(d.column())
          .append(' ')
          .append(d.code().id())
          .append(' ')
          .append(d.message());
      if (d.help() != null) {
        sb.append(" [help: ").append(d.help()).append(']');
      }
      sb.append('\n');
    }
    if (comp.context() != null) {
      List<ClassSymbol> classes = comp.context().syms.sourceClasses();
      sb.append("---\n").append(SymbolPrinter.print(classes));
    }
    String name = c.getFileName().toString().replace(".jsharp", "");
    Golden.check(c.resolveSibling(name + ".expected"), sb.toString());
  }
}

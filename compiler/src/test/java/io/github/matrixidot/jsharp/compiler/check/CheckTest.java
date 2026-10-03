package io.github.matrixidot.jsharp.compiler.check;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.DiagnosticRenderer;
import io.github.matrixidot.jsharp.compiler.driver.Compilation;
import io.github.matrixidot.jsharp.compiler.driver.CompilerOptions;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.testing.Golden;
import io.github.matrixidot.jsharp.compiler.testing.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Type-checker tests with inline expectations. A line ending in {@code //~ JS0601} (several codes
 * allowed) must produce exactly those diagnostics; every other line must produce none. Warnings
 * count too. Files without markers must check cleanly.
 *
 * <p>A directory named {@code *.module} is one module of J# and Java sources (D082): it is fully
 * compiled (javac included) and the markers of all its files are checked.
 */
class CheckTest {
  private static final Pattern EXPECT = Pattern.compile("//~((?:\\s+JS\\d{4})+)");

  @TestFactory
  Stream<DynamicTest> cases() throws IOException {
    Path dir = Golden.resourceDir("check");
    List<Path> cases = new ArrayList<>();
    try (Stream<Path> s = Files.walk(dir)) {
      s.filter(p -> isModule(p) || p.toString().endsWith(".jsharp") && !inModule(p))
          .sorted()
          .forEach(cases::add);
    }
    return cases.stream()
        .map(f -> DynamicTest.dynamicTest(dir.relativize(f).toString(), () -> run(f)));
  }

  private static boolean isModule(Path p) {
    return Files.isDirectory(p) && p.getFileName().toString().endsWith(".module");
  }

  private static boolean inModule(Path p) {
    for (Path q = p.getParent(); q != null; q = q.getParent()) {
      if (q.getFileName() != null && q.getFileName().toString().endsWith(".module")) {
        return true;
      }
    }
    return false;
  }

  static void run(Path f) throws IOException {
    List<Path> paths = new ArrayList<>();
    if (Files.isDirectory(f)) {
      try (Stream<Path> s = Files.walk(f)) {
        s.filter(p -> p.toString().endsWith(".jsharp") || p.toString().endsWith(".java"))
            .sorted()
            .forEach(paths::add);
      }
    } else {
      paths.add(f);
    }
    // Keyed by "file:line".
    Map<String, List<String>> expected = new TreeMap<>();
    List<SourceFile> sources = new ArrayList<>();
    for (Path p : paths) {
      String name = Files.isDirectory(f) ? f.relativize(p).toString() : p.getFileName().toString();
      String src = Golden.read(p);
      sources.add(new SourceFile(name, src));
      String[] lines = src.split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        Matcher m = EXPECT.matcher(lines[i]);
        if (m.find()) {
          List<String> codes = new ArrayList<>();
          for (String c : m.group(1).trim().split("\\s+")) {
            codes.add(c);
          }
          codes.sort(null);
          expected.put(name + ":" + (i + 1), codes);
        }
      }
    }
    Compilation comp;
    if (Files.isDirectory(f)) {
      comp = new Compilation(sources, CompilerOptions.defaults(), TestCompiler.classPath());
      comp.compile();
    } else {
      comp = TestCompiler.analyze(sources);
    }
    Map<String, List<String>> actual = new TreeMap<>();
    List<Diagnostic> all = comp.diagnostics().sorted();
    for (Diagnostic d : all) {
      String file = d.file() == null ? "?" : d.file().path();
      actual.computeIfAbsent(file + ":" + d.line(), k -> new ArrayList<>()).add(d.code().id());
    }
    actual.values().forEach(l -> l.sort(null));
    if (!expected.equals(actual)) {
      String rendered = new DiagnosticRenderer(false).renderAll(all);
      assertThat(actual)
          .as("diagnostics by line in %s\n%s", f.getFileName(), rendered)
          .isEqualTo(expected);
    }
  }
}

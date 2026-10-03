package io.github.matrixidot.jsharp.compiler.check;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.DiagnosticRenderer;
import io.github.matrixidot.jsharp.compiler.driver.Compilation;
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
 */
class CheckTest {
  private static final Pattern EXPECT = Pattern.compile("//~((?:\\s+JS\\d{4})+)");

  @TestFactory
  Stream<DynamicTest> cases() throws IOException {
    Path dir = Golden.resourceDir("check");
    List<Path> files = new ArrayList<>();
    try (Stream<Path> s = Files.walk(dir)) {
      s.filter(p -> p.toString().endsWith(".jsharp")).sorted().forEach(files::add);
    }
    return files.stream()
        .map(f -> DynamicTest.dynamicTest(dir.relativize(f).toString(), () -> run(f)));
  }

  static void run(Path f) {
    String src = Golden.read(f);
    Map<Integer, List<String>> expected = new TreeMap<>();
    String[] lines = src.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      Matcher m = EXPECT.matcher(lines[i]);
      if (m.find()) {
        List<String> codes = new ArrayList<>();
        for (String c : m.group(1).trim().split("\\s+")) {
          codes.add(c);
        }
        codes.sort(null);
        expected.put(i + 1, codes);
      }
    }
    Compilation comp =
        TestCompiler.analyze(List.of(new SourceFile(f.getFileName().toString(), src)));
    Map<Integer, List<String>> actual = new TreeMap<>();
    List<Diagnostic> all = comp.diagnostics().sorted();
    for (Diagnostic d : all) {
      actual.computeIfAbsent(d.line(), k -> new ArrayList<>()).add(d.code().id());
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

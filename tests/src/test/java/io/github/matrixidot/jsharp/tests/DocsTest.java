package io.github.matrixidot.jsharp.tests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matrixidot.jsharp.compiler.diag.DiagnosticRenderer;
import io.github.matrixidot.jsharp.compiler.driver.Compilation;
import io.github.matrixidot.jsharp.compiler.driver.CompilerOptions;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Keeps the documentation honest: every {@code ```jsharp} block in docs/TOUR.md is compiled and run
 * on a fresh JVM with {@code -Xverify:all}, and its output must equal the {@code // prints:}
 * comments of the block, in order.
 */
class DocsTest {
  private static final Path TOUR = Path.of(System.getProperty("jsharp.docs"), "TOUR.md");
  private static final Pattern BLOCK = Pattern.compile("```jsharp\\n(.*?)```", Pattern.DOTALL);
  private static final Pattern PRINTS = Pattern.compile("//\\s*prints:\\s?(.*)$");

  @TestFactory
  Stream<DynamicTest> tourExamples() throws IOException {
    String text = Files.readString(TOUR);
    List<DynamicTest> tests = new ArrayList<>();
    Matcher m = BLOCK.matcher(text);
    int index = 0;
    while (m.find()) {
      String code = m.group(1);
      int line = (int) text.substring(0, m.start()).lines().count() + 1;
      String name = "tour" + (++index);
      tests.add(DynamicTest.dynamicTest(name + " (TOUR.md:" + line + ")", () -> check(name, code)));
    }
    assertThat(tests).hasSizeGreaterThan(10);
    return tests.stream();
  }

  private static void check(String name, String code) throws Exception {
    StringBuilder expected = new StringBuilder();
    for (String l : code.lines().toList()) {
      Matcher p = PRINTS.matcher(l);
      if (p.find()) {
        expected.append(p.group(1)).append('\n');
      }
    }
    Path out = Files.createTempDirectory("jsharp-docs-" + name);
    Compilation comp =
        new Compilation(
            List.of(new SourceFile(name + ".jsharp", code)),
            CompilerOptions.defaults().withOutputDir(out),
            null);
    comp.compile();
    comp.close();
    assertThat(comp.diagnostics().hasErrors())
        .as("%s", new DiagnosticRenderer(false).renderAll(comp.diagnostics().sorted()))
        .isFalse();
    TestSupport.Run run = TestSupport.runJava(List.of(out), comp.mainClass(), List.of(), null);
    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stdout()).isEqualTo(expected.toString());
  }
}

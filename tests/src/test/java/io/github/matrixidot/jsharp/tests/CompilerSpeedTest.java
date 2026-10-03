package io.github.matrixidot.jsharp.tests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matrixidot.jsharp.compiler.classpath.ClassPath;
import io.github.matrixidot.jsharp.compiler.diag.DiagnosticRenderer;
import io.github.matrixidot.jsharp.compiler.driver.Compilation;
import io.github.matrixidot.jsharp.compiler.driver.CompilerOptions;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Compiler-speed regression test (ARCHITECTURE: a 10k-line project in under 2 s after JIT warm-up).
 * Generates the same project as {@code scripts/gen_project.py} (88 files, ~10k lines) and compiles
 * it in memory, through code generation, several times; the best warm run must stay under the
 * target. It measured about 0.4 s when written, so the bound leaves room for slow CI.
 */
class CompilerSpeedTest {
  private static final int FILES = 88;
  private static final long TARGET_MILLIS = 2_000;

  static List<SourceFile> generate() throws IOException {
    String template;
    try (InputStream in =
        CompilerSpeedTest.class.getResourceAsStream("/speed/template.jsharp.txt")) {
      template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<SourceFile> files = new ArrayList<>();
    for (int i = 0; i < FILES; i++) {
      String imp = i > 0 ? "import gen.p" + (i - 1) + ".*;" : "";
      String chain =
          i > 0 ? "return chain" + (i - 1) + "(x + 1) + score" + (i - 1) + "(3);" : "return x;";
      String text =
          template
              .replace("@IMP@", imp)
              .replace("@CHAIN@", chain)
              .replace("@I@", String.valueOf(i));
      files.add(new SourceFile("file" + i + ".jsharp", text));
    }
    return files;
  }

  @Test
  void tenThousandLinesCompileWithinTarget() throws IOException {
    List<SourceFile> files = generate();
    long lines = files.stream().mapToLong(SourceFile::lineCount).sum();
    assertThat(lines).isGreaterThan(9_500);
    ClassPath cp = Compilation.openClassPath(CompilerOptions.defaults());
    long best = Long.MAX_VALUE;
    for (int run = 0; run < 8; run++) {
      long t0 = System.nanoTime();
      Compilation c = new Compilation(files, CompilerOptions.defaults(), cp);
      c.compile();
      long millis = (System.nanoTime() - t0) / 1_000_000;
      assertThat(c.diagnostics().hasErrors())
          .as("%s", new DiagnosticRenderer(false).renderAll(c.diagnostics().sorted()))
          .isFalse();
      assertThat(c.classFiles().size()).isGreaterThanOrEqualTo(FILES * 8);
      best = Math.min(best, millis);
    }
    System.out.printf("CompilerSpeedTest: %d lines, best warm compile %d ms%n", lines, best);
    assertThat(best).as("best warm compile time (ms) of %d lines", lines).isLessThan(TARGET_MILLIS);
  }
}

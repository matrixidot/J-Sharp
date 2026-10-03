package io.github.matrixidot.jsharp.compiler.diag;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matrixidot.jsharp.compiler.driver.Compilation;
import io.github.matrixidot.jsharp.compiler.driver.CompilerOptions;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.testing.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every diagnostic code must be exercised by at least one test resource (spec M3 exit). */
class DiagnosticCoverageTest {

  @Test
  void everyCodeHasATest() throws IOException {
    StringBuilder all = new StringBuilder();
    try (Stream<Path> s = Files.walk(Path.of("src/test/resources"))) {
      for (Path p : s.filter(Files::isRegularFile).toList()) {
        all.append(Files.readString(p));
      }
    }
    List<String> missing = new ArrayList<>();
    for (Code c : Code.values()) {
      // INTERNAL_ERROR is only produced by compiler bugs; PLATFORM_NULLNESS is covered below.
      if (c == Code.INTERNAL_ERROR || c == Code.PLATFORM_NULLNESS) {
        continue;
      }
      if (all.indexOf(c.id()) < 0) {
        missing.add(c.id() + " " + c.name());
      }
    }
    assertThat(missing).as("diagnostic codes without a test").isEmpty();
  }

  @Test
  void strictPlatformNullnessWarnsOnJavaMemberAccess() {
    var options = new CompilerOptions(List.of(), null, false, true, true);
    var comp =
        new Compilation(
            List.of(
                new SourceFile(
                    "t.jsharp",
                    "var sb = new StringBuilder();\nvar n = sb.toString().length();\n")),
            options,
            TestCompiler.classPath());
    comp.analyze();
    assertThat(comp.diagnostics().all())
        .extracting(Diagnostic::code)
        .contains(Code.PLATFORM_NULLNESS);
  }
}

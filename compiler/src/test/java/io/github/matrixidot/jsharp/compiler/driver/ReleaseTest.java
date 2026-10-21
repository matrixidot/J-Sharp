package io.github.matrixidot.jsharp.compiler.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.matrixidot.jsharp.compiler.LanguageInfo;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code --release}: the class file version and the JDK API a program is compiled against (D108).
 */
class ReleaseTest {
  private static Compilation compile(int release, String text) {
    Compilation c =
        new Compilation(
            List.of(new SourceFile("app/Main.jsharp", text)),
            CompilerOptions.defaults().withRelease(release),
            null);
    c.compile();
    return c;
  }

  private static int majorVersion(byte[] classFile) {
    return ((classFile[6] & 0xff) << 8) | (classFile[7] & 0xff);
  }

  @Test
  void classFilesHaveTheReleasesVersion() {
    for (int release : List.of(LanguageInfo.MIN_JAVA_RELEASE, CompilerOptions.defaultRelease())) {
      Compilation c = compile(release, "package app;\npublic int twice(int x) => 2 * x;\n");
      assertThat(c.diagnostics().all()).isEmpty();
      assertThat(c.classFiles().values())
          .allSatisfy(bytes -> assertThat(majorVersion(bytes)).isEqualTo(44 + release));
    }
  }

  /** java.lang.IO is new in Java 25: compiling for Java 21 does not see it. */
  @Test
  void olderReleasesSeeTheirOwnApi() {
    assumeTrue(Runtime.version().feature() >= 25, "needs Java 25 to compile for it");
    String text = "package app;\npublic void hello() { IO.println(\"hi\"); }\n";
    assertThat(compile(25, text).diagnostics().all()).isEmpty();
    assertThat(compile(21, text).diagnostics().all())
        .extracting(Diagnostic::message)
        .anySatisfy(m -> assertThat(m).contains("IO"));
    // APIs Java 21 has are there.
    assertThat(
            compile(21, "package app;\npublic int first() => java.util.List.of(4, 2).getFirst();\n")
                .diagnostics()
                .all())
        .isEmpty();
  }

  @Test
  void unsupportedReleasesAreExplained() {
    assertThat(CompilerOptions.releaseProblem(17)).contains("Java 21 or newer");
    assertThat(CompilerOptions.releaseProblem(Runtime.version().feature() + 1)).contains("needs");
    assertThat(CompilerOptions.releaseProblem(21)).isNull();
  }
}

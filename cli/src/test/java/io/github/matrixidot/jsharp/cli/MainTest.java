package io.github.matrixidot.jsharp.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
  record Outcome(int code, String out, String err) {}

  static Outcome run(String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int code =
        Main.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Outcome(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void printsVersion() {
    Outcome o = run("--version");
    assertThat(o.code()).isZero();
    assertThat(o.out()).startsWith("jsharp ");
  }

  @Test
  void exitCodes(@TempDir Path dir) throws IOException {
    Path ok = Files.writeString(dir.resolve("ok.jsharp"), "println(1 + 1);\n");
    Path bad = Files.writeString(dir.resolve("bad.jsharp"), "int x = \"no\";\n");
    assertThat(run("check", ok.toString()).code()).isZero();
    Outcome b = run("check", bad.toString());
    assertThat(b.code()).isEqualTo(1);
    assertThat(b.err()).contains("error[JS0600]");
    assertThat(run("frobnicate").code()).isEqualTo(2);
    assertThat(run("check").code()).isEqualTo(2);
    assertThat(run("check", "--diagnostics=json", bad.toString()).out()).contains("\"JS0600\"");
  }

  @Test
  void jarWithRuntimeIsSelfContained(@TempDir Path dir) throws IOException {
    Path src = Files.writeString(dir.resolve("hello.jsharp"), "println($\"hi {1 + 2}\");\n");
    Path jar = dir.resolve("app.jar");
    Outcome o = run("build", src.toString(), "--jar", jar.toString(), "--include-runtime");
    assertThat(o.code()).as(o.err()).isZero();
    try (JarFile jf = new JarFile(jar.toFile())) {
      assertThat(jf.getManifest().getMainAttributes().getValue("Main-Class"))
          .isEqualTo("HelloModule");
      assertThat(jf.getEntry("HelloModule.class")).isNotNull();
      assertThat(jf.getEntry("jsharp/core/Prelude.class")).isNotNull();
      assertThat(jf.getEntry("jsharp/collections/Sequences.class")).isNotNull();
    }
  }

  @Test
  void cdsTrainingProgramCompiles() {
    assertThat(run("--cds-train").code()).isZero();
  }
}

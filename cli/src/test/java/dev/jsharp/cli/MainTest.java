package dev.jsharp.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MainTest {
  @Test
  void printsVersion() {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int code =
        Main.run(
            new String[] {"--version"},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    assertThat(code).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("jsharp ");
  }
}

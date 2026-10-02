package dev.jsharp.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Placeholder until M4: checks the golden corpus directory is wired into the build. */
class GoldenTest {
  @Test
  void casesDirectoryExists() {
    assertThat(Files.isDirectory(Path.of(System.getProperty("jsharp.cases")))).isTrue();
  }
}

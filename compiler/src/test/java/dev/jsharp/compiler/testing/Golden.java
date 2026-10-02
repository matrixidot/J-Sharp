package dev.jsharp.compiler.testing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jsharp.compiler.diag.Diagnostic;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Golden-file helpers. Set the environment variable {@code JSHARP_UPDATE_GOLDEN=1} to (re)write
 * expected files from actual output; review the diff before committing.
 */
public final class Golden {
  private Golden() {}

  public static boolean updating() {
    return "1".equals(System.getenv("JSHARP_UPDATE_GOLDEN"));
  }

  /** The source tree directory for test resources (so updates land in git, not build/). */
  public static Path resourceDir(String name) {
    Path p = Path.of("src/test/resources").resolve(name);
    assertThat(p).isDirectory();
    return p;
  }

  public static List<Path> files(Path dir, String ext) {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.toString().endsWith(ext)).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static String read(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Compares {@code actual} with the golden file, or writes it in update mode. */
  public static void check(Path expectedFile, String actual) {
    if (updating()) {
      try {
        Files.writeString(expectedFile, actual, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return;
    }
    assertThat(expectedFile)
        .as("golden file %s (run with JSHARP_UPDATE_GOLDEN=1)", expectedFile)
        .exists();
    assertThat(actual).as("golden %s", expectedFile.getFileName()).isEqualTo(read(expectedFile));
  }

  /** Compact one-line-per-diagnostic form used by golden files. */
  public static String diagnostics(List<Diagnostic> ds) {
    StringBuilder sb = new StringBuilder();
    for (Diagnostic d : ds) {
      sb.append(d.severity().label())
          .append(' ')
          .append(d.code().id())
          .append(' ')
          .append(d.line())
          .append(':')
          .append(d.column())
          .append(' ')
          .append(d.message())
          .append('\n');
    }
    return sb.toString();
  }
}

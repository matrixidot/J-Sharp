package io.github.matrixidot.jsharp.compiler.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Compiles Java source fixtures with javac into a temporary directory for interop tests. */
public final class JavaFixtures {
  private JavaFixtures() {}

  /**
   * @param sources map from relative path (e.g. {@code p/A.java}) to source text
   * @param extraClassPath additional class path entries for javac
   * @return the output directory containing the class files
   */
  public static Path compile(Map<String, String> sources, List<Path> extraClassPath) {
    try {
      Path src = Files.createTempDirectory("jsharp-fixture-src");
      Path out = Files.createTempDirectory("jsharp-fixture-out");
      List<String> args =
          new ArrayList<>(
              List.of("-d", out.toString(), "-parameters", "-nowarn", "--release", "25"));
      StringBuilder cp = new StringBuilder(System.getProperty("java.class.path"));
      for (Path p : extraClassPath) {
        cp.append(java.io.File.pathSeparator).append(p);
      }
      args.add("-cp");
      args.add(cp.toString());
      for (var e : sources.entrySet()) {
        Path f = src.resolve(e.getKey());
        Files.createDirectories(f.getParent());
        Files.writeString(f, e.getValue());
        args.add(f.toString());
      }
      JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
      var err = new java.io.ByteArrayOutputStream();
      int rc = javac.run(null, null, err, args.toArray(String[]::new));
      if (rc != 0) {
        throw new IllegalStateException("javac failed:\n" + err);
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

package dev.jsharp.tests;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Shared helpers for the end-to-end suites: bytecode verification and running a fresh JVM. */
final class TestSupport {
  static final String RUNTIME = System.getProperty("jsharp.runtime.classpath");
  static final String JAVA = System.getProperty("jsharp.java", "java");

  private TestSupport() {}

  record Run(String stdout, String stderr, int exitCode) {}

  /** Verifies generated classes with the ClassFile API verifier (resolving among themselves). */
  static List<String> verify(Map<String, byte[]> classes) {
    return verify(classes, List.of());
  }

  /** Like {@link #verify(Map)}, also resolving classes from the given class directories. */
  static List<String> verify(Map<String, byte[]> classes, List<Path> classDirs) {
    var resolver =
        ClassHierarchyResolver.ofResourceParsing(
                d -> {
                  String ds = d.descriptorString();
                  if (ds.length() <= 2) {
                    return null;
                  }
                  String internal = ds.substring(1, ds.length() - 1);
                  byte[] b = classes.get(internal);
                  if (b != null) {
                    return new ByteArrayInputStream(b);
                  }
                  for (Path dir : classDirs) {
                    Path f = dir.resolve(internal + ".class");
                    if (Files.exists(f)) {
                      try {
                        return Files.newInputStream(f);
                      } catch (IOException e) {
                        return null;
                      }
                    }
                  }
                  return null;
                })
            .orElse(ClassHierarchyResolver.defaultResolver());
    ClassFile verifier = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
    List<String> errors = new ArrayList<>();
    for (var e : classes.entrySet()) {
      for (var err : verifier.verify(e.getValue())) {
        errors.add(e.getKey() + ": " + err.getMessage());
      }
    }
    return errors;
  }

  /** Runs {@code mainClass} on a fresh JVM with {@code -Xverify:all}. */
  static Run runJava(List<Path> classPath, String mainClass, List<String> args, Path stdin)
      throws IOException, InterruptedException {
    List<String> cp = new ArrayList<>();
    for (Path p : classPath) {
      cp.add(p.toString());
    }
    cp.add(RUNTIME);
    List<String> cmd =
        new ArrayList<>(
            List.of(
                JAVA,
                "-Xverify:all",
                "-XX:+UseSerialGC",
                "-XX:TieredStopAtLevel=1",
                "-cp",
                String.join(File.pathSeparator, cp),
                mainClass));
    cmd.addAll(args);
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectInput(
        stdin != null
            ? ProcessBuilder.Redirect.from(stdin.toFile())
            : ProcessBuilder.Redirect.from(new File("/dev/null")));
    Process p = pb.start();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var out = pool.submit(() -> p.getInputStream().readAllBytes());
      var err = pool.submit(() -> p.getErrorStream().readAllBytes());
      if (!p.waitFor(60, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return new Run("", "timeout", -2);
      }
      return new Run(
          new String(out.get(), StandardCharsets.UTF_8).replace("\r\n", "\n"),
          new String(err.get(), StandardCharsets.UTF_8),
          p.exitValue());
    } catch (ExecutionException e) {
      throw new IOException(e);
    }
  }

  /** All files under {@code dir} with the given extension, sorted. */
  static List<Path> files(Path dir, String ext) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> s = Files.walk(dir)) {
      return s.filter(p -> p.toString().endsWith(ext)).sorted().toList();
    }
  }
}

package dev.jsharp.tests;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jsharp.compiler.classpath.ClassPath;
import dev.jsharp.compiler.diag.DiagnosticRenderer;
import dev.jsharp.compiler.driver.Compilation;
import dev.jsharp.compiler.driver.CompilerOptions;
import dev.jsharp.compiler.source.SourceFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Golden end-to-end tests. Each {@code tests/cases/X.jsharp} (or directory {@code X/} of files) is
 * compiled, every class is checked with the ClassFile verifier, and the program runs on a fresh JVM
 * with {@code -Xverify:all}; stdout must equal {@code X.expected}. Optional {@code X.args} (one
 * argument per line) and {@code X.stdin}. Programs that should fail at run time put the expected
 * exit code in {@code X.exitcode}. Set {@code JSHARP_UPDATE_GOLDEN=1} to rewrite outputs.
 */
class EndToEndTest {
  private static final Path CASES = Path.of(System.getProperty("jsharp.cases"));
  private static final String RUNTIME = System.getProperty("jsharp.runtime.classpath");
  private static final String JAVA = System.getProperty("jsharp.java", "java");

  record Result(
      String compileErrors,
      List<String> verifyErrors,
      String stdout,
      String stderr,
      int exitCode) {}

  @TestFactory
  Stream<DynamicTest> cases() throws Exception {
    List<Path> cases = new ArrayList<>();
    try (Stream<Path> s = Files.list(CASES)) {
      s.filter(p -> p.toString().endsWith(".jsharp") || Files.isDirectory(p))
          .sorted()
          .forEach(cases::add);
    }
    ClassPath cp = Compilation.openClassPath(CompilerOptions.defaults());
    ExecutorService pool =
        Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
    List<Future<Result>> results = new ArrayList<>();
    for (Path c : cases) {
      results.add(pool.submit(() -> runCase(c, cp)));
    }
    List<DynamicTest> tests = new ArrayList<>();
    for (int i = 0; i < cases.size(); i++) {
      Path c = cases.get(i);
      Future<Result> f = results.get(i);
      tests.add(
          DynamicTest.dynamicTest(
              c.getFileName().toString(), () -> check(c, f.get(5, TimeUnit.MINUTES))));
    }
    pool.shutdown();
    return tests.stream();
  }

  private static String base(Path c) {
    String n = c.getFileName().toString();
    return n.endsWith(".jsharp") ? n.substring(0, n.length() - ".jsharp".length()) : n;
  }

  private static Result runCase(Path c, ClassPath cp) throws IOException, InterruptedException {
    List<SourceFile> files = new ArrayList<>();
    if (Files.isDirectory(c)) {
      try (Stream<Path> s = Files.walk(c)) {
        for (Path p : s.filter(x -> x.toString().endsWith(".jsharp")).sorted().toList()) {
          files.add(new SourceFile(c.getFileName() + "/" + c.relativize(p), Files.readString(p)));
        }
      }
    } else {
      files.add(new SourceFile(c.getFileName().toString(), Files.readString(c)));
    }
    Path out = Files.createTempDirectory("jsharp-e2e-" + base(c));
    Compilation comp = new Compilation(files, CompilerOptions.defaults().withOutputDir(out), cp);
    comp.compile();
    if (comp.diagnostics().hasErrors()) {
      return new Result(
          new DiagnosticRenderer(false).renderAll(comp.diagnostics().sorted()),
          List.of(),
          "",
          "",
          -1);
    }
    List<String> verify = new ArrayList<>();
    var generated = comp.classFiles();
    var resolver =
        java.lang.classfile.ClassHierarchyResolver.ofResourceParsing(
                d -> {
                  String ds = d.descriptorString();
                  byte[] b =
                      ds.length() > 2 ? generated.get(ds.substring(1, ds.length() - 1)) : null;
                  return b == null ? null : new java.io.ByteArrayInputStream(b);
                })
            .orElse(java.lang.classfile.ClassHierarchyResolver.defaultResolver());
    ClassFile verifier = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
    for (var e : generated.entrySet()) {
      for (var err : verifier.verify(e.getValue())) {
        verify.add(e.getKey() + ": " + err.getMessage());
      }
    }
    if (comp.mainClass() == null) {
      return new Result("no entry point", verify, "", "", -1);
    }
    List<String> cmd =
        new ArrayList<>(
            List.of(
                JAVA,
                "-Xverify:all",
                "-XX:+UseSerialGC",
                "-XX:TieredStopAtLevel=1",
                "-Xshare:auto",
                "-cp",
                out + java.io.File.pathSeparator + RUNTIME,
                comp.mainClass()));
    Path argsFile = c.resolveSibling(base(c) + ".args");
    if (Files.exists(argsFile)) {
      cmd.addAll(Files.readAllLines(argsFile));
    }
    ProcessBuilder pb = new ProcessBuilder(cmd);
    Path stdin = c.resolveSibling(base(c) + ".stdin");
    pb.redirectInput(
        Files.exists(stdin)
            ? ProcessBuilder.Redirect.from(stdin.toFile())
            : ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
    Process p = pb.start();
    var stdoutF = p.getInputStream();
    var stderrF = p.getErrorStream();
    byte[] so;
    byte[] se;
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var a = pool.submit(stdoutF::readAllBytes);
      var b = pool.submit(stderrF::readAllBytes);
      if (!p.waitFor(60, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return new Result("", verify, "", "timeout", -2);
      }
      so = a.get();
      se = b.get();
    } catch (java.util.concurrent.ExecutionException e) {
      throw new IOException(e);
    }
    return new Result(
        "",
        verify,
        new String(so, StandardCharsets.UTF_8),
        new String(se, StandardCharsets.UTF_8),
        p.exitValue());
  }

  private static void check(Path c, Result r) {
    assertThat(r.compileErrors()).as("compile errors in %s", c.getFileName()).isEmpty();
    assertThat(r.verifyErrors()).as("ClassFile verifier errors").isEmpty();
    Path exitFile = c.resolveSibling(base(c) + ".exitcode");
    int expectedExit = 0;
    try {
      if (Files.exists(exitFile)) {
        expectedExit = Integer.parseInt(Files.readString(exitFile).trim());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    assertThat(r.stderr())
        .as("stderr must not contain VerifyError")
        .doesNotContain("VerifyError")
        .doesNotContain("ClassFormatError");
    assertThat(r.exitCode())
        .as("exit code of %s (stderr: %s)", c.getFileName(), r.stderr())
        .isEqualTo(expectedExit);
    Path expected = c.resolveSibling(base(c) + ".expected");
    String actual = r.stdout().replace("\r\n", "\n");
    try {
      if ("1".equals(System.getenv("JSHARP_UPDATE_GOLDEN"))) {
        Files.writeString(expected, actual);
        return;
      }
      assertThat(expected).as("expected output file (run with JSHARP_UPDATE_GOLDEN=1)").exists();
      assertThat(actual).as("stdout of %s", c.getFileName()).isEqualTo(Files.readString(expected));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

package io.github.matrixidot.jsharp.tests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matrixidot.jsharp.compiler.diag.DiagnosticRenderer;
import io.github.matrixidot.jsharp.compiler.driver.Compilation;
import io.github.matrixidot.jsharp.compiler.driver.CompilerOptions;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the example applications. Each {@code examples/NAME/} has J# sources in {@code src/} and
 * test cases in {@code test/}: {@code CASE.args} (one program argument per line, paths relative to
 * the example directory), {@code CASE.expected} (stdout and stderr) and optionally {@code
 * CASE.exitcode}. Programs run on a fresh JVM with {@code -Xverify:all}.
 */
class ExamplesTest {
  private static final Path EXAMPLES = Path.of(System.getProperty("jsharp.examples"));

  @TestFactory
  Stream<DynamicNode> examples() throws IOException {
    List<DynamicNode> out = new ArrayList<>();
    try (Stream<Path> s = Files.list(EXAMPLES)) {
      for (Path dir : s.filter(d -> Files.isDirectory(d.resolve("test"))).sorted().toList()) {
        out.add(DynamicContainer.dynamicContainer(dir.getFileName().toString(), cases(dir)));
      }
    }
    return out.stream();
  }

  private static Stream<DynamicNode> cases(Path dir) throws IOException {
    Path classes = Files.createTempDirectory("jsharp-example-" + dir.getFileName());
    String mainClass = compile(dir.resolve("src"), classes);
    List<DynamicNode> tests = new ArrayList<>();
    try (Stream<Path> s = Files.list(dir.resolve("test"))) {
      for (Path args : s.filter(p -> p.toString().endsWith(".args")).sorted().toList()) {
        String name = args.getFileName().toString().replace(".args", "");
        tests.add(DynamicTest.dynamicTest(name, () -> run(dir, classes, mainClass, name)));
      }
    }
    return tests.stream();
  }

  private static String compile(Path src, Path out) throws IOException {
    List<SourceFile> files = new ArrayList<>();
    try (Stream<Path> s = Files.walk(src)) {
      for (Path p : s.filter(x -> x.toString().endsWith(".jsharp")).sorted().toList()) {
        files.add(new SourceFile(src.relativize(p).toString(), Files.readString(p)));
      }
    }
    Compilation comp = new Compilation(files, CompilerOptions.defaults().withOutputDir(out), null);
    comp.compile();
    comp.close();
    assertThat(comp.diagnostics().hasErrors())
        .as("%s", new DiagnosticRenderer(false).renderAll(comp.diagnostics().sorted()))
        .isFalse();
    assertThat(TestSupport.verify(comp.classFiles())).isEmpty();
    return comp.mainClass();
  }

  private static void run(Path dir, Path classes, String mainClass, String name) throws Exception {
    Path test = dir.resolve("test");
    List<String> cmd =
        new ArrayList<>(
            List.of(
                TestSupport.JAVA,
                "-Xverify:all",
                "-XX:TieredStopAtLevel=1",
                "-cp",
                classes + File.pathSeparator + TestSupport.RUNTIME,
                mainClass));
    cmd.addAll(Files.readAllLines(test.resolve(name + ".args")));
    Process p =
        new ProcessBuilder(cmd)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
            .start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
    Path exitFile = test.resolve(name + ".exitcode");
    int expectedExit =
        Files.exists(exitFile) ? Integer.parseInt(Files.readString(exitFile).trim()) : 0;
    assertThat(p.exitValue()).as("exit code; output:%n%s", output).isEqualTo(expectedExit);
    assertThat(output).isEqualTo(Files.readString(test.resolve(name + ".expected")));
  }
}

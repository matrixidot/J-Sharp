package dev.jsharp.tests;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jsharp.compiler.diag.DiagnosticRenderer;
import dev.jsharp.compiler.driver.Compilation;
import dev.jsharp.compiler.driver.CompilerOptions;
import dev.jsharp.compiler.source.SourceFile;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Java/J# interop in both directions. Each {@code tests/interop/NAME/} has {@code lib/} compiled
 * first and {@code main/} compiled against it (each Java or J#), then the program runs on a fresh
 * JVM with {@code -Xverify:all} and its stdout must equal {@code expected.txt}.
 */
class InteropTest {
  private static final Path CASES = Path.of(System.getProperty("jsharp.interop"));

  @TestFactory
  Stream<DynamicTest> cases() throws IOException {
    List<Path> dirs;
    try (Stream<Path> s = Files.list(CASES)) {
      dirs = s.filter(Files::isDirectory).sorted().toList();
    }
    return dirs.stream()
        .map(d -> DynamicTest.dynamicTest(d.getFileName().toString(), () -> runCase(d)));
  }

  private static void runCase(Path dir) throws Exception {
    Path work = Files.createTempDirectory("jsharp-interop-" + dir.getFileName());
    Path libOut = work.resolve("lib");
    Path mainOut = work.resolve("main");
    compile(dir.resolve("lib"), List.of(), libOut);
    String jsharpMain = compile(dir.resolve("main"), List.of(libOut), mainOut);
    String mainClass = jsharpMain;
    if (mainClass == null) {
      Path mc = dir.resolve("mainclass.txt");
      mainClass = Files.exists(mc) ? Files.readString(mc).trim() : "Main";
    }
    TestSupport.Run run = TestSupport.runJava(List.of(mainOut, libOut), mainClass, List.of(), null);
    assertThat(run.stderr()).as("stderr").isEmpty();
    assertThat(run.exitCode()).isZero();
    assertThat(run.stdout()).isEqualTo(Files.readString(dir.resolve("expected.txt")));
  }

  /**
   * Compiles the Java or J# sources in {@code src} into {@code out}; returns the J# entry point
   * class (or null).
   */
  private static String compile(Path src, List<Path> classPath, Path out) throws IOException {
    Files.createDirectories(out);
    List<Path> java = TestSupport.files(src, ".java");
    List<Path> jsharp = TestSupport.files(src, ".jsharp");
    assertThat(java.isEmpty() || jsharp.isEmpty()).as("%s mixes Java and J# sources", src).isTrue();
    if (!java.isEmpty()) {
      var javac = ToolProvider.getSystemJavaCompiler();
      List<String> args = new ArrayList<>();
      List<String> cp = new ArrayList<>();
      classPath.forEach(p -> cp.add(p.toString()));
      cp.add(TestSupport.RUNTIME);
      args.addAll(
          List.of("-d", out.toString(), "-cp", String.join(File.pathSeparator, cp), "-proc:none"));
      java.forEach(p -> args.add(p.toString()));
      StringWriter errors = new StringWriter();
      int rc =
          javac.run(
              null,
              null,
              new java.io.PrintStream(
                  new java.io.OutputStream() {
                    @Override
                    public void write(int b) {
                      errors.write(b);
                    }
                  }),
              args.toArray(String[]::new));
      assertThat(rc).as("javac failed:%n%s", errors).isZero();
      return null;
    }
    List<SourceFile> files = new ArrayList<>();
    for (Path p : jsharp) {
      files.add(new SourceFile(src.relativize(p).toString(), Files.readString(p)));
    }
    Compilation comp =
        new Compilation(
            files, CompilerOptions.defaults().withClassPath(classPath).withOutputDir(out), null);
    comp.compile();
    comp.close();
    assertThat(comp.diagnostics().hasErrors())
        .as("J# errors:%n%s", new DiagnosticRenderer(false).renderAll(comp.diagnostics().sorted()))
        .isFalse();
    assertThat(TestSupport.verify(comp.classFiles(), classPath)).as("ClassFile verifier").isEmpty();
    return comp.mainClass();
  }
}

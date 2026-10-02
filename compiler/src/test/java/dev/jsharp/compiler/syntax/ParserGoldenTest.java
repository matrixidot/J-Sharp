package dev.jsharp.compiler.syntax;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jsharp.compiler.ast.AstPrinter;
import dev.jsharp.compiler.ast.CompilationUnit;
import dev.jsharp.compiler.diag.Diagnostics;
import dev.jsharp.compiler.source.SourceFile;
import dev.jsharp.compiler.testing.Golden;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Golden parser tests. {@code parse/X.jsharp} must parse without errors and print as {@code X.ast};
 * {@code syntax-errors/X.jsharp} must produce exactly the diagnostics in {@code X.diagnostics}.
 */
class ParserGoldenTest {

  @TestFactory
  Stream<DynamicTest> validPrograms() {
    Path dir = Golden.resourceDir("parse");
    return Golden.files(dir, ".jsharp").stream()
        .map(
            f ->
                DynamicTest.dynamicTest(
                    f.getFileName().toString(),
                    () -> {
                      SourceFile src = new SourceFile(f.getFileName().toString(), Golden.read(f));
                      Diagnostics d = new Diagnostics();
                      CompilationUnit u = Parser.parse(src, d);
                      assertThat(Golden.diagnostics(d.sorted())).isEmpty();
                      Golden.check(siblingWith(f, ".ast"), AstPrinter.print(u));
                    }));
  }

  @TestFactory
  Stream<DynamicTest> syntaxErrors() {
    Path dir = Golden.resourceDir("syntax-errors");
    return Golden.files(dir, ".jsharp").stream()
        .map(
            f ->
                DynamicTest.dynamicTest(
                    f.getFileName().toString(),
                    () -> {
                      SourceFile src = new SourceFile(f.getFileName().toString(), Golden.read(f));
                      Diagnostics d = new Diagnostics();
                      Parser.parse(src, d);
                      String actual = Golden.diagnostics(d.sorted());
                      assertThat(actual).as("expected at least one diagnostic").isNotEmpty();
                      Golden.check(siblingWith(f, ".diagnostics"), actual);
                    }));
  }

  static Path siblingWith(Path f, String ext) {
    String name = f.getFileName().toString();
    return f.resolveSibling(name.substring(0, name.lastIndexOf('.')) + ext);
  }
}

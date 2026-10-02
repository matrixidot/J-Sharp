package dev.jsharp.compiler.diag;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jsharp.compiler.source.SourceFile;
import dev.jsharp.compiler.source.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticRendererTest {
  private final SourceFile file = new SourceFile("src/Main.jsharp", "val x = 1;\nvar y = x +;\n");

  @Test
  void rendersRustStyleSnippet() {
    Diagnostic d =
        Diagnostic.error(
                Code.EXPECTED_EXPRESSION, file, new Span(22, 23), "expected expression, found ';'")
            .label("expected an operand")
            .help("add an operand after '+'")
            .build();
    String out = new DiagnosticRenderer(false).render(d);
    assertThat(out)
        .isEqualTo(
            """
            error[JS0101]: expected expression, found ';'
             --> src/Main.jsharp:2:12
              |
            2 | var y = x +;
              |            ^ expected an operand
              = help: add an operand after '+'
            """);
  }

  @Test
  void rendersJson() {
    Diagnostic d =
        Diagnostic.error(Code.EXPECTED_TOKEN, file, new Span(9, 10), "expected \"x\"").build();
    String json = DiagnosticRenderer.renderJson(List.of(d));
    assertThat(json)
        .contains("\"code\":\"JS0100\"")
        .contains("\"line\":1")
        .contains("\"column\":10")
        .contains("\"message\":\"expected \\\"x\\\"\"");
  }

  @Test
  void codesAreUniqueAndWellFormed() {
    java.util.Set<Integer> seen = new java.util.HashSet<>();
    for (Code c : Code.values()) {
      assertThat(seen.add(c.number())).as("duplicate code %s", c).isTrue();
      assertThat(c.id()).matches("JS\\d{4}");
    }
  }

  @Test
  void duplicatesAreDropped() {
    Diagnostics ds = new Diagnostics();
    Diagnostic d = Diagnostic.error(Code.EXPECTED_TOKEN, file, new Span(0, 1), "m").build();
    ds.report(d);
    ds.report(d);
    assertThat(ds.all()).hasSize(1);
    assertThat(ds.errorCount()).isEqualTo(1);
  }
}

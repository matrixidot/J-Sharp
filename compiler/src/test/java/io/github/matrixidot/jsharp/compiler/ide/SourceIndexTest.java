package io.github.matrixidot.jsharp.compiler.ide;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matrixidot.jsharp.compiler.driver.Compilation;
import io.github.matrixidot.jsharp.compiler.driver.CompilerOptions;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceIndexTest {
  static final String SRC =
      """
      import java.util.*;
      public class Point {
          public int x { get; set; }
          public Point(int x) { this.x = x; }
          public int twice() => x * 2;
      }
      var alpha = new Point(3);
      int beta(int gamma) => gamma + alpha2();
      int alpha2() => 2;
      foreach (var delta in List.of(1, 2)) println(delta + alpha.twice());
      if (beta(1) is int eps) println(eps + alpha.x);
      """;

  private final SourceFile file = new SourceFile("idx.jsharp", SRC);

  private SourceIndex index() {
    Compilation c = new Compilation(List.of(file), CompilerOptions.defaults(), null);
    assertThat(c.analyze()).as(c.diagnostics().sorted().toString()).isTrue();
    return c.index();
  }

  private int offset(String needle, int occurrence) {
    int at = -1;
    for (int i = 0; i <= occurrence; i++) {
      at = SRC.indexOf(needle, at + 1);
    }
    return at + 1; // inside the word
  }

  @Test
  void findsLocalsAndTheirDeclarations() {
    SourceIndex idx = index();
    var use = idx.at(file, offset("gamma +", 0)).orElseThrow();
    assertThat(use.kind()).isEqualTo(SourceIndex.Kind.LOCAL);
    assertThat(use.symbol().name()).isEqualTo("gamma");
    var decl = idx.declaration(use.symbol()).orElseThrow();
    assertThat(SRC.substring(decl.span().start(), decl.span().end())).isEqualTo("gamma");
    assertThat(SRC.substring(0, decl.span().start())).endsWith("int beta(int ");
  }

  @Test
  void findsMethodsPropertiesAndPatternBindings() {
    SourceIndex idx = index();
    var call = idx.at(file, offset("alpha2()", 0)).orElseThrow();
    assertThat(call.kind()).isEqualTo(SourceIndex.Kind.METHOD);
    assertThat(call.symbol().name()).isEqualTo("alpha2");
    var target = idx.declaration(call.symbol()).orElseThrow();
    assertThat(SRC.substring(target.span().start(), target.span().end())).isEqualTo("alpha2");

    var twice = idx.at(file, offset("twice()", 1)).orElseThrow();
    assertThat(twice.symbol().name()).isEqualTo("twice");
    assertThat(twice.type().display()).isEqualTo("int");

    var prop = idx.at(file, offset("alpha.x", 0) + 6).orElseThrow();
    assertThat(prop.kind()).isEqualTo(SourceIndex.Kind.PROPERTY);
    assertThat(prop.symbol().name()).isEqualTo("x");

    var eps = idx.at(file, offset("eps +", 0)).orElseThrow();
    assertThat(eps.symbol().name()).isEqualTo("eps");
    assertThat(idx.declaration(eps.symbol())).isPresent();

    var delta = idx.at(file, offset("delta +", 0)).orElseThrow();
    assertThat(delta.type().display()).startsWith("Integer");
  }

  @Test
  void receiverBeforeDot() {
    SourceIndex idx = index();
    int dot = SRC.indexOf("alpha.twice") + "alpha".length();
    var recv = idx.endingAt(file, dot).orElseThrow();
    assertThat(recv.type().display()).startsWith("Point");
  }
}

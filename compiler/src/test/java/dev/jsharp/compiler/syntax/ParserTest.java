package dev.jsharp.compiler.syntax;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jsharp.compiler.ast.AstPrinter;
import dev.jsharp.compiler.ast.CompilationUnit;
import dev.jsharp.compiler.ast.Expr;
import dev.jsharp.compiler.diag.Diagnostics;
import dev.jsharp.compiler.source.SourceFile;
import dev.jsharp.compiler.testing.Golden;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Focused parser tests for precedence and disambiguation rules. */
class ParserTest {

  private static String expr(String src) {
    Diagnostics d = new Diagnostics();
    Expr e = Parser.parseExpression(new SourceFile("t", src), d);
    assertThat(Golden.diagnostics(d.sorted())).isEmpty();
    return AstPrinter.print(e).strip();
  }

  private static String unit(String src) {
    Diagnostics d = new Diagnostics();
    CompilationUnit u = Parser.parse(new SourceFile("t", src), d);
    assertThat(Golden.diagnostics(d.sorted())).isEmpty();
    return AstPrinter.print(u).strip();
  }

  @ParameterizedTest
  @CsvSource(
      delimiterString = " -> ",
      value = {
        "a + b * c -> (+ a (* b c))",
        "a - b - c -> (- (- a b) c)",
        "a = b = c -> (= a (= b c))",
        "a ?? b ?? c -> (?? a (?? b c))",
        "a || b && c -> (|| a (&& b c))",
        "a == b < c -> (== a (< b c))",
        "a < b << c -> (< a (<< b c))",
        "1..n + 1 -> (.. 1 (+ n 1))",
        "x is int && y -> (&& (is x (type-pat int)) y)",
        "-x.y -> (- (. x y))",
        "!a! -> (! (!! a))",
        "a >> b > c -> (> (>> a b) c)",
        "a > > b -> (> (> a <error>) b)",
        "c ? a : b ? d : e -> (?: c a (?: b d e))",
        "x switch { _ => 1 } + 2 -> (+ (switch x (arm _ 1)) 2)",
        "p with { x = 1 } -> (with p (= x 1))",
        "(a) - b -> (- (paren a) b)",
        "(int) - b -> (cast int (- b))",
        "(Foo) x -> (cast Foo x)",
        "(Foo) is Bar -> (is (paren Foo) (const Bar))",
        "(a, b) -> (tuple a b)",
        "(a, b) => a -> (lambda (params (param a) (param b)) (=> a))",
        "a < b -> (< a b)",
        "f<T>(x) -> (call f<T> x)",
        "a < b > (c) -> (call a<b> c)",
        "a?.b?[0] -> (?[] (?. a b) 0)",
        "await x -> (await x)",
        "^1 -> (^ 1)",
        "x!.y -> (. (!! x) y)",
      })
  void precedenceAndDisambiguation(String source, String expected) {
    if (expected.contains("<error>")) {
      Diagnostics d = new Diagnostics();
      Expr e = Parser.parseExpression(new SourceFile("t", source), d);
      assertThat(d.hasErrors()).isTrue();
      assertThat(AstPrinter.print(e).strip()).isEqualTo(expected);
      return;
    }
    assertThat(expr(source)).isEqualTo(expected);
  }

  @Test
  void contextualKeywordsRemainUsableAsNames() {
    assertThat(unit("System.out.println(System.in);"))
        .contains("(. System out)")
        .contains("(. System in)");
    assertThat(unit("var value = 1; var record = 2; var where = value + record;"))
        .contains("(= where (+ value record))");
    assertThat(unit("var open = 1; open(); sealed(1);")).contains("(call open)");
    assertThat(unit("list.where(x => x > 1).select(x => x);")).contains("where").contains("select");
  }

  @Test
  void topLevelStatementsVersusDeclarations() {
    String u = unit("int square(int x) => x * x;\nvar y = square(3);\npublic val PI = 3.14;\n");
    assertThat(u)
        .contains("(method square")
        .contains("(top (local var")
        .contains("(field (mods public) val");
  }

  @Test
  void awaitIsAnIdentifierOutsideAsyncCode() {
    String u = unit("class A { int f() { var await = 1; return await + 1; } }");
    assertThat(u).contains("(+ await 1)");
  }

  @Test
  void genericTypesNestClosingAngles() {
    assertThat(unit("Map<String, List<List<int>>> m = new();"))
        .contains("(local Map<String, List<List<int>>>");
  }

  @Test
  void deeplyNestedInputReportsInsteadOfOverflowing() {
    String src = "var x = " + "(".repeat(5000) + "1" + ")".repeat(5000) + ";";
    Diagnostics d = new Diagnostics();
    Parser.parse(new SourceFile("t", src), d);
    assertThat(d.hasErrors()).isTrue();
    assertThat(d.all().getFirst().message()).contains("nested too deeply");
  }
}

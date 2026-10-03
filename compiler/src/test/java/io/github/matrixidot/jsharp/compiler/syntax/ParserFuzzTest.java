package io.github.matrixidot.jsharp.compiler.syntax;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostics;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.testing.Golden;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Robustness: the parser must never throw, hang, or produce out-of-range diagnostics on mutated
 * versions of valid programs.
 */
class ParserFuzzTest {
  private static final int ITERATIONS = 10_000;

  private static final String[] SNIPPETS = {
    "(", ")", "{", "}", "[", "]", ";", ",", ".", "..", "=>", "?", "?.", "??", "!", "<", ">", ">>",
    "=", "==", "\"", "'", "$\"{", "}\"", "\"\"\"", "/*", "//", "class ", "var ", "if ", "else ",
    "switch ", "case ", "new ", "is ", "with ", "async ", "await ", "record ", "x", "42", "0x", "@",
    "::", "^", "#", "`", "\n", " and ", " or ", " not ", "when ", "=> {", "<T>", "int[]",
  };

  @Test
  void mutatedProgramsNeverCrashTheParser() {
    List<String> corpus = new ArrayList<>();
    for (String dir : List.of("parse", "syntax-errors")) {
      for (Path p : Golden.files(Golden.resourceDir(dir), ".jsharp")) {
        corpus.add(Golden.read(p));
      }
    }
    SplittableRandom rnd = new SplittableRandom(0x15C0FFEEL);
    for (int i = 0; i < ITERATIONS; i++) {
      String base = corpus.get(rnd.nextInt(corpus.size()));
      String mutated = mutate(base, rnd, 1 + rnd.nextInt(4));
      SourceFile f = new SourceFile("fuzz" + i + ".jsharp", mutated);
      Diagnostics d = new Diagnostics();
      try {
        Parser.parse(f, d);
      } catch (RuntimeException | StackOverflowError e) {
        throw new AssertionError("parser threw on iteration " + i + ":\n" + mutated, e);
      }
      for (Diagnostic diag : d.all()) {
        assertThat(diag.span().end())
            .as("span in range, iteration %d", i)
            .isLessThanOrEqualTo(mutated.length());
        assertThat(diag.code()).isNotNull();
      }
    }
  }

  private static String mutate(String s, SplittableRandom rnd, int times) {
    StringBuilder sb = new StringBuilder(s);
    for (int t = 0; t < times; t++) {
      int len = sb.length();
      int at = len == 0 ? 0 : rnd.nextInt(len);
      switch (rnd.nextInt(6)) {
        case 0 -> sb.delete(at, Math.min(len, at + 1 + rnd.nextInt(8)));
        case 1 -> sb.insert(at, SNIPPETS[rnd.nextInt(SNIPPETS.length)]);
        case 2 -> {
          int to = Math.min(len, at + rnd.nextInt(40));
          sb.insert(rnd.nextInt(len + 1), sb.substring(at, to));
        }
        case 3 -> {
          if (len > 1) {
            int b = rnd.nextInt(len);
            char c = sb.charAt(at);
            sb.setCharAt(at, sb.charAt(b));
            sb.setCharAt(b, c);
          }
        }
        case 4 -> sb.setLength(at);
        default -> sb.insert(at, (char) rnd.nextInt(32, 127));
      }
    }
    return sb.toString();
  }
}

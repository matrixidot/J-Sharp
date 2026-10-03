package io.github.matrixidot.jsharp.compiler.check;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates interpolation format specifiers to {@link java.util.Formatter} patterns. Accepts the
 * common .NET standard numeric formats ({@code F2}, {@code N0}, {@code D5}, {@code X8}, {@code E3},
 * {@code P1}, {@code G}) and raw Java specs starting with {@code %} (e.g. {@code %08.3f}).
 */
final class FormatSpecs {
  private FormatSpecs() {}

  private static final Pattern DOTNET = Pattern.compile("([FfNnDdXxEePpGg])(\\d{0,2})");

  /** Kinds of value a format applies to. */
  enum Domain {
    INTEGRAL,
    FLOATING,
    ANY
  }

  /** A translated format: Java pattern, and the operand domain it requires. */
  record Translation(String javaFormat, Domain domain, boolean percent) {}

  /** Returns the translation, or null if the spec is not recognized. */
  static Translation translate(String spec) {
    if (spec.startsWith("%")) {
      return new Translation(spec, Domain.ANY, false);
    }
    Matcher m = DOTNET.matcher(spec);
    if (!m.matches()) {
      return null;
    }
    char k = m.group(1).charAt(0);
    String digits = m.group(2);
    Integer n = digits.isEmpty() ? null : Integer.parseInt(digits);
    return switch (Character.toUpperCase(k)) {
      case 'F' -> new Translation("%." + (n == null ? 2 : n) + "f", Domain.FLOATING, false);
      case 'N' -> new Translation("%,." + (n == null ? 2 : n) + "f", Domain.FLOATING, false);
      case 'E' ->
          new Translation(
              "%." + (n == null ? 6 : n) + (k == 'e' ? "e" : "E"), Domain.FLOATING, false);
      case 'P' -> new Translation("%." + (n == null ? 2 : n) + "f%%", Domain.FLOATING, true);
      case 'D' -> new Translation(n == null ? "%d" : "%0" + n + "d", Domain.INTEGRAL, false);
      case 'X' ->
          new Translation(
              (n == null ? "%" : "%0" + n) + (k == 'x' ? "x" : "X"), Domain.INTEGRAL, false);
      case 'G' -> new Translation("%s", Domain.ANY, false);
      default -> null;
    };
  }
}

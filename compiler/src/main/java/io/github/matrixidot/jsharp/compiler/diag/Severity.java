package io.github.matrixidot.jsharp.compiler.diag;

import java.util.Locale;

/** How serious a diagnostic is. Only {@link #ERROR} stops code generation. */
public enum Severity {
  ERROR,
  WARNING,
  INFO;

  public String label() {
    return name().toLowerCase(Locale.ROOT);
  }
}

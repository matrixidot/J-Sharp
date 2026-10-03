package io.github.matrixidot.jsharp.compiler.diag;

/** How serious a diagnostic is. Only {@link #ERROR} stops code generation. */
public enum Severity {
  ERROR,
  WARNING,
  INFO;

  public String label() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}

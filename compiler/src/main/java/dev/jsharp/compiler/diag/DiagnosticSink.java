package dev.jsharp.compiler.diag;

/** Receives diagnostics as compilation phases produce them. */
@FunctionalInterface
public interface DiagnosticSink {
  void report(Diagnostic diagnostic);
}

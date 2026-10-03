package io.github.matrixidot.jsharp.compiler.diag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A per-compilation diagnostic collector. Drops exact duplicates (same code, file and span) so that
 * error recovery cannot spam identical messages.
 */
public final class Diagnostics implements DiagnosticSink {
  private final List<Diagnostic> all = new ArrayList<>();
  private final Set<String> seen = new HashSet<>();
  private int errors;
  private final boolean warningsAsErrors;

  public Diagnostics() {
    this(false);
  }

  public Diagnostics(boolean warningsAsErrors) {
    this.warningsAsErrors = warningsAsErrors;
  }

  @Override
  public void report(Diagnostic d) {
    if (warningsAsErrors && d.severity() == Severity.WARNING) {
      d =
          new Diagnostic(
              Severity.ERROR,
              d.code(),
              d.message(),
              d.file(),
              d.span(),
              d.label(),
              d.notes(),
              d.help());
    }
    String key =
        d.code()
            + "|"
            + (d.file() == null ? "" : d.file().path())
            + "|"
            + d.span()
            + "|"
            + d.message();
    if (!seen.add(key)) {
      return;
    }
    all.add(d);
    if (d.isError()) {
      errors++;
    }
  }

  public boolean hasErrors() {
    return errors > 0;
  }

  public int errorCount() {
    return errors;
  }

  /** All diagnostics, sorted by file, then position, then report order. */
  public List<Diagnostic> sorted() {
    List<Diagnostic> copy = new ArrayList<>(all);
    copy.sort(
        Comparator.comparing((Diagnostic d) -> d.file() == null ? "" : d.file().path())
            .thenComparingInt(d -> d.span() == null ? -1 : d.span().start()));
    return copy;
  }

  public List<Diagnostic> all() {
    return List.copyOf(all);
  }
}

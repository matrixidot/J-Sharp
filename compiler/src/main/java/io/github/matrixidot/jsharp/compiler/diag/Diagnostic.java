package io.github.matrixidot.jsharp.compiler.diag;

import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import java.util.ArrayList;
import java.util.List;

/**
 * A structured compiler message. Rendering is separate (see {@link DiagnosticRenderer}).
 *
 * @param severity error/warning/info
 * @param code stable diagnostic code
 * @param message primary message, lowercase start, no trailing period
 * @param file the file the span refers to (may be {@code null} for global diagnostics)
 * @param span primary location (may be {@code null} for global diagnostics)
 * @param label short text shown next to the caret, or {@code null}
 * @param notes related notes
 * @param help a suggested fix, or {@code null}
 */
public record Diagnostic(
    Severity severity,
    Code code,
    String message,
    SourceFile file,
    Span span,
    String label,
    List<Note> notes,
    String help) {

  public Diagnostic {
    notes = List.copyOf(notes);
  }

  public static Builder error(Code code, SourceFile file, Span span, String message) {
    return new Builder(code.defaultSeverity(), code, file, span, message);
  }

  public boolean isError() {
    return severity == Severity.ERROR;
  }

  /** 1-based line of the span start, or 0 when there is no location. */
  public int line() {
    return file == null || span == null ? 0 : file.line(span.start());
  }

  /** 1-based column of the span start, or 0 when there is no location. */
  public int column() {
    return file == null || span == null ? 0 : file.column(span.start());
  }

  /** Fluent builder; finish with {@link #report(DiagnosticSink)} or {@link #build()}. */
  public static final class Builder {
    private Severity severity;
    private final Code code;
    private final SourceFile file;
    private final Span span;
    private final String message;
    private String label;
    private final List<Note> notes = new ArrayList<>();
    private String help;

    Builder(Severity severity, Code code, SourceFile file, Span span, String message) {
      this.severity = severity;
      this.code = code;
      this.file = file;
      this.span = span;
      this.message = message;
    }

    public Builder severity(Severity s) {
      this.severity = s;
      return this;
    }

    public Builder label(String l) {
      this.label = l;
      return this;
    }

    public Builder note(String n) {
      notes.add(Note.of(n));
      return this;
    }

    public Builder note(String n, SourceFile f, Span s) {
      notes.add(new Note(n, f, s));
      return this;
    }

    public Builder help(String h) {
      this.help = h;
      return this;
    }

    public Diagnostic build() {
      return new Diagnostic(severity, code, message, file, span, label, notes, help);
    }

    public void report(DiagnosticSink sink) {
      sink.report(build());
    }
  }
}

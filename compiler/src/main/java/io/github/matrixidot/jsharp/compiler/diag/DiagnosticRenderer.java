package io.github.matrixidot.jsharp.compiler.diag;

import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import java.util.List;

/** Renders diagnostics as Rust-style human text or as machine-readable JSON. */
public final class DiagnosticRenderer {
  private static final String RESET = "\u001b[0m";
  private static final String BOLD = "\u001b[1m";
  private static final String RED = "\u001b[31m";
  private static final String YELLOW = "\u001b[33m";
  private static final String BLUE = "\u001b[34m";
  private static final String CYAN = "\u001b[36m";

  private final boolean color;

  public DiagnosticRenderer(boolean color) {
    this.color = color;
  }

  private String c(String code, String s) {
    return color ? code + s + RESET : s;
  }

  /** Renders a single diagnostic, ending with a newline. */
  public String render(Diagnostic d) {
    StringBuilder sb = new StringBuilder();
    String sevColor =
        d.severity() == Severity.ERROR ? RED : d.severity() == Severity.WARNING ? YELLOW : CYAN;
    sb.append(c(BOLD + sevColor, d.severity().label() + "[" + d.code().id() + "]"))
        .append(c(BOLD, ": " + d.message()))
        .append('\n');
    if (d.file() != null && d.span() != null) {
      SourceFile f = d.file();
      Span s = d.span();
      int line = f.line(s.start());
      int col = f.column(s.start());
      String gutter = " ".repeat(String.valueOf(line).length());
      sb.append(gutter)
          .append(c(BLUE, "--> "))
          .append(f.path())
          .append(':')
          .append(line)
          .append(':')
          .append(col)
          .append('\n');
      sb.append(gutter).append(c(BLUE, " |")).append('\n');
      String text = f.lineText(line);
      sb.append(c(BLUE, line + " | ")).append(expandTabs(text)).append('\n');
      int endLine = f.line(Math.max(s.start(), s.end() - 1));
      int caretStart = visualColumn(text, col - 1);
      int caretEnd;
      if (endLine == line) {
        caretEnd = visualColumn(text, Math.min(text.length(), f.column(s.end()) - 1));
      } else {
        caretEnd = visualColumn(text, text.length());
      }
      int width = Math.max(1, caretEnd - caretStart);
      sb.append(gutter)
          .append(c(BLUE, " | "))
          .append(" ".repeat(caretStart))
          .append(c(BOLD + sevColor, "^".repeat(width)));
      if (d.label() != null) {
        sb.append(' ').append(c(BOLD + sevColor, d.label()));
      }
      sb.append('\n');
      for (Note n : d.notes()) {
        sb.append(gutter).append(c(BLUE, " = ")).append(c(BOLD, "note: ")).append(n.message());
        if (n.file() != null && n.span() != null) {
          sb.append(" (")
              .append(n.file().path())
              .append(':')
              .append(n.file().line(n.span().start()))
              .append(':')
              .append(n.file().column(n.span().start()))
              .append(')');
        }
        sb.append('\n');
      }
      if (d.help() != null) {
        sb.append(gutter)
            .append(c(BLUE, " = "))
            .append(c(BOLD, "help: "))
            .append(d.help())
            .append('\n');
      }
    } else {
      for (Note n : d.notes()) {
        sb.append("  = note: ").append(n.message()).append('\n');
      }
      if (d.help() != null) {
        sb.append("  = help: ").append(d.help()).append('\n');
      }
    }
    return sb.toString();
  }

  /** Renders all diagnostics followed by a summary line. */
  public String renderAll(List<Diagnostic> ds) {
    StringBuilder sb = new StringBuilder();
    int errors = 0;
    int warnings = 0;
    for (Diagnostic d : ds) {
      sb.append(render(d)).append('\n');
      if (d.severity() == Severity.ERROR) {
        errors++;
      } else if (d.severity() == Severity.WARNING) {
        warnings++;
      }
    }
    if (errors > 0 || warnings > 0) {
      sb.append(plural(errors, "error"))
          .append(", ")
          .append(plural(warnings, "warning"))
          .append('\n');
    }
    return sb.toString();
  }

  private static String plural(int n, String word) {
    return n + " " + word + (n == 1 ? "" : "s");
  }

  private static String expandTabs(String s) {
    return s.replace("\t", "    ");
  }

  private static int visualColumn(String line, int index) {
    int v = 0;
    for (int i = 0; i < index && i < line.length(); i++) {
      v += line.charAt(i) == '\t' ? 4 : 1;
    }
    return v;
  }

  /** Renders diagnostics as a JSON array (one object per diagnostic). */
  public static String renderJson(List<Diagnostic> ds) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < ds.size(); i++) {
      Diagnostic d = ds.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("\n  {\"severity\":").append(str(d.severity().label()));
      sb.append(",\"code\":").append(str(d.code().id()));
      sb.append(",\"message\":").append(str(d.message()));
      if (d.file() != null) {
        sb.append(",\"file\":").append(str(d.file().path()));
      }
      if (d.file() != null && d.span() != null) {
        sb.append(",\"line\":").append(d.line());
        sb.append(",\"column\":").append(d.column());
        sb.append(",\"endLine\":").append(d.file().line(d.span().end()));
        sb.append(",\"endColumn\":").append(d.file().column(d.span().end()));
        sb.append(",\"start\":").append(d.span().start());
        sb.append(",\"end\":").append(d.span().end());
      }
      if (d.label() != null) {
        sb.append(",\"label\":").append(str(d.label()));
      }
      if (d.help() != null) {
        sb.append(",\"help\":").append(str(d.help()));
      }
      if (!d.notes().isEmpty()) {
        sb.append(",\"notes\":[");
        for (int j = 0; j < d.notes().size(); j++) {
          if (j > 0) {
            sb.append(',');
          }
          sb.append(str(d.notes().get(j).message()));
        }
        sb.append(']');
      }
      sb.append('}');
    }
    return sb.append(ds.isEmpty() ? "]" : "\n]").toString();
  }

  static String str(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      switch (ch) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (ch < 0x20) {
            sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
          } else {
            sb.append(ch);
          }
        }
      }
    }
    return sb.append('"').toString();
  }
}

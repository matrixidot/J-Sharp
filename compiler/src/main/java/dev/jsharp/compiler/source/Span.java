package dev.jsharp.compiler.source;

/**
 * A half-open range {@code [start, end)} of UTF-16 offsets within a {@link SourceFile}.
 *
 * @param start inclusive start offset
 * @param end exclusive end offset
 */
public record Span(int start, int end) {
  /** A placeholder span for synthesized nodes. */
  public static final Span NONE = new Span(0, 0);

  public Span {
    if (start < 0 || end < start) {
      throw new IllegalArgumentException("bad span [" + start + ", " + end + ")");
    }
  }

  /** Returns the smallest span covering both {@code this} and {@code other}. */
  public Span to(Span other) {
    return new Span(Math.min(start, other.start), Math.max(end, other.end));
  }

  public int length() {
    return end - start;
  }

  @Override
  public String toString() {
    return start + ".." + end;
  }
}

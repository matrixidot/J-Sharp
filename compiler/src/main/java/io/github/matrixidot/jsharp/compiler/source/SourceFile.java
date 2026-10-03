package io.github.matrixidot.jsharp.compiler.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** An immutable source text plus a line index for offset to line/column conversion. */
public final class SourceFile {
  private final String path;
  private final String content;
  private final int[] lineStarts;

  /**
   * @param path display path used in diagnostics and the {@code SourceFile} class attribute
   * @param content the full source text
   */
  public SourceFile(String path, String content) {
    this.path = path;
    this.content = content;
    this.lineStarts = computeLineStarts(content);
  }

  /** Reads a UTF-8 file from disk. */
  public static SourceFile read(Path file) throws IOException {
    return new SourceFile(file.toString(), Files.readString(file, StandardCharsets.UTF_8));
  }

  public String path() {
    return path;
  }

  /** The file name without directories, e.g. {@code Main.jsharp}. */
  public String fileName() {
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return path.substring(slash + 1);
  }

  public String content() {
    return content;
  }

  public String text(Span span) {
    return content.substring(span.start(), Math.min(span.end(), content.length()));
  }

  /** 1-based line number containing {@code offset}. */
  public int line(int offset) {
    int idx = Arrays.binarySearch(lineStarts, Math.min(offset, content.length()));
    return idx >= 0 ? idx + 1 : -idx - 1;
  }

  /** 1-based column (in UTF-16 units) of {@code offset}. */
  public int column(int offset) {
    int line = line(offset);
    return Math.min(offset, content.length()) - lineStarts[line - 1] + 1;
  }

  public int lineCount() {
    return lineStarts.length;
  }

  /** The text of the 1-based {@code line} without its line terminator. */
  public String lineText(int line) {
    int start = lineStarts[line - 1];
    int end = line < lineStarts.length ? lineStarts[line] : content.length();
    while (end > start && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) {
      end--;
    }
    return content.substring(start, end);
  }

  /** Offset of the first character of the 1-based {@code line}. */
  public int lineStart(int line) {
    return lineStarts[line - 1];
  }

  private static int[] computeLineStarts(String s) {
    int count = 1;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n') {
        count++;
      }
    }
    int[] starts = new int[count];
    int n = 1;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n') {
        starts[n++] = i + 1;
      }
    }
    return starts;
  }

  @Override
  public String toString() {
    return path;
  }
}

package io.github.matrixidot.jsharp.compiler.diag;

import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;

/**
 * Secondary information attached to a diagnostic, optionally pointing at another location.
 *
 * @param message the note text
 * @param file file of the related location, or {@code null}
 * @param span the related location, or {@code null}
 */
public record Note(String message, SourceFile file, Span span) {
  public static Note of(String message) {
    return new Note(message, null, null);
  }
}

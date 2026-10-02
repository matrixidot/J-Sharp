package dev.jsharp.compiler.ast;

import dev.jsharp.compiler.source.Span;
import java.util.List;

/**
 * Modifiers and annotations preceding a declaration.
 *
 * @param list modifiers in source order
 * @param annotations annotations in source order
 * @param span covering span (empty span at the declaration start if there are none)
 */
public record Modifiers(List<Item> list, List<Annotation> annotations, Span span) implements Node {
  public Modifiers {
    list = List.copyOf(list);
    annotations = List.copyOf(annotations);
  }

  /** One modifier keyword occurrence. */
  public record Item(Modifier modifier, Span span) {}

  public static Modifiers empty(int at) {
    return new Modifiers(List.of(), List.of(), new Span(at, at));
  }

  public boolean has(Modifier m) {
    for (Item i : list) {
      if (i.modifier() == m) {
        return true;
      }
    }
    return false;
  }

  public Span spanOf(Modifier m) {
    for (Item i : list) {
      if (i.modifier() == m) {
        return i.span();
      }
    }
    return span;
  }

  public boolean isEmpty() {
    return list.isEmpty() && annotations.isEmpty();
  }
}

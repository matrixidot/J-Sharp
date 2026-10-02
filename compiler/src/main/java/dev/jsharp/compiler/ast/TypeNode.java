package dev.jsharp.compiler.ast;

import dev.jsharp.compiler.source.Span;
import java.util.List;

/** Syntax of a type reference. */
public sealed interface TypeNode extends Node {

  /**
   * A possibly qualified, possibly generic name: {@code java.util.Map.Entry<K, V>}.
   *
   * @param segments dot-separated segments; each may carry type arguments
   */
  record Named(List<Segment> segments, Span span) implements TypeNode {
    public Named {
      segments = List.copyOf(segments);
    }

    /** Dotted name without type arguments. */
    public String qualifiedName() {
      StringBuilder sb = new StringBuilder();
      for (Segment s : segments) {
        if (!sb.isEmpty()) {
          sb.append('.');
        }
        sb.append(s.name());
      }
      return sb.toString();
    }

    public Segment last() {
      return segments.getLast();
    }
  }

  /**
   * One segment of a named type.
   *
   * @param diamond true for {@code <>} (inferred type arguments)
   */
  record Segment(String name, List<TypeNode> typeArgs, boolean diamond, Span span) {
    public Segment {
      typeArgs = List.copyOf(typeArgs);
    }
  }

  /** {@code int}, {@code boolean}, ..., and {@code void}. */
  record Primitive(Kind kind, Span span) implements TypeNode {
    /** Primitive kinds. */
    public enum Kind {
      BOOLEAN,
      BYTE,
      CHAR,
      SHORT,
      INT,
      LONG,
      FLOAT,
      DOUBLE,
      VOID;

      public String keyword() {
        return name().toLowerCase(java.util.Locale.ROOT);
      }
    }
  }

  /** {@code T[]}. */
  record Array(TypeNode element, Span span) implements TypeNode {}

  /** {@code T?}. */
  record Nullable(TypeNode inner, Span span) implements TypeNode {}

  /** {@code (int, String)} or {@code (int id, String name)}. */
  record Tuple(List<Element> elements, Span span) implements TypeNode {
    public Tuple {
      elements = List.copyOf(elements);
    }

    /** A tuple element with optional name. */
    public record Element(TypeNode type, String name, Span span) {}
  }

  /**
   * Use-site variance/wildcard: {@code ?} (bound null), {@code out T}, {@code in T}, {@code ?
   * extends T} is written {@code out T}.
   */
  record Wildcard(TypeParam.Variance variance, TypeNode bound, Span span) implements TypeNode {}
}

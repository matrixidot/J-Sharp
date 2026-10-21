package io.github.matrixidot.jsharp.compiler.symbols;

import io.github.matrixidot.jsharp.compiler.types.Type;
import java.util.List;

/**
 * A checked annotation on a declaration, as code generation writes it (D109).
 *
 * @param type the annotation type
 * @param visible true for {@code RUNTIME} retention (RuntimeVisibleAnnotations), false for {@code
 *     CLASS} (RuntimeInvisibleAnnotations); {@code SOURCE} annotations are not kept
 * @param elements the given elements, in source order (defaults are not repeated)
 */
public record AnnotationUse(ClassSymbol type, boolean visible, List<Element> elements) {
  public AnnotationUse {
    elements = List.copyOf(elements);
  }

  /** One {@code name = value} element. */
  public record Element(String name, Value value) {}

  /** An element value: what class files can hold. */
  public sealed interface Value {}

  /**
   * A constant: a boxed primitive or a String.
   *
   * @param type the element type (decides the encoding of, e.g., a short)
   */
  public record Constant(Object value, Type type) implements Value {}

  /** An enum constant. */
  public record EnumConstant(ClassSymbol enumType, String name) implements Value {}

  /** A class literal. */
  public record ClassValue(Type type) implements Value {}

  /** An array of values. */
  public record Array(List<Value> elements) implements Value {
    public Array {
      elements = List.copyOf(elements);
    }
  }
}

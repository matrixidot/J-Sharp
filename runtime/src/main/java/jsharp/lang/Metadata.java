package jsharp.lang;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class compiled by J#. Unannotated reference types in its signatures are non-null
 * (nullable ones carry {@code @Nullable}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Metadata {
  /** Compiler version that produced the class. */
  String version() default "";

  /**
   * True for the synthetic class holding a file's top-level functions and values; {@code import
   * pkg.*} brings their members (and extensions) into scope.
   */
  boolean module() default false;
}

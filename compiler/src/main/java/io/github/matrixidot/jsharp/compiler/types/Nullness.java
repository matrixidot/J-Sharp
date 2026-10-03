package io.github.matrixidot.jsharp.compiler.types;

/** Nullness of a reference type. Primitive types have no nullness. */
public enum Nullness {
  /** {@code T}: never null. */
  NON_NULL,
  /** {@code T?}: may be null. */
  NULLABLE,
  /**
   * A Java type without nullness information. Flexible: usable as either {@code T} or {@code T?};
   * flowing into a non-null J# location inserts a runtime null check.
   */
  PLATFORM;

  public boolean mayBeNull() {
    return this != NON_NULL;
  }
}

package io.github.matrixidot.jsharp.compiler.ast;

/** Declaration modifiers. Several are contextual keywords in source. */
public enum Modifier {
  PUBLIC,
  PROTECTED,
  PRIVATE,
  INTERNAL,
  STATIC,
  FINAL,
  ABSTRACT,
  SEALED,
  BASE,
  OVERRIDE,
  ASYNC,
  REQUIRED,
  DEFAULT,
  SYNCHRONIZED,
  VOLATILE,
  TRANSIENT,
  NATIVE,
  /** Marks a user-defined operator method (implicit; written as the `operator` keyword). */
  OPERATOR,
  /** A local variable lambdas and local functions may update, stored atomically (D084). */
  ATOMIC;

  public String keyword() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}

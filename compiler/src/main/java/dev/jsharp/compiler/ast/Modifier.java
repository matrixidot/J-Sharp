package dev.jsharp.compiler.ast;

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
  OPEN,
  OVERRIDE,
  ASYNC,
  REQUIRED,
  DEFAULT,
  SYNCHRONIZED,
  VOLATILE,
  TRANSIENT,
  NATIVE;

  public String keyword() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}

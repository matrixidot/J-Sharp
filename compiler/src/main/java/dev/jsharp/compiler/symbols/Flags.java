package dev.jsharp.compiler.symbols;

/**
 * Symbol flags: JVM access flags in the low 16 bits (same values as the class file format) and
 * J#-specific flags above.
 */
public final class Flags {
  private Flags() {}

  public static final long PUBLIC = 0x0001;
  public static final long PRIVATE = 0x0002;
  public static final long PROTECTED = 0x0004;
  public static final long STATIC = 0x0008;
  public static final long FINAL = 0x0010;
  public static final long SYNCHRONIZED = 0x0020;
  public static final long VOLATILE = 0x0040;
  public static final long BRIDGE = 0x0040;
  public static final long TRANSIENT = 0x0080;
  public static final long VARARGS = 0x0080;
  public static final long NATIVE = 0x0100;
  public static final long INTERFACE = 0x0200;
  public static final long ABSTRACT = 0x0400;
  public static final long STRICT = 0x0800;
  public static final long SYNTHETIC = 0x1000;
  public static final long ANNOTATION = 0x2000;
  public static final long ENUM = 0x4000;
  public static final long MANDATED = 0x8000;

  /** Mask of flags that are written to class files. */
  public static final long JVM_MASK = 0xFFFF;

  public static final long SEALED = 1L << 16;
  public static final long OPEN = 1L << 17;
  public static final long OVERRIDE = 1L << 18;
  public static final long ASYNC = 1L << 19;
  public static final long REQUIRED = 1L << 20;
  public static final long DEFAULT = 1L << 21;
  public static final long RECORD = 1L << 22;
  public static final long EXTENSION = 1L << 23;
  public static final long GETTER = 1L << 24;
  public static final long SETTER = 1L << 25;
  public static final long INIT_ONLY = 1L << 26;

  /** Synthetic module class holding a file's top-level functions/statements. */
  public static final long MODULE = 1L << 27;

  /** Declared in J# source in the current compilation. */
  public static final long SOURCE = 1L << 28;

  public static final long COMPACT_CTOR = 1L << 29;
  public static final long DEPRECATED = 1L << 30;
  public static final long NON_SEALED = 1L << 31;

  /** Compiled by J# (class file carries J# metadata). */
  public static final long JSHARP = 1L << 32;

  /** Field that backs a property. */
  public static final long BACKING_FIELD = 1L << 33;

  /** Local/anonymous class. */
  public static final long LOCAL = 1L << 34;

  public static final long ANONYMOUS = 1L << 35;

  /** Enum constant field. */
  public static final long ENUM_CONSTANT = 1L << 36;

  /** Default constructor synthesized by the compiler. */
  public static final long GENERATED = 1L << 37;

  /** Functional interface (exactly one abstract method). */
  public static final long FUNCTIONAL = 1L << 38;

  /** Method is the implicit entry point built from top-level statements. */
  public static final long ENTRY_POINT = 1L << 39;

  /** Method or property declared with an omitted return type (inferred). */
  public static final long INFERRED_TYPE = 1L << 40;

  public static boolean is(long flags, long flag) {
    return (flags & flag) != 0;
  }

  public static int jvm(long flags) {
    return (int) (flags & JVM_MASK);
  }

  /** {@code public}/{@code protected}/{@code private}/package-private rendering. */
  public static String access(long flags) {
    if (is(flags, PUBLIC)) {
      return "public";
    }
    if (is(flags, PROTECTED)) {
      return "protected";
    }
    if (is(flags, PRIVATE)) {
      return "private";
    }
    return "internal";
  }
}

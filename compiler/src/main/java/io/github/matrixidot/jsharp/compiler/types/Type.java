package io.github.matrixidot.jsharp.compiler.types;

import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import java.util.List;

/**
 * Semantic types. Immutable; class types compare their {@link ClassSymbol} by identity.
 *
 * <p>Generic types are erased at the JVM level ({@link #erasure()}); tuple types erase to the
 * runtime {@code jsharp.core.TupleN} records.
 */
public sealed interface Type {

  /** Source-like rendering used in diagnostics. */
  String display();

  default Nullness nullness() {
    return Nullness.NON_NULL;
  }

  /** Returns this type with the given nullness (identity for primitives and special types). */
  default Type withNullness(Nullness n) {
    return this;
  }

  default boolean isPrimitive() {
    return this instanceof PrimType p && p != PrimType.VOID;
  }

  default boolean isReference() {
    return this instanceof ClassType
        || this instanceof ArrayType
        || this instanceof TypeVar
        || this instanceof TupleType
        || this instanceof NullType;
  }

  default boolean isError() {
    return this instanceof ErrorType;
  }

  default boolean isNullable() {
    return isReference() && nullness() == Nullness.NULLABLE || this instanceof NullType;
  }

  /** The JVM-level type (type variables replaced by their erased bound). */
  Type erasure();

  /** Primitive types (including {@code void}). */
  enum PrimType implements Type {
    BOOLEAN("boolean", "Z"),
    BYTE("byte", "B"),
    SHORT("short", "S"),
    CHAR("char", "C"),
    INT("int", "I"),
    LONG("long", "J"),
    FLOAT("float", "F"),
    DOUBLE("double", "D"),
    VOID("void", "V");

    private final String keyword;
    private final String descriptor;

    PrimType(String keyword, String descriptor) {
      this.keyword = keyword;
      this.descriptor = descriptor;
    }

    @Override
    public String display() {
      return keyword;
    }

    public String descriptor() {
      return descriptor;
    }

    @Override
    public Type erasure() {
      return this;
    }

    public boolean isNumeric() {
      return this != BOOLEAN && this != VOID;
    }

    public boolean isIntegral() {
      return this == BYTE || this == SHORT || this == CHAR || this == INT || this == LONG;
    }

    /** JVM slot size (1 or 2). */
    public int slots() {
      return this == LONG || this == DOUBLE ? 2 : this == VOID ? 0 : 1;
    }

    /** Numeric rank for binary numeric promotion. */
    public int rank() {
      return switch (this) {
        case BYTE -> 1;
        case SHORT, CHAR -> 2;
        case INT -> 3;
        case LONG -> 4;
        case FLOAT -> 5;
        case DOUBLE -> 6;
        default -> 0;
      };
    }

    /** The wrapper class's binary name, e.g. {@code java/lang/Integer}. */
    public String boxBinaryName() {
      return switch (this) {
        case BOOLEAN -> "java/lang/Boolean";
        case BYTE -> "java/lang/Byte";
        case SHORT -> "java/lang/Short";
        case CHAR -> "java/lang/Character";
        case INT -> "java/lang/Integer";
        case LONG -> "java/lang/Long";
        case FLOAT -> "java/lang/Float";
        case DOUBLE -> "java/lang/Double";
        case VOID -> "java/lang/Void";
      };
    }
  }

  /**
   * A class or interface type with type arguments ({@code List<String>}). An empty argument list
   * for a generic class denotes a raw type (only produced by Java signatures).
   */
  record ClassType(ClassSymbol sym, List<Type> args, Nullness nullness) implements Type {
    public ClassType {
      args = List.copyOf(args);
    }

    public static ClassType of(ClassSymbol sym) {
      return new ClassType(sym, List.of(), Nullness.NON_NULL);
    }

    public boolean isRaw() {
      return args.isEmpty() && !sym.typeParams().isEmpty();
    }

    @Override
    public String display() {
      StringBuilder sb = new StringBuilder(sym.displayName());
      if (!args.isEmpty()) {
        sb.append('<');
        for (int i = 0; i < args.size(); i++) {
          if (i > 0) {
            sb.append(", ");
          }
          sb.append(args.get(i).display());
        }
        sb.append('>');
      }
      return sb.append(
              nullness == Nullness.NULLABLE ? "?" : nullness == Nullness.PLATFORM ? "!" : "")
          .toString();
    }

    @Override
    public Type withNullness(Nullness n) {
      return n == nullness ? this : new ClassType(sym, args, n);
    }

    @Override
    public Type erasure() {
      return args.isEmpty() && nullness == Nullness.NON_NULL
          ? this
          : new ClassType(sym, List.of(), Nullness.NON_NULL);
    }

    @Override
    public String toString() {
      return display();
    }
  }

  /** {@code T[]}. */
  record ArrayType(Type elem, Nullness nullness) implements Type {
    public static ArrayType of(Type elem) {
      return new ArrayType(elem, Nullness.NON_NULL);
    }

    @Override
    public String display() {
      return elem.display() + "[]" + (nullness == Nullness.NULLABLE ? "?" : "");
    }

    @Override
    public Type withNullness(Nullness n) {
      return n == nullness ? this : new ArrayType(elem, n);
    }

    @Override
    public Type erasure() {
      return new ArrayType(elem.erasure().withNullness(Nullness.NON_NULL), Nullness.NON_NULL);
    }

    @Override
    public String toString() {
      return display();
    }
  }

  /** A type variable use, {@code T} or {@code T?}. */
  record TypeVar(TypeVarSymbol sym, Nullness nullness) implements Type {
    @Override
    public String display() {
      return sym.name() + (nullness == Nullness.NULLABLE ? "?" : "");
    }

    @Override
    public Type withNullness(Nullness n) {
      return n == nullness ? this : new TypeVar(sym, n);
    }

    @Override
    public Type erasure() {
      return sym.erasedBound();
    }

    @Override
    public String toString() {
      return display();
    }
  }

  /** Use-site wildcard ({@code ?}, {@code out T}, {@code in T}); only as a type argument. */
  record WildcardType(Kind kind, Type bound) implements Type {
    /** Wildcard kinds. */
    public enum Kind {
      UNBOUNDED,
      EXTENDS,
      SUPER
    }

    @Override
    public String display() {
      return switch (kind) {
        case UNBOUNDED -> "?";
        case EXTENDS -> "out " + bound.display();
        case SUPER -> "in " + bound.display();
      };
    }

    @Override
    public Type erasure() {
      return kind == Kind.EXTENDS
          ? bound.erasure()
          : this; // wildcards only occur as type arguments
    }
  }

  /** A tuple {@code (int, String)}; erases to {@code jsharp.core.TupleN}. */
  record TupleType(List<Type> elems, List<String> names, ClassSymbol erasedSym, Nullness nullness)
      implements Type {
    public TupleType {
      elems = List.copyOf(elems);
      names = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(names));
    }

    @Override
    public String display() {
      StringBuilder sb = new StringBuilder("(");
      for (int i = 0; i < elems.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(elems.get(i).display());
        if (names.get(i) != null) {
          sb.append(' ').append(names.get(i));
        }
      }
      return sb.append(nullness == Nullness.NULLABLE ? ")?" : ")").toString();
    }

    @Override
    public Type withNullness(Nullness n) {
      return n == nullness ? this : new TupleType(elems, names, erasedSym, n);
    }

    @Override
    public Type erasure() {
      return ClassType.of(erasedSym);
    }
  }

  /** The type of the {@code null} literal. */
  record NullType() implements Type {
    public static final NullType INSTANCE = new NullType();

    @Override
    public String display() {
      return "null";
    }

    @Override
    public Nullness nullness() {
      return Nullness.NULLABLE;
    }

    @Override
    public Type erasure() {
      return this;
    }
  }

  /** The bottom type: type of {@code throw} expressions and calls that never return. */
  record NeverType() implements Type {
    public static final NeverType INSTANCE = new NeverType();

    @Override
    public String display() {
      return "never";
    }

    @Override
    public Type erasure() {
      return this;
    }
  }

  /** Poison type after an error; compatible with everything to avoid cascades. */
  record ErrorType() implements Type {
    public static final ErrorType INSTANCE = new ErrorType();

    @Override
    public String display() {
      return "<error>";
    }

    @Override
    public Type erasure() {
      return this;
    }
  }
}

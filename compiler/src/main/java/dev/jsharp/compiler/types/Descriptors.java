package dev.jsharp.compiler.types;

import java.util.List;

/** JVM descriptors of (erased) types. */
public final class Descriptors {
  private Descriptors() {}

  public static String of(Type t) {
    return switch (t) {
      case Type.PrimType p -> p.descriptor();
      case Type.ClassType c -> "L" + c.sym().binaryName() + ";";
      case Type.ArrayType a -> "[" + of(a.elem());
      case Type.TypeVar v -> of(v.erasure());
      case Type.TupleType tt -> of(tt.erasure());
      case Type.WildcardType w ->
          w.kind() == Type.WildcardType.Kind.EXTENDS ? of(w.bound()) : "Ljava/lang/Object;";
      case Type.NullType n -> "Ljava/lang/Object;";
      case Type.NeverType n -> "Ljava/lang/Object;";
      case Type.ErrorType e -> "Ljava/lang/Object;";
    };
  }

  public static String method(List<Type> params, Type ret) {
    StringBuilder sb = new StringBuilder("(");
    for (Type p : params) {
      sb.append(of(p));
    }
    return sb.append(')').append(ret == null ? "V" : of(ret)).toString();
  }

  /** Erased parameter list, used to detect clashing overloads. */
  public static String params(List<Type> params) {
    StringBuilder sb = new StringBuilder("(");
    for (Type p : params) {
      sb.append(of(p));
    }
    return sb.append(')').toString();
  }
}

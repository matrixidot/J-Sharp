package io.github.matrixidot.jsharp.compiler.codegen;

import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;

/** Symbolic descriptors ({@link ClassDesc}, {@link MethodTypeDesc}) and generic signatures. */
final class Descs {
  private Descs() {}

  static ClassDesc of(ClassSymbol c) {
    return ClassDesc.ofInternalName(c.binaryName());
  }

  /** Descriptor of the erasure of {@code t}. */
  static ClassDesc of(Type t) {
    return switch (t) {
      case PrimType p ->
          switch (p) {
            case BOOLEAN -> ConstantDescs.CD_boolean;
            case BYTE -> ConstantDescs.CD_byte;
            case SHORT -> ConstantDescs.CD_short;
            case CHAR -> ConstantDescs.CD_char;
            case INT -> ConstantDescs.CD_int;
            case LONG -> ConstantDescs.CD_long;
            case FLOAT -> ConstantDescs.CD_float;
            case DOUBLE -> ConstantDescs.CD_double;
            case VOID -> ConstantDescs.CD_void;
          };
      case Type.ClassType c -> of(c.sym());
      case Type.ArrayType a -> of(a.elem()).arrayType();
      case Type.TypeVar v -> of(v.sym().erasedBound());
      case Type.TupleType tt -> of(tt.erasedSym());
      case Type.WildcardType w ->
          w.kind() == Type.WildcardType.Kind.EXTENDS ? of(w.bound()) : ConstantDescs.CD_Object;
      default -> ConstantDescs.CD_Object;
    };
  }

  static TypeKind kind(Type t) {
    if (t instanceof PrimType p) {
      return switch (p) {
        case BOOLEAN -> TypeKind.BOOLEAN;
        case BYTE -> TypeKind.BYTE;
        case SHORT -> TypeKind.SHORT;
        case CHAR -> TypeKind.CHAR;
        case INT -> TypeKind.INT;
        case LONG -> TypeKind.LONG;
        case FLOAT -> TypeKind.FLOAT;
        case DOUBLE -> TypeKind.DOUBLE;
        case VOID -> TypeKind.VOID;
      };
    }
    if (t instanceof Type.NeverType) {
      return TypeKind.REFERENCE;
    }
    return TypeKind.REFERENCE;
  }

  /** The JVM descriptor of a method (declared, erased types). */
  static MethodTypeDesc of(MethodSymbol m) {
    List<ClassDesc> ps = new ArrayList<>();
    for (MethodSymbol.Param p : m.params()) {
      ps.add(of(p.type()));
    }
    Type ret = m.isConstructor() || m.name().equals("<clinit>") ? PrimType.VOID : m.returnType();
    if (m.jvmReturnType() != null) {
      ret = m.jvmReturnType(); // @NoReturn methods are typed `never` but keep their JVM return type
    }
    return MethodTypeDesc.of(ret == null ? ConstantDescs.CD_void : of(ret), ps);
  }

  // ------------------------------------------------------------------ generic signatures

  static String signature(Type t) {
    StringBuilder sb = new StringBuilder();
    appendSig(sb, t);
    return sb.toString();
  }

  private static void appendSig(StringBuilder sb, Type t) {
    switch (t) {
      case PrimType p -> sb.append(p.descriptor());
      case Type.ClassType c -> {
        sb.append('L').append(c.sym().binaryName());
        if (!c.args().isEmpty()) {
          sb.append('<');
          for (Type a : c.args()) {
            appendSig(sb, a);
          }
          sb.append('>');
        }
        sb.append(';');
      }
      case Type.ArrayType a -> {
        sb.append('[');
        appendSig(sb, a.elem());
      }
      case Type.TypeVar v -> {
        if (v.sym().isCaptured()) {
          appendSig(sb, v.sym().bounds().getFirst());
        } else {
          sb.append('T').append(v.sym().name()).append(';');
        }
      }
      case Type.WildcardType w -> {
        switch (w.kind()) {
          case UNBOUNDED -> sb.append('*');
          case EXTENDS -> {
            sb.append('+');
            appendSig(sb, w.bound());
          }
          case SUPER -> {
            sb.append('-');
            appendSig(sb, w.bound());
          }
        }
      }
      case Type.TupleType tt -> {
        sb.append('L').append(tt.erasedSym().binaryName()).append('<');
        for (Type e : tt.elems()) {
          if (e instanceof PrimType p) {
            sb.append('L').append(p.boxBinaryName()).append(';');
          } else {
            appendSig(sb, e);
          }
        }
        sb.append(">;");
      }
      default -> sb.append("Ljava/lang/Object;");
    }
  }

  static void appendTypeParams(StringBuilder sb, List<TypeVarSymbol> tps) {
    if (tps.isEmpty()) {
      return;
    }
    sb.append('<');
    for (TypeVarSymbol tv : tps) {
      sb.append(tv.name());
      List<Type> bounds = tv.bounds();
      boolean firstIsInterface =
          !bounds.isEmpty()
              && bounds.getFirst() instanceof Type.ClassType ct
              && ct.sym().isInterface();
      if (firstIsInterface || bounds.isEmpty()) {
        sb.append(':');
      }
      for (Type b : bounds) {
        sb.append(':');
        appendSig(sb, b);
      }
    }
    sb.append('>');
  }

  /** True if a type needs a Signature attribute (mentions type variables or arguments). */
  static boolean isGeneric(Type t) {
    return switch (t) {
      case Type.ClassType c -> !c.args().isEmpty();
      case Type.ArrayType a -> isGeneric(a.elem());
      case Type.TypeVar v -> true;
      case Type.TupleType tt -> true;
      default -> false;
    };
  }

  static String methodSignature(MethodSymbol m) {
    StringBuilder sb = new StringBuilder();
    appendTypeParams(sb, m.typeParams());
    sb.append('(');
    for (MethodSymbol.Param p : m.params()) {
      appendSig(sb, p.type());
    }
    sb.append(')');
    Type ret = m.isConstructor() ? PrimType.VOID : m.returnType();
    appendSig(sb, ret == null ? PrimType.VOID : ret);
    return sb.toString();
  }

  static boolean methodNeedsSignature(MethodSymbol m) {
    if (!m.typeParams().isEmpty()) {
      return true;
    }
    for (MethodSymbol.Param p : m.params()) {
      if (p.type() != null && isGeneric(p.type())) {
        return true;
      }
    }
    return !m.isConstructor() && m.returnType() != null && isGeneric(m.returnType());
  }
}

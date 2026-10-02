package dev.jsharp.compiler.bound;

import dev.jsharp.compiler.source.Span;
import dev.jsharp.compiler.symbols.ClassSymbol;
import dev.jsharp.compiler.symbols.FieldSymbol;
import dev.jsharp.compiler.symbols.MethodSymbol;
import dev.jsharp.compiler.symbols.Symbol;
import dev.jsharp.compiler.symbols.VarSymbol;
import dev.jsharp.compiler.types.Type;
import dev.jsharp.compiler.types.Type.ArrayType;
import dev.jsharp.compiler.types.Type.ClassType;
import java.util.List;

/**
 * Typed expressions produced by the checker. All implicit conversions are explicit ({@link Conv});
 * overloads, properties and extension methods are resolved to concrete symbols; varargs are packed
 * and default arguments filled in.
 */
public sealed interface BExpr {
  Type type();

  Span span();

  /** A compile-time constant (boxed value; {@code null} for the null literal). */
  record Const(Object value, Type type, Span span) implements BExpr {}

  record Local(VarSymbol var, Span span) implements BExpr {
    @Override
    public Type type() {
      return var.type();
    }
  }

  /** {@code this} of the innermost class. */
  record This(Type type, Span span) implements BExpr {}

  /** The instance of an enclosing class seen from a local/anonymous class. */
  record OuterThis(ClassSymbol outer, Type type, Span span) implements BExpr {}

  /** Field read; {@code receiver} is null for static fields. */
  record Field(BExpr receiver, FieldSymbol field, Type type, Span span) implements BExpr {}

  /** How a call is dispatched. */
  enum CallKind {
    STATIC,
    VIRTUAL,
    INTERFACE,
    /** invokespecial: super calls and constructor chaining. */
    SPECIAL
  }

  /**
   * Method call. Arguments match the method's parameters one-to-one (varargs already packed,
   * defaults filled in). {@code type} is the instantiated return type.
   */
  record Call(BExpr receiver, MethodSymbol method, List<BExpr> args, CallKind kind, Type type, Span span)
      implements BExpr {}

  /** Object creation. */
  record New(ClassType type, MethodSymbol ctor, List<BExpr> args, Span span) implements BExpr {}

  /** An assignment performed on a freshly created object (object initializer / with). */
  record MemberInit(Symbol member, BExpr value) {}

  /** {@code new T(...) { a = 1, b = 2 }}: evaluates {@code creation}, then the member inits. */
  record ObjectInit(BExpr creation, List<MemberInit> inits, Type type, Span span) implements BExpr {}

  /** Array creation with dimension sizes or with elements (exactly one is non-empty). */
  record NewArray(ArrayType type, List<BExpr> dims, List<BExpr> elems, Span span) implements BExpr {}

  record ArrayElem(BExpr array, BExpr index, Type type, Span span) implements BExpr {}

  record ArrayLength(BExpr array, Type type, Span span) implements BExpr {}

  /** Unary operators on already-promoted operands. */
  enum UnOp {
    NEG,
    BIT_NOT,
    NOT
  }

  record Unary(UnOp op, BExpr operand, Type type, Span span) implements BExpr {}

  /** Binary operators; operands are converted to the operation type beforehand. */
  enum BinOp {
    ADD,
    SUB,
    MUL,
    DIV,
    REM,
    SHL,
    SHR,
    USHR,
    AND,
    OR,
    XOR,
    LT,
    GT,
    LE,
    GE,
    EQ,
    NE,
    /** Reference identity ({@code ===}), also used for enums. */
    REF_EQ,
    REF_NE,
    COND_AND,
    COND_OR;

    public boolean isComparison() {
      return this == LT || this == GT || this == LE || this == GE || this == EQ || this == NE || this == REF_EQ || this == REF_NE;
    }
  }

  /**
   * Binary operation.
   *
   * @param checked use overflow-checked arithmetic ({@code Math.addExact} family)
   */
  record Binary(BinOp op, BExpr left, BExpr right, Type type, boolean checked, Span span) implements BExpr {}

  /** Null-safe value equality {@code Objects.equals(left, right)} (J# {@code ==} on references). */
  record ValueEquals(BExpr left, BExpr right, boolean negate, Type type, Span span) implements BExpr {}

  record Assign(BLValue target, BExpr value, Type type, Span span) implements BExpr {}

  /**
   * {@code target op= value}: computed in {@code opType}, converted back to the target type.
   *
   * @param opType the operation type (after promotion)
   */
  record CompoundAssign(BLValue target, BinOp op, BExpr value, Type opType, Type type, boolean checked, Span span)
      implements BExpr {}

  /** {@code ++x}, {@code x--}, ... */
  record IncDec(BLValue target, boolean increment, boolean prefix, Type type, boolean checked, Span span)
      implements BExpr {}

  /** Kinds of conversion. */
  enum ConvKind {
    /** Primitive widening or narrowing (i2l, l2i, d2i, ...). */
    PRIMITIVE,
    BOX,
    UNBOX,
    /** Reference cast (checkcast); explicit casts and smart casts. */
    CHECKCAST,
    /** Platform value into a non-null location: fail fast with a NullPointerException. */
    NULL_CHECK,
    /** {@code x!}: assert non-null. */
    NON_NULL_ASSERT,
    /** Static retyping without runtime effect (e.g. reference widening). */
    RETYPE
  }

  record Conv(BExpr expr, ConvKind kind, Type type, Span span) implements BExpr {}

  record InstanceOf(BExpr expr, Type target, Type type, Span span) implements BExpr {}

  record Conditional(BExpr cond, BExpr then, BExpr otherwise, Type type, Span span) implements BExpr {}

  /** String concatenation (via {@code StringConcatFactory}); parts are any types. */
  record Concat(List<BExpr> parts, Type type, Span span) implements BExpr {}

  /**
   * A lambda converted to functional interface {@code type}.
   *
   * @param params lambda parameters (types = the function type's parameter types)
   * @param body the body (expression lambdas are wrapped in a return or expression statement)
   * @param captures captured locals (effectively final), in first-use order
   * @param capturesThis whether {@code this} is used
   */
  record Lambda(
      ClassType type,
      MethodSymbol sam,
      List<VarSymbol> params,
      BStmt body,
      Type returnType,
      List<VarSymbol> captures,
      boolean capturesThis,
      Span span)
      implements BExpr {}

  /** Method reference kinds. */
  enum RefKind {
    STATIC,
    BOUND,
    UNBOUND,
    CONSTRUCTOR,
    ARRAY_CONSTRUCTOR
  }

  /**
   * {@code X::m} converted to functional interface {@code type}.
   *
   * @param receiver evaluated receiver for BOUND references, else null
   */
  record MethodRef(ClassType type, MethodSymbol sam, MethodSymbol target, RefKind kind, BExpr receiver, Type refType, Span span)
      implements BExpr {}

  /** {@code typeof(T)}: a class literal. */
  record ClassLit(Type target, Type type, Span span) implements BExpr {}

  /** Binds {@code var} to {@code init} while evaluating {@code body}. */
  record Let(VarSymbol var, BExpr init, BExpr body, Span span) implements BExpr {
    @Override
    public Type type() {
      return body.type();
    }
  }

  /** {@code throw} in expression position (type never). */
  record Throw(BExpr exception, Type type, Span span) implements BExpr {}

  /**
   * {@code receiver?.rest}: evaluates the receiver once into {@code tmp}; yields null if it is null,
   * else {@code whenPresent} (which refers to {@code tmp}).
   */
  record SafeAccess(BExpr receiver, VarSymbol tmp, BExpr whenPresent, Type type, Span span) implements BExpr {}

  /** {@code left ?? right}. */
  record Coalesce(BExpr left, BExpr right, Type type, Span span) implements BExpr {}

  /** Evaluates statements, then yields {@code value} (lowering helper and switch expressions). */
  record Block(List<BStmt> stmts, BExpr value, Span span) implements BExpr {
    @Override
    public Type type() {
      return value.type();
    }
  }

  /** {@code await task}: joins a {@code Task}/{@code Future} on the current (virtual) thread. */
  record Await(BExpr task, MethodSymbol join, Type type, Span span) implements BExpr {}

  /** Switch expression over a decision structure (see {@link BSwitch}). */
  record Switch(BSwitch sw, Type type, Span span) implements BExpr {}

  /** {@code expr is pattern}: tests the pattern and binds its variables. */
  record IsPattern(BExpr expr, BPattern pattern, Type type, Span span) implements BExpr {}

  /** Placeholder after an error. */
  record Error(Type type, Span span) implements BExpr {}
}

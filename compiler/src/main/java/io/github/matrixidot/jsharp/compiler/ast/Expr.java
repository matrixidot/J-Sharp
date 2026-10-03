package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;
import java.util.List;

/** Expression syntax. */
public sealed interface Expr extends Node {

  /** Literal kinds. */
  enum LiteralKind {
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    CHAR,
    STRING,
    BOOLEAN,
    NULL
  }

  /**
   * A literal. Integer values are stored as {@code Long} magnitudes; range checking happens in the
   * type checker (which knows about a preceding unary minus).
   *
   * @param text raw source text
   */
  record Literal(LiteralKind kind, Object value, String text, Span span) implements Expr {}

  /** {@code $"Hello {name}, {x:F2}"}. */
  record Interpolated(List<Part> parts, Span span) implements Expr {
    public Interpolated {
      parts = List.copyOf(parts);
    }
  }

  /** A piece of an interpolated string. */
  sealed interface Part {}

  /** Literal text inside an interpolated string. */
  record TextPart(String text) implements Part {}

  /** {@code {expr[:format]}}. */
  record HolePart(Expr expr, String format, Span span) implements Part {}

  /** A simple name, optionally with explicit type arguments ({@code foo<String>}). */
  record Name(String name, List<TypeNode> typeArgs, Span span) implements Expr {
    public Name {
      typeArgs = List.copyOf(typeArgs);
    }
  }

  /** {@code target.name} or {@code target?.name}. */
  record Member(
      Expr target, String name, Span nameSpan, List<TypeNode> typeArgs, boolean nullSafe, Span span)
      implements Expr {
    public Member {
      typeArgs = List.copyOf(typeArgs);
    }
  }

  /** {@code callee(args)}. */
  record Call(Expr callee, List<Arg> args, Span span) implements Expr {
    public Call {
      args = List.copyOf(args);
    }
  }

  /** {@code target[index]} or {@code target?[index]}. */
  record Index(Expr target, Expr index, boolean nullSafe, Span span) implements Expr {}

  /**
   * Object creation.
   *
   * @param type created type, or null for target-typed {@code new(...)}
   * @param args constructor arguments, or null when written without parentheses
   * @param init object initializer entries, or null
   * @param anonBody anonymous class members, or null
   */
  record New(TypeNode type, List<Arg> args, List<FieldInit> init, List<Decl> anonBody, Span span)
      implements Expr {}

  /**
   * Array creation {@code new int[n][]} or {@code new int[] {1, 2}}.
   *
   * @param dims dimension size expressions
   * @param extraDims count of trailing {@code []} without sizes
   * @param init initializer, or null
   */
  record NewArray(TypeNode elementType, List<Expr> dims, int extraDims, ArrayInit init, Span span)
      implements Expr {
    public NewArray {
      dims = List.copyOf(dims);
    }
  }

  /**
   * {@code [a, b, ..xs]}: a target-typed collection literal (list, set, array or a concrete
   * collection class); elements may be {@link Spread}s.
   */
  record CollectionLiteral(List<Expr> elements, Span span) implements Expr {
    public CollectionLiteral {
      elements = List.copyOf(elements);
    }
  }

  /** {@code ..xs} inside a collection literal: all elements of an iterable or array. */
  record Spread(Expr expr, Span span) implements Expr {}

  /** {@code {k1: v1, k2: v2}}: a map literal (insertion-ordered, immutable unless target-typed). */
  record MapLiteral(List<MapEntry> entries, Span span) implements Expr {
    public MapLiteral {
      entries = List.copyOf(entries);
    }
  }

  /** One {@code key: value} entry of a map literal. */
  record MapEntry(Expr key, Expr value, Span span) {}

  /** {@code {a, b, c}} in an array-initializer position. */
  record ArrayInit(List<Expr> elements, Span span) implements Expr {
    public ArrayInit {
      elements = List.copyOf(elements);
    }
  }

  /** Unary operators. */
  enum UnaryOp {
    NEG("-"),
    PLUS("+"),
    NOT("!"),
    BIT_NOT("~"),
    PRE_INC("++"),
    PRE_DEC("--"),
    POST_INC("++"),
    POST_DEC("--"),
    /** Postfix {@code x!}: assert non-null. */
    NON_NULL("!"),
    /** Prefix {@code ^i}: index from end. */
    FROM_END("^");

    private final String symbol;

    UnaryOp(String symbol) {
      this.symbol = symbol;
    }

    public String symbol() {
      return symbol;
    }

    public boolean isPostfix() {
      return this == POST_INC || this == POST_DEC || this == NON_NULL;
    }
  }

  record Unary(UnaryOp op, Expr operand, Span span) implements Expr {}

  /** Binary operators. */
  enum BinaryOp {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/"),
    REM("%"),
    SHL("<<"),
    SHR(">>"),
    USHR(">>>"),
    BIT_AND("&"),
    BIT_OR("|"),
    BIT_XOR("^"),
    AND("&&"),
    OR("||"),
    EQ("=="),
    NE("!="),
    REF_EQ("==="),
    REF_NE("!=="),
    LT("<"),
    GT(">"),
    LE("<="),
    GE(">="),
    COALESCE("??");

    private final String symbol;

    BinaryOp(String symbol) {
      this.symbol = symbol;
    }

    public String symbol() {
      return symbol;
    }
  }

  record Binary(BinaryOp op, Expr left, Expr right, Span span) implements Expr {}

  /** {@code from..to}; either side may be null. */
  record Range(Expr from, Expr to, Span span) implements Expr {}

  /** Assignment operators; {@code op()} gives the underlying binary operator. */
  enum AssignOp {
    ASSIGN("=", null),
    ADD("+=", BinaryOp.ADD),
    SUB("-=", BinaryOp.SUB),
    MUL("*=", BinaryOp.MUL),
    DIV("/=", BinaryOp.DIV),
    REM("%=", BinaryOp.REM),
    SHL("<<=", BinaryOp.SHL),
    SHR(">>=", BinaryOp.SHR),
    USHR(">>>=", BinaryOp.USHR),
    AND("&=", BinaryOp.BIT_AND),
    OR("|=", BinaryOp.BIT_OR),
    XOR("^=", BinaryOp.BIT_XOR),
    COALESCE("??=", BinaryOp.COALESCE);

    private final String symbol;
    private final BinaryOp op;

    AssignOp(String symbol, BinaryOp op) {
      this.symbol = symbol;
      this.op = op;
    }

    public String symbol() {
      return symbol;
    }

    public BinaryOp op() {
      return op;
    }
  }

  record Assign(AssignOp op, Expr target, Expr value, Span span) implements Expr {}

  /**
   * {@code c ? a : b} or {@code if (c) a else b}.
   *
   * @param ifSyntax true when written as an if-expression
   */
  record Conditional(Expr cond, Expr then, Expr otherwise, boolean ifSyntax, Span span)
      implements Expr {}

  /** {@code selector switch { arms }} (or {@code switch (selector) { arms }}). */
  record Switch(Expr selector, List<SwitchArm> arms, Span span) implements Expr {
    public Switch {
      arms = List.copyOf(arms);
    }
  }

  /** {@code expr is pattern}. */
  record Is(Expr expr, Pattern pattern, Span span) implements Expr {}

  /** {@code expr as Type}. */
  record As(Expr expr, TypeNode type, Span span) implements Expr {}

  /** {@code (Type) expr}. */
  record Cast(TypeNode type, Expr expr, Span span) implements Expr {}

  /** {@code x => e}, {@code (int a, b) => { ... }}, {@code async () => ...}. */
  record Lambda(List<Param> params, boolean isAsync, Body body, Span span) implements Expr {
    public Lambda {
      params = List.copyOf(params);
    }
  }

  /**
   * {@code target::name}; exactly one of {@code target}/{@code typeTarget} is non-null. {@code
   * name} is {@code "new"} for constructor references.
   */
  record MethodRef(Expr target, TypeNode typeTarget, String name, Span nameSpan, Span span)
      implements Expr {}

  /** {@code this} or {@code Outer.this}. */
  record This(String qualifier, Span span) implements Expr {}

  /**
   * {@code super} or {@code I.super} (only valid as a member access target); the qualifier names a
   * direct superinterface whose default method is called.
   */
  record Super(Expr qualifier, Span span) implements Expr {}

  /** {@code typeof(T)}. */
  record TypeOf(TypeNode type, Span span) implements Expr {}

  /** {@code nameof(x)}. */
  record NameOf(Expr expr, Span span) implements Expr {}

  /** {@code (a, b)} or {@code (id: 1, name: "x")}. */
  record Tuple(List<Arg> elements, Span span) implements Expr {
    public Tuple {
      elements = List.copyOf(elements);
    }
  }

  /** {@code target with { x = 1 }}. */
  record With(Expr target, List<FieldInit> inits, Span span) implements Expr {
    public With {
      inits = List.copyOf(inits);
    }
  }

  /** {@code throw expr} in expression position. */
  record Throw(Expr expr, Span span) implements Expr {}

  /** {@code await expr}. */
  record Await(Expr expr, Span span) implements Expr {}

  /** {@code checked(expr)}. */
  record Checked(Expr expr, Span span) implements Expr {}

  /** {@code (expr)}. */
  record Paren(Expr expr, Span span) implements Expr {}

  /** Placeholder produced by error recovery. */
  record Error(Span span) implements Expr {}
}

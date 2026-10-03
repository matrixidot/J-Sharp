package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.bound.BStmt;
import io.github.matrixidot.jsharp.compiler.resolve.TypeScope;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.PropertySymbol;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The checking environment of one body (method, initializer, lambda, accessor). Mutable: the
 * statement checker updates {@link #flow} and {@link #scope} as it goes; nested bodies get a fresh
 * {@code Env} via {@link #nested()}.
 */
final class Env {
  /** A break/continue target. */
  static final class Jump {
    final String name;
    final BStmt.Label label;
    final boolean isLoop;
    final boolean isSwitch;
    final List<FlowState> breaks = new ArrayList<>();
    final List<FlowState> continues = new ArrayList<>();

    Jump(String name, BStmt.Label label, boolean isLoop, boolean isSwitch) {
      this.name = name;
      this.label = label;
      this.isLoop = isLoop;
      this.isSwitch = isSwitch;
    }
  }

  /** The class whose {@code this} is in scope. */
  ClassSymbol cls;

  TypeScope typeScope;

  /** Enclosing method, or null in field initializers and initializer blocks. */
  MethodSymbol method;

  boolean isStatic;
  Scope scope;
  FlowState flow = new FlowState();

  /** Expected return type; null while inferring a lambda's result type. */
  Type returnType;

  LambdaFrame lambda;
  boolean isAsync;

  /** In a constructor body (or instance initializer). */
  boolean inConstructor;

  /** Before the {@code this(...)}/{@code super(...)} call of a constructor. */
  boolean beforeSuperCall;

  /** Property whose accessor is being checked (enables {@code field}), else null. */
  PropertySymbol accessorOf;

  FieldSymbol backingField;
  Deque<Jump> jumps = new ArrayDeque<>();

  /** Innermost catch variable (for {@code throw;}), or null. */
  VarSymbol catchVar;

  boolean checked;

  /** Shared counter for variable ids within one top-level body. */
  int[] varIds = new int[1];

  /** Local classes created in this body. */
  List<io.github.matrixidot.jsharp.compiler.bound.BClass> localClasses;

  Env() {}

  int nextVarId() {
    return varIds[0]++;
  }

  /** A copy for a nested body (lambda): shares class/static/ids, new jumps, copied flow. */
  Env nested() {
    Env e = new Env();
    e.cls = cls;
    e.typeScope = typeScope;
    e.method = method;
    e.isStatic = isStatic;
    e.scope = scope;
    e.flow = flow.copy();
    e.returnType = returnType;
    e.lambda = lambda;
    e.isAsync = isAsync;
    e.inConstructor = false;
    e.beforeSuperCall = beforeSuperCall;
    e.accessorOf = accessorOf;
    e.backingField = backingField;
    e.catchVar = catchVar;
    e.checked = checked;
    e.varIds = varIds;
    e.localClasses = localClasses;
    return e;
  }

  VarSymbol newVar(
      String name,
      Type type,
      long flags,
      VarSymbol.Kind kind,
      io.github.matrixidot.jsharp.compiler.source.Span span) {
    return new VarSymbol(name, type, flags, kind, span, nextVarId());
  }
}

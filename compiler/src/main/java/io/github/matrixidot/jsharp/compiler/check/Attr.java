package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.Context;
import io.github.matrixidot.jsharp.compiler.ast.AstPrinter;
import io.github.matrixidot.jsharp.compiler.ast.Body;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.ast.FieldInit;
import io.github.matrixidot.jsharp.compiler.ast.Param;
import io.github.matrixidot.jsharp.compiler.ast.TypeNode;
import io.github.matrixidot.jsharp.compiler.bound.BClass;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.bound.BExpr.ConvKind;
import io.github.matrixidot.jsharp.compiler.bound.BLValue;
import io.github.matrixidot.jsharp.compiler.bound.BStmt;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.DiagnosticSink;
import io.github.matrixidot.jsharp.compiler.diag.Severity;
import io.github.matrixidot.jsharp.compiler.resolve.FileScope;
import io.github.matrixidot.jsharp.compiler.resolve.MemberEnter;
import io.github.matrixidot.jsharp.compiler.resolve.Suggestions;
import io.github.matrixidot.jsharp.compiler.resolve.TypeResolver;
import io.github.matrixidot.jsharp.compiler.resolve.TypeScope;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.PropertySymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symtab;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import io.github.matrixidot.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The expression checker. Produces the bound tree ({@link BExpr}) from syntax, with bidirectional
 * typing: {@link #expr(Expr, Type)} takes an optional expected type that drives target-typed
 * constructs (lambdas, method references, {@code new()}, conditionals, generic calls), and {@link
 * #coerce} inserts implicit conversions and enforces nullness.
 *
 * <p>Statements live in {@link Stmts}, operators in {@link Ops}, calls and overload resolution in
 * {@link Calls}; they share this object's environment and diagnostics.
 */
public final class Attr {
  final Context ctx;
  final Types types;
  final Symtab syms;
  final MemberEnter memberEnter;
  final TypeResolver typeResolver;
  final Lookup lookup;
  final Ops ops;
  final Calls calls;
  final Stmts stmts;
  final Patterns patterns;
  final CollectionLiterals literals;
  final LocalFunctions localFunctions;

  private DiagnosticSink sink;
  Env env;
  int speculative;

  /** Initializers of fields with inferred types, attributed on demand. */
  final Map<FieldSymbol, BExpr> inferredFieldInits = new IdentityHashMap<>();

  /** Bodies of methods with inferred return types, attributed on demand. */
  final Map<MethodSymbol, BClass.Method> inferredMethodBodies = new IdentityHashMap<>();

  private final Set<Symbol> inferring = new HashSet<>();

  /** Variables captured by lambdas or local classes, to verify effective finality afterwards. */
  final List<VarSymbol> capturedVars = new ArrayList<>();

  /** Atomic locals declared so far (D084), checked for need at the end. */
  final List<VarSymbol> atomicVars = new ArrayList<>();

  final Map<VarSymbol, SourceFile> atomicFiles = new java.util.HashMap<>();

  final Map<VarSymbol, Span> captureSites = new IdentityHashMap<>();

  /** Captured variables of each local/anonymous class. */
  final Map<ClassSymbol, Set<VarSymbol>> localClassCaptures = new IdentityHashMap<>();

  /** Module classes per package (top-level functions/values visible package-wide). */
  final Map<String, List<ClassSymbol>> packageModules = new HashMap<>();

  final Map<ClassSymbol, int[]> anonCounters = new IdentityHashMap<>();

  /** Enclosing scopes of local/anonymous classes (their bodies can see enclosing locals). */
  final Map<ClassSymbol, Scope> localScopes = new IdentityHashMap<>();

  /** Local classes created while checking each method body. */
  final Map<MethodSymbol, List<BClass>> pendingLocal = new IdentityHashMap<>();

  /** Final fields assigned by each class's instance initializer blocks. */
  final Map<ClassSymbol, Set<FieldSymbol>> initAssigned = new IdentityHashMap<>();

  /** File of each capture site (for deferred effective-finality errors). */
  final Map<VarSymbol, SourceFile> captureFiles = new IdentityHashMap<>();

  public Attr(Context ctx, MemberEnter memberEnter) {
    this.ctx = ctx;
    this.types = new Types(ctx.syms);
    this.syms = ctx.syms;
    this.memberEnter = memberEnter;
    this.typeResolver = memberEnter.resolver();
    this.lookup = new Lookup(types);
    this.sink = ctx.diags;
    this.ops = new Ops(this);
    this.calls = new Calls(this);
    this.stmts = new Stmts(this);
    this.patterns = new Patterns(this);
    this.literals = new CollectionLiterals(this);
    this.localFunctions = new LocalFunctions(this);
  }

  public Types types() {
    return types;
  }

  /** The superclass constructor each anonymous class's constructor delegates to. */
  public Map<ClassSymbol, MethodSymbol> anonymousSuperConstructors() {
    return patterns.superCtors;
  }

  public void registerModule(ClassSymbol module) {
    packageModules.computeIfAbsent(module.packageName(), k -> new ArrayList<>()).add(module);
  }

  // ------------------------------------------------------------------ diagnostics

  SourceFile file() {
    return env.cls.unit() != null ? env.cls.unit().file() : null;
  }

  Diagnostic.Builder err(Code code, Span span, String message) {
    return Diagnostic.error(code, file(), span, message);
  }

  void error(Code code, Span span, String message) {
    report(err(code, span, message));
  }

  /** Number of diagnostics reported through this checker (including speculative ones). */
  int reported;

  void report(Diagnostic.Builder b) {
    reported++;
    sink.report(b.build());
  }

  void warn(Code code, Span span, String message) {
    sink.report(err(code, span, message).severity(Severity.WARNING).build());
  }

  boolean isSpeculative() {
    return speculative > 0;
  }

  /**
   * Runs {@code body} with diagnostics captured; returns its result and whether it reported errors.
   * Used to try lambdas against candidate overloads.
   */
  <T> Speculation<T> speculate(Supplier<T> body) {
    DiagnosticSink saved = sink;
    List<Diagnostic> buffer = new ArrayList<>();
    sink = buffer::add;
    speculative++;
    Env savedEnv = env;
    FlowState savedFlow = env.flow.copy();
    Map<ClassSymbol, int[]> savedCounters = new IdentityHashMap<>();
    anonCounters.forEach((k, v) -> savedCounters.put(k, v.clone()));
    try {
      T result = body.get();
      boolean errors = buffer.stream().anyMatch(Diagnostic::isError);
      return new Speculation<>(result, errors, !buffer.isEmpty());
    } finally {
      speculative--;
      sink = saved;
      env = savedEnv;
      env.flow.set(savedFlow);
      anonCounters.clear();
      anonCounters.putAll(savedCounters);
    }
  }

  record Speculation<T>(T result, boolean hasErrors, boolean hasDiagnostics) {}

  /** Call results whose type arguments were partly defaulted (they would benefit from a target). */
  final Set<BExpr> flexibleResults = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

  /** Runs {@code body} reporting to the real diagnostics even during speculation. */
  <T> T withRealDiagnostics(Supplier<T> body) {
    DiagnosticSink saved = sink;
    int savedSpec = speculative;
    sink = ctx.diags;
    speculative = 0;
    try {
      return body.get();
    } finally {
      sink = saved;
      speculative = savedSpec;
    }
  }

  // ------------------------------------------------------------------ env helpers

  Env pushEnv(Env e) {
    Env old = env;
    env = e;
    return old;
  }

  /** Resolves a type in the current body (method type params, local classes, class, file). */
  Type resolveType(TypeNode tn) {
    return typeResolver.resolve(tn, typeScope());
  }

  Type resolveType(TypeNode tn, TypeResolver.RawMode raw) {
    return typeResolver.resolve(tn, typeScope(), false, raw);
  }

  TypeScope typeScope() {
    Map<String, ClassSymbol> locals = new HashMap<>();
    for (Scope s = env.scope; s != null; s = s.parent) {
      for (var e : s.classes.entrySet()) {
        locals.putIfAbsent(e.getKey(), e.getValue());
      }
    }
    return locals.isEmpty() ? env.typeScope : new TypeScope.LocalLevel(locals, env.typeScope);
  }

  // ------------------------------------------------------------------ coercion

  /** Integer value of an int-typed constant (for constant narrowing), else null. */
  static Integer intConstant(BExpr b) {
    if (b instanceof BExpr.Const c
        && c.value() instanceof Integer i
        && b.type() instanceof PrimType p
        && (p == PrimType.INT || p == PrimType.SHORT || p == PrimType.BYTE)) {
      return i;
    }
    if (b instanceof BExpr.Const c && c.value() instanceof Character ch) {
      return (int) ch;
    }
    return null;
  }

  /**
   * Converts {@code b} to {@code target} using assignment conversion, reporting mismatches. Returns
   * the converted expression (an error node on failure).
   */
  BExpr coerce(BExpr b, Type target, Span span) {
    return coerce(b, target, span, null);
  }

  BExpr coerce(BExpr b, Type target, Span span, String what) {
    Type from = b.type();
    if (target == null || from.isError() || target.isError()) {
      return b;
    }
    if (from == PrimType.VOID) {
      error(Code.VOID_VALUE, span, "a void expression has no value");
      return new BExpr.Error(target, span);
    }
    if (target == PrimType.VOID) {
      return b;
    }
    if (target instanceof Type.NeverType) {
      if (!(from instanceof Type.NeverType)) {
        error(Code.TYPE_MISMATCH, span, "this expression must not complete normally");
      }
      return b;
    }
    Types.Conv c = types.assignConversion(from, target, intConstant(b));
    if (c == Types.Conv.NONE) {
      reportMismatch(b, target, span, what);
      return new BExpr.Error(target, span);
    }
    if (!types.nullnessCompatible(from, target) && from instanceof Type.NullType) {
      report(
          err(
                  Code.NULLABILITY_MISMATCH,
                  span,
                  (what == null ? "" : what + ": ")
                      + "'null' is not a value of the non-null type "
                      + target.display())
              .help(
                  target.isReference() && !(target instanceof Type.TypeVar)
                      ? "declare the type as '" + target.display() + "?' to allow null"
                      : null));
    } else if (!types.nullnessCompatible(from, target)) {
      report(
          err(
                  Code.NULLABILITY_MISMATCH,
                  span,
                  (what == null ? "" : what + ": ")
                      + "type "
                      + displayNullable(from)
                      + " may be null, but "
                      + target.display()
                      + " is required")
              .help(
                  "use '!' to assert non-null, '??' to supply a default, or check for null first"));
    }
    if (c == Types.Conv.UNBOX && from.nullness() == Nullness.NULLABLE) {
      report(
          err(
                  Code.NULLABILITY_MISMATCH,
                  span,
                  "cannot unbox nullable " + from.display() + " to " + target.display())
              .help("use '!' or '??' to provide a non-null value"));
    }
    BExpr r = applyConversion(b, c, target, span);
    if (from.nullness() == Nullness.PLATFORM
        && target.isReference()
        && target.nullness() == Nullness.NON_NULL
        && !(b instanceof BExpr.Const)
        && c != Types.Conv.BOX) {
      r = new BExpr.Conv(r, ConvKind.NULL_CHECK, r.type().withNullness(Nullness.NON_NULL), span);
    }
    return r;
  }

  private static String displayNullable(Type t) {
    return t instanceof Type.NullType ? "null" : t.display();
  }

  private void reportMismatch(BExpr b, Type target, Span span, String what) {
    Type from = b.type();
    String prefix = what == null ? "" : what + ": ";
    Diagnostic.Builder d =
        err(
            Code.TYPE_MISMATCH,
            span,
            prefix + "expected " + target.display() + ", found " + displayNullable(from));
    PrimType fp = types.primitiveView(from);
    PrimType tp = types.primitiveView(target);
    if (fp != null && tp != null && fp.isNumeric() && tp.isNumeric()) {
      d.help("use an explicit conversion: (" + tp.display() + ") value");
    } else if (from.isReference() && target.isReference() && types.isCastable(from, target)) {
      d.help(
          "use a cast '(" + target.display() + ")' or a type test 'is " + target.display() + "'");
    } else if (target == PrimType.BOOLEAN && fp != null && fp.isNumeric()) {
      d.help("J# does not treat numbers as booleans; compare explicitly, e.g. 'x != 0'");
    }
    report(d);
  }

  BExpr applyConversion(BExpr b, Types.Conv c, Type target, Span span) {
    switch (c) {
      case IDENTITY, REFERENCE -> {
        return b;
      }
      case WIDEN, NARROW_CONSTANT -> {
        return primConv(b, (PrimType) target, span);
      }
      case BOX -> {
        PrimType p = (PrimType) b.type();
        PrimType boxedPrim = p;
        if (target instanceof ClassType tc) {
          PrimType u = syms.unboxedOf(tc.sym());
          if (u != null && u != p) {
            boxedPrim = u;
            b = primConv(b, u, span);
          }
        }
        return new BExpr.Conv(b, ConvKind.BOX, syms.boxed(boxedPrim), span);
      }
      case UNBOX -> {
        PrimType u = types.unboxedType(b.type());
        BExpr r = new BExpr.Conv(b, ConvKind.UNBOX, u, span);
        if (u != target) {
          r = primConv(r, (PrimType) target, span);
        }
        return r;
      }
      default -> {
        return b;
      }
    }
  }

  /** Primitive conversion, folded for constants. */
  BExpr primConv(BExpr b, PrimType to, Span span) {
    if (b.type() == to) {
      return b;
    }
    if (b instanceof BExpr.Const k && k.value() != null && b.type() instanceof PrimType) {
      return new BExpr.Const(ConstFold.convert(k.value(), to), to, k.span());
    }
    return new BExpr.Conv(b, ConvKind.PRIMITIVE, to, span);
  }

  /** Converts to boolean for conditions. */
  BExpr coerceCondition(BExpr b, Span span) {
    if (b.type().isError()) {
      return b;
    }
    if (!types.isBoolean(b.type())) {
      Diagnostic.Builder d =
          err(
              Code.TYPE_MISMATCH,
              span,
              "condition must be boolean, found " + displayNullable(b.type()));
      if (types.isNumeric(b.type())) {
        d.help("J# does not treat numbers as booleans; compare explicitly, e.g. 'x != 0'");
      } else if (b.type().isReference()) {
        d.help("test for null explicitly, e.g. 'x != null'");
      }
      report(d);
      return new BExpr.Error(PrimType.BOOLEAN, span);
    }
    return coerce(b, PrimType.BOOLEAN, span);
  }

  // ------------------------------------------------------------------ expressions

  /**
   * When non-null, the type of every attributed expression, by file and span, even if the enclosing
   * expression later fails (editor tooling: the receiver of an incomplete {@code x.}).
   */
  private java.util.Map<SourceFile, java.util.Map<Span, Type>> recordedTypes;

  /** Turns on expression-type recording into {@code into} (see {@link #recordedTypes}). */
  public void recordTypesInto(java.util.Map<SourceFile, java.util.Map<Span, Type>> into) {
    this.recordedTypes = into;
  }

  private void recordType(Expr e, BExpr b) {
    if (recordedTypes != null
        && b != null
        && b.type() != null
        && !b.type().isError()
        && !isSpeculative()
        && file() != null) {
      recordedTypes
          .computeIfAbsent(file(), k -> new java.util.HashMap<>())
          .putIfAbsent(e.span(), b.type());
    }
  }

  /** Attributes {@code e} with an optional expected type; the result is not yet coerced. */
  BExpr expr(Expr e, Type pt) {
    BExpr b = exprImpl(e, pt);
    recordType(e, b);
    return b;
  }

  private BExpr exprImpl(Expr e, Type pt) {
    return switch (e) {
      case Expr.Literal l -> literal(l);
      case Expr.Interpolated i -> interpolated(i);
      case Expr.Name n -> {
        Expr fv = asFunctionValue(n);
        yield switch (fv) {
          case Expr.MethodRef fr -> methodRef(fr, pt);
          case Expr.Lambda l -> {
            BExpr mismatch = functionValueMismatch(n.name(), pt, n.span());
            yield mismatch != null ? mismatch : lambda(l, pt);
          }
          case null, default -> nameExpr(n);
        };
      }
      case Expr.Member m -> {
        Expr.MethodRef fr = asFunctionRef(m);
        yield fr != null ? methodRef(fr, pt) : memberExpr(m);
      }
      case Expr.Call c -> calls.call(c, pt);
      case Expr.Index i -> index(i);
      case Expr.New n -> newExpr(n, pt);
      case Expr.NewArray n -> newArray(n);
      case Expr.ArrayInit a -> arrayInit(a, pt);
      case Expr.CollectionLiteral c -> literals.collection(c, pt);
      case Expr.MapLiteral m -> literals.map(m, pt);
      case Expr.Spread s -> {
        error(Code.INVALID_LITERAL, s.span(), "'..' spreads are only allowed inside [...]");
        value(s.expr(), null);
        yield new BExpr.Error(Type.ErrorType.INSTANCE, s.span());
      }
      case Expr.Unary u -> ops.unary(u, pt);
      case Expr.Binary b -> ops.binary(b, pt);
      case Expr.Range r -> patterns.range(r);
      case Expr.Assign a -> ops.assign(a);
      case Expr.Conditional c -> conditional(c, pt);
      case Expr.Switch s -> patterns.switchExpr(s, pt);
      case Expr.Is i -> patterns.isExpr(i);
      case Expr.As a -> asExpr(a);
      case Expr.Cast c -> cast(c);
      case Expr.Lambda l -> lambda(l, pt);
      case Expr.MethodRef m -> methodRef(m, pt);
      case Expr.This t -> thisExpr(t);
      case Expr.Super s -> {
        error(Code.INVALID_THIS, s.span(), "'super' must be followed by a member access or call");
        yield new BExpr.Error(Type.ErrorType.INSTANCE, s.span());
      }
      case Expr.TypeOf t -> typeOf(t);
      case Expr.NameOf n -> nameOf(n);
      case Expr.Tuple t -> patterns.tuple(t, pt);
      case Expr.With w -> patterns.with(w);
      case Expr.Throw t -> {
        BExpr ex = exprCoerced(t.expr(), syms.throwableType(), t.expr().span());
        env.flow.alive = false;
        yield new BExpr.Throw(ex, Type.NeverType.INSTANCE, t.span());
      }
      case Expr.Await a -> patterns.await(a);
      case Expr.Checked c -> {
        boolean saved = env.checked;
        env.checked = true;
        try {
          yield expr(c.expr(), pt);
        } finally {
          env.checked = saved;
        }
      }
      case Expr.Paren p -> expr(p.expr(), pt);
      case Expr.Error err -> new BExpr.Error(Type.ErrorType.INSTANCE, err.span());
    };
  }

  /** Attributes and converts to {@code target}. */
  BExpr exprCoerced(Expr e, Type target, Span span) {
    return coerce(expr(e, target), target, span);
  }

  BExpr exprCoerced(Expr e, Type target) {
    return exprCoerced(e, target, e.span());
  }

  /** Attributes an expression whose value is required (not void). */
  BExpr value(Expr e, Type pt) {
    BExpr b = expr(e, pt);
    if (b.type() == PrimType.VOID) {
      error(Code.VOID_VALUE, e.span(), "a void expression has no value");
      return new BExpr.Error(Type.ErrorType.INSTANCE, e.span());
    }
    return b;
  }

  // ------------------------------------------------------------------ literals

  private BExpr literal(Expr.Literal l) {
    return switch (l.kind()) {
      case INT -> {
        long v = (Long) l.value();
        if (v > Integer.MAX_VALUE
            && !l.text().startsWith("0x")
            && !l.text().startsWith("0X")
            && !l.text().startsWith("0b")
            && !l.text().startsWith("0B")) {
          report(
              err(
                      Code.LITERAL_OUT_OF_RANGE,
                      l.span(),
                      "integer literal " + l.text() + " is out of range for int")
                  .help("add an 'L' suffix for a long literal: " + l.text() + "L"));
          yield new BExpr.Const(0, PrimType.INT, l.span());
        }
        yield new BExpr.Const((int) v, PrimType.INT, l.span());
      }
      case LONG -> {
        long v = (Long) l.value();
        String digits = l.text().replace("_", "");
        if (!digits.startsWith("0x")
            && !digits.startsWith("0X")
            && !digits.startsWith("0b")
            && !digits.startsWith("0B")
            && new java.math.BigInteger(digits.substring(0, digits.length() - 1))
                    .compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))
                > 0) {
          error(
              Code.LITERAL_OUT_OF_RANGE, l.span(), "long literal " + l.text() + " is out of range");
          yield new BExpr.Const(0L, PrimType.LONG, l.span());
        }
        yield new BExpr.Const(v, PrimType.LONG, l.span());
      }
      case FLOAT -> new BExpr.Const(l.value(), PrimType.FLOAT, l.span());
      case DOUBLE -> new BExpr.Const(l.value(), PrimType.DOUBLE, l.span());
      case CHAR -> new BExpr.Const(l.value(), PrimType.CHAR, l.span());
      case STRING -> new BExpr.Const(l.value(), syms.stringType(), l.span());
      case BOOLEAN -> new BExpr.Const(l.value(), PrimType.BOOLEAN, l.span());
      case NULL -> new BExpr.Const(null, Type.NullType.INSTANCE, l.span());
    };
  }

  /** {@code -2147483648} and {@code -9223372036854775808L}: the literal is only legal negated. */
  BExpr negatedLiteral(Expr.Literal l) {
    if (l.kind() == Expr.LiteralKind.INT && (Long) l.value() == 2147483648L) {
      return new BExpr.Const(Integer.MIN_VALUE, PrimType.INT, l.span());
    }
    if (l.kind() == Expr.LiteralKind.LONG
        && l.text().replace("_", "").replaceAll("[lL]$", "").equals("9223372036854775808")) {
      return new BExpr.Const(Long.MIN_VALUE, PrimType.LONG, l.span());
    }
    return null;
  }

  private BExpr interpolated(Expr.Interpolated i) {
    List<BExpr> parts = new ArrayList<>();
    for (Expr.Part p : i.parts()) {
      switch (p) {
        case Expr.TextPart t -> parts.add(new BExpr.Const(t.text(), syms.stringType(), i.span()));
        case Expr.HolePart h -> {
          BExpr v = value(h.expr(), null);
          if (h.format() != null) {
            v = formatted(v, h.format(), h.span());
          }
          parts.add(v);
        }
      }
    }
    return new BExpr.Concat(parts, syms.stringType(), i.span());
  }

  /** {@code {x:F2}} -> {@code String.format(Locale.ROOT, "%.2f", x)}. */
  private BExpr formatted(BExpr v, String spec, Span span) {
    FormatSpecs.Translation t = FormatSpecs.translate(spec);
    if (t == null) {
      report(
          err(Code.INVALID_INTERPOLATION_FORMAT, span, "unknown format specifier '" + spec + "'")
              .help(
                  "use F<n>, N<n>, D<n>, X<n>, E<n>, P<n>, G, or a Java format starting with '%'"));
      return v;
    }
    PrimType p = types.primitiveView(v.type());
    if (t.domain() == FormatSpecs.Domain.FLOATING && (p == null || !p.isNumeric())) {
      error(
          Code.INVALID_INTERPOLATION_FORMAT,
          span,
          "format '" + spec + "' needs a number, found " + v.type().display());
      return v;
    }
    if (t.domain() == FormatSpecs.Domain.INTEGRAL
        && (p == null || !p.isIntegral() || p == PrimType.CHAR)) {
      error(
          Code.INVALID_INTERPOLATION_FORMAT,
          span,
          "format '" + spec + "' needs an integer, found " + v.type().display());
      return v;
    }
    BExpr arg = v;
    if (t.domain() == FormatSpecs.Domain.FLOATING && p != PrimType.DOUBLE && p != PrimType.FLOAT) {
      arg = coerce(v, PrimType.DOUBLE, span);
    }
    if (t.percent()) {
      arg =
          new BExpr.Binary(
              BExpr.BinOp.MUL,
              coerce(arg, PrimType.DOUBLE, span),
              new BExpr.Const(100.0, PrimType.DOUBLE, span),
              PrimType.DOUBLE,
              false,
              span);
    }
    arg = coerce(arg, syms.objectType().withNullness(Nullness.NULLABLE), span);
    ClassSymbol string = syms.stringType().sym();
    MethodSymbol format = null;
    for (MethodSymbol m : string.methods("format")) {
      if (m.params().size() == 3) {
        format = m;
      }
    }
    BExpr locale =
        new BExpr.Field(
            null,
            syms.wellKnown("java/util/Locale").sym().field("ROOT"),
            syms.wellKnown("java/util/Locale"),
            span);
    BExpr array =
        new BExpr.NewArray(
            Type.ArrayType.of(syms.objectType().withNullness(Nullness.NULLABLE)),
            List.of(),
            List.of(arg),
            span);
    return new BExpr.Call(
        null,
        format,
        List.of(locale, new BExpr.Const(t.javaFormat(), syms.stringType(), span), array),
        BExpr.CallKind.STATIC,
        syms.stringType(),
        span);
  }

  // ------------------------------------------------------------------ names

  /** What a (possibly qualified) name denotes. */
  sealed interface Target {}

  record ValueTarget(BExpr expr) implements Target {}

  record TypeTarget(Type type, Span span) implements Target {}

  record PackageTarget(String name, Span span) implements Target {}

  record SuperTarget(ClassType superType, Span span) implements Target {}

  private BExpr nameExpr(Expr.Name n) {
    Target t = target(n, false);
    return asValue(t, n.span());
  }

  BExpr asValue(Target t, Span span) {
    return switch (t) {
      case ValueTarget v -> v.expr();
      case TypeTarget tt -> {
        if (!tt.type().isError()) {
          report(
              err(
                      Code.UNRESOLVED_NAME,
                      span,
                      "'" + tt.type().display() + "' is a type, not a value")
                  .help("use typeof(" + tt.type().display() + ") for its Class object"));
        }
        yield new BExpr.Error(Type.ErrorType.INSTANCE, span);
      }
      case PackageTarget p -> {
        error(Code.UNRESOLVED_NAME, span, "'" + p.name() + "' is a package, not a value");
        yield new BExpr.Error(Type.ErrorType.INSTANCE, span);
      }
      case SuperTarget s -> {
        error(Code.INVALID_THIS, span, "'super' must be followed by a member access or call");
        yield new BExpr.Error(Type.ErrorType.INSTANCE, span);
      }
    };
  }

  /**
   * Attributes an expression that may denote a type or package (a member-access qualifier).
   *
   * @param quietTypes when true, unresolved simple names fall back to type/package lookup silently
   */
  Target target(Expr e, boolean quietTypes) {
    Target t = targetImpl(e, quietTypes);
    if (t instanceof ValueTarget vt) {
      recordType(e, vt.expr());
    }
    return t;
  }

  private Target targetImpl(Expr e, boolean quietTypes) {
    return switch (e) {
      case Expr.Name n -> nameTarget(n);
      case Expr.Member m when !m.nullSafe() -> {
        Target q = target(m.target(), true);
        yield switch (q) {
          case PackageTarget p -> {
            String full = p.name() + "." + m.name();
            ClassSymbol c = syms.lookup(full.replace('.', '/'));
            if (c != null && TypeResolver.isAccessible(c, env.cls, env.cls.packageName())) {
              yield new TypeTarget(ClassType.of(c), m.span());
            }
            if (syms.packageExists(full)) {
              yield new PackageTarget(full, m.span());
            }
            report(
                err(
                    Code.UNRESOLVED_NAME,
                    m.nameSpan(),
                    "cannot find '" + m.name() + "' in package " + p.name()));
            yield new ValueTarget(new BExpr.Error(Type.ErrorType.INSTANCE, m.span()));
          }
          case TypeTarget tt -> {
            if (tt.type().isError()) {
              yield tt;
            }
            ClassSymbol nested =
                tt.type() instanceof ClassType ct
                    ? TypeScope.memberTypeInherited(ct.sym(), m.name(), new HashSet<>())
                    : null;
            if (nested != null && staticMemberValue(tt, m) == null) {
              yield new TypeTarget(ClassType.of(nested), m.span());
            }
            yield new ValueTarget(staticMember(tt, m));
          }
          case ValueTarget v -> new ValueTarget(member(v.expr(), m.name(), m.nameSpan(), m.span()));
          case SuperTarget s -> new ValueTarget(superMember(s, m));
        };
      }
      case Expr.Super s -> superTarget(s);
      case Expr.Paren p -> target(p.expr(), quietTypes);
      default -> new ValueTarget(value(e, null));
    };
  }

  SuperTarget superTarget(Expr.Super s) {
    if (env.isStatic) {
      error(Code.STATIC_CONTEXT, s.span(), "'super' cannot be used in a static context");
    }
    noteThisUse();
    if (s.qualifier() != null) {
      // I.super.m(): I must be a direct superinterface (Java rule).
      Target q = target(s.qualifier(), true);
      if (q instanceof TypeTarget tt && tt.type() instanceof ClassType qt) {
        for (ClassType i : env.cls.interfaces()) {
          if (i.sym() == qt.sym()) {
            return new SuperTarget(i, s.span());
          }
        }
        error(
            Code.INVALID_THIS,
            s.qualifier().span(),
            "'"
                + qt.display()
                + ".super' needs "
                + qt.display()
                + " to be a direct superinterface of "
                + env.cls.name());
      } else {
        error(Code.INVALID_THIS, s.qualifier().span(), "'X.super' needs an interface name");
      }
    }
    ClassType sup = env.cls.superclass();
    if (env.cls.isInterface() || sup == null) {
      sup = syms.objectType();
    }
    return new SuperTarget((ClassType) Types.subst(sup, Map.of()), s.span());
  }

  private Target nameTarget(Expr.Name n) {
    String name = n.name();
    Span span = n.span();
    // 1. locals (and the `field` keyword inside accessors)
    if (name.equals("field") && env.backingField != null && env.scope.lookup("field") == null) {
      BExpr recv = env.backingField.isStatic() ? null : thisValue(span);
      return new ValueTarget(
          new BExpr.Field(recv, env.backingField, env.backingField.type(), span));
    }
    Scope.Found found = env.scope.lookup(name);
    if (found != null) {
      return new ValueTarget(localRef(found, span));
    }
    // 2. members of enclosing classes (innermost first)
    BExpr member = implicitMember(name, span);
    if (member != null) {
      return new ValueTarget(member);
    }
    // 3. top-level values of this file and package, static imports
    BExpr stat = staticImportedValue(name, span);
    if (stat != null) {
      return new ValueTarget(stat);
    }
    // 4. type names
    TypeScope.Found tf = typeScope().find(name);
    if (tf instanceof TypeScope.FoundClass(ClassSymbol c)) {
      return new TypeTarget(ClassType.of(c), span);
    }
    if (tf instanceof TypeScope.FoundVar(TypeVarSymbol tv)) {
      return new TypeTarget(tv.asType(), span);
    }
    if (tf instanceof TypeScope.FoundAmbiguous(List<ClassSymbol> cands)) {
      error(
          Code.AMBIGUOUS_TYPE,
          span,
          "'"
              + name
              + "' is ambiguous between "
              + cands.getFirst().qualifiedName()
              + " and "
              + cands.get(1).qualifiedName());
      return new TypeTarget(Type.ErrorType.INSTANCE, span);
    }
    // 5. packages
    if (syms.packageExists(name)) {
      return new PackageTarget(name, span);
    }
    reportUnresolvedName(name, span);
    return new ValueTarget(new BExpr.Error(Type.ErrorType.INSTANCE, span));
  }

  /**
   * A name that denotes only methods, used as a value, is a method reference (D083): {@code twice}
   * is like a reference to the function {@code twice}, {@code Math.abs} like {@code Math::abs} and
   * {@code list.add} like {@code list::add}. Variables, fields, properties, types and packages of
   * the same name take precedence. Returns the equivalent reference, or null.
   */
  Expr.MethodRef asFunctionRef(Expr e) {
    if (e instanceof Expr.Name n
        && env.scope.lookup(n.name()) == null
        && env.scope.lookupFunction(n.name()) != null) {
      return null; // a local function: see asFunctionValue
    }
    return switch (e) {
      case Expr.Name n when n.typeArgs().isEmpty() && denotesOnlyFunctions(n.name()) ->
          new Expr.MethodRef(null, null, n.name(), n.span(), n.span());
      case Expr.Member m
          when !m.nullSafe()
              && m.typeArgs().isEmpty()
              && isNameChain(m.target())
              && memberDenotesOnlyMethods(m) ->
          new Expr.MethodRef(m.target(), null, m.name(), m.nameSpan(), m.span());
      default -> null;
    };
  }

  /**
   * {@link #asFunctionRef}, or for a local function used by name the lambda {@code ($0, ...) =>
   * f($0, ...)} (D083). Null if {@code e} is not a function used as a value.
   */
  Expr asFunctionValue(Expr e) {
    if (e instanceof Expr.Name n && n.typeArgs().isEmpty() && env.scope.lookup(n.name()) == null) {
      LocalFunctions.Fn fn = env.scope.lookupFunction(n.name());
      if (fn != null) {
        List<io.github.matrixidot.jsharp.compiler.ast.Param> ps = new ArrayList<>();
        List<io.github.matrixidot.jsharp.compiler.ast.Arg> args = new ArrayList<>();
        for (int i = 0; i < fn.sym.params().size(); i++) {
          String p = "$" + i;
          ps.add(
              new io.github.matrixidot.jsharp.compiler.ast.Param(
                  io.github.matrixidot.jsharp.compiler.ast.Modifiers.empty(n.span().start()),
                  false,
                  false,
                  null,
                  p,
                  n.span(),
                  null,
                  n.span()));
          args.add(
              new io.github.matrixidot.jsharp.compiler.ast.Arg(
                  null, new Expr.Name(p, List.of(), n.span()), n.span()));
        }
        Expr call = new Expr.Call(new Expr.Name(n.name(), List.of(), n.span()), args, n.span());
        return new Expr.Lambda(
            ps, false, new io.github.matrixidot.jsharp.compiler.ast.Body.ExprBody(call), n.span());
      }
    }
    return asFunctionRef(e);
  }

  private boolean denotesOnlyFunctions(String name) {
    if (env.scope.lookup(name) != null || name.equals("field") && env.backingField != null) {
      return false;
    }
    for (ClassSymbol c = env.cls; c != null; c = c.outer()) {
      ClassType site = c.thisType();
      if (lookup.findProperty(site, name) != null
          || lookup.findField(site, name) != null
          || !c.has(Flags.MODULE) && lookup.findGetter(site, name) != null) {
        return false;
      }
    }
    ClassSymbol module = moduleOf(env.cls);
    if (module != null && module.field(name) != null) {
      return false;
    }
    for (ClassSymbol m : packageModules.getOrDefault(env.cls.packageName(), List.of())) {
      if (m.field(name) != null) {
        return false;
      }
    }
    FileScope fs = fileScope();
    for (FileScope.StaticImport si : fs.staticSingleImports()) {
      String visible = si.alias() != null ? si.alias() : si.member();
      if (visible.equals(name) && si.owner().field(si.member()) != null) {
        return false;
      }
    }
    for (ClassSymbol c : fs.staticOnDemandImports()) {
      FieldSymbol f = lookup.findField(c.thisType(), name);
      if (f != null && f.isStatic()) {
        return false;
      }
    }
    if (typeScope().find(name) != null || syms.packageExists(name)) {
      return false;
    }
    return calls.functionGroup(name) != null;
  }

  /** {@code a}, {@code this}, {@code a.b.c}: qualifiers cheap to attribute speculatively. */
  private static boolean isNameChain(Expr e) {
    return switch (e) {
      case Expr.Name n -> true;
      case Expr.This t -> true;
      case Expr.Member m -> !m.nullSafe() && isNameChain(m.target());
      default -> false;
    };
  }

  private boolean memberDenotesOnlyMethods(Expr.Member m) {
    Speculation<Boolean> s =
        speculate(
            () -> {
              Type site =
                  switch (target(m.target(), true)) {
                    case TypeTarget tt -> tt.type();
                    case ValueTarget vt -> vt.expr().type();
                    default -> null;
                  };
              if (site == null || site.isError() || site instanceof PrimType) {
                return false;
              }
              String name = m.name();
              return lookup.findProperty(site, name) == null
                  && lookup.findField(site, name) == null
                  && lookup.findGetter(site, name) == null
                  && lookup.hasMethodNamed(site, name);
            });
    return !s.hasErrors() && Boolean.TRUE.equals(s.result());
  }

  void reportUnresolvedName(String name, Span span) {
    Diagnostic.Builder d =
        err(Code.UNRESOLVED_NAME, span, "cannot find '" + name + "' in this scope");
    String hidden = env.scope.hiddenFunctionNote(name);
    if (hidden != null) {
      report(d.help(hidden));
      return;
    }
    String importable =
        io.github.matrixidot.jsharp.compiler.resolve.TypeResolver.importable(ctx, name);
    if (importable != null) {
      // `List.of(...)` without `import java.util.List;`
      report(d.help("add 'import " + importable + ";'"));
      return;
    }
    Set<String> names = new LinkedHashSet<>();
    env.scope.collectNames(names);
    for (ClassSymbol c = env.cls; c != null; c = c.outer()) {
      names.addAll(lookup.memberNames(c.thisType()));
    }
    ClassSymbol module = moduleOf(env.cls);
    if (module != null) {
      names.addAll(lookup.memberNames(module.thisType()));
    }
    for (ClassSymbol c : fileScope().staticOnDemandImports()) {
      for (FieldSymbol f : c.fields()) {
        names.add(f.name());
      }
      for (MethodSymbol m : c.allMethods()) {
        names.add(m.name());
      }
    }
    String guess = Suggestions.closest(name, names);
    if (guess != null) {
      d.help("did you mean '" + guess + "'?");
    } else if (env.cls.has(Flags.MODULE)
        && env.method != null
        && !env.method.has(Flags.ENTRY_POINT)
        && isTopLevelLocal(name)) {
      d.note("'" + name + "' is a local of the top-level statements, which functions cannot see")
          .help("make it a top-level value: 'private val " + name + " = ...;'");
    }
    report(d.label("not found"));
  }

  private boolean isTopLevelLocal(String name) {
    if (env.cls.unit() == null) {
      return false;
    }
    for (var d : env.cls.unit().members()) {
      if (d instanceof io.github.matrixidot.jsharp.compiler.ast.Decl.TopLevelStmt t
          && t.stmt() instanceof io.github.matrixidot.jsharp.compiler.ast.Stmt.LocalVar lv
          && lv.vars().stream().anyMatch(v -> v.name().equals(name))) {
        return true;
      }
    }
    return false;
  }

  FileScope fileScope() {
    return ctx.fileScope(env.cls.outermost().unit());
  }

  ClassSymbol moduleOf(ClassSymbol c) {
    ClassSymbol top = c.outermost();
    if (top.has(Flags.MODULE)) {
      return top;
    }
    return top.unit() != null && ctx.fileScope(top.unit()) != null
        ? ctx.fileScope(top.unit()).moduleClass()
        : null;
  }

  /** Reference to a local variable, recording captures and applying smart casts. */
  private BExpr localRef(Scope.Found found, Span span) {
    VarSymbol v = found.var();
    for (Scope crossed : found.crossed()) {
      if (crossed.boundary == Scope.Boundary.LAMBDA) {
        crossed.lambda.captures.add(v);
      } else if (crossed.boundary == Scope.Boundary.CLASS) {
        localClassCaptures.computeIfAbsent(crossed.localClass, k -> new LinkedHashSet<>()).add(v);
      }
    }
    if (!found.crossed().isEmpty() && !isSpeculative()) {
      capturedVars.add(v);
      captureSites.putIfAbsent(v, span);
      captureFiles.putIfAbsent(v, file());
    }
    if (found.crossed().stream().noneMatch(s -> s.boundary == Scope.Boundary.CLASS)
        && (v.kind() == VarSymbol.Kind.LOCAL || v.kind() == VarSymbol.Kind.PATTERN)
        && !env.flow.assigned.get(v.id())
        && env.flow.alive) {
      report(
          err(
                  Code.UNINITIALIZED_VARIABLE,
                  span,
                  "variable '" + v.name() + "' might not have been initialized")
              .note("declared here", file(), v.span()));
      env.flow.assigned.set(v.id());
    }
    BExpr ref = new BExpr.Local(v, span);
    // Another thread may change an atomic variable between a check and a use: no smart casts.
    Type narrowed = v.isAtomic() ? null : env.flow.narrowed.get(v);
    if (narrowed != null && !narrowed.equals(v.type())) {
      boolean needsCast =
          !types.isSameType(narrowed.erasure(), v.type().erasure())
              && !types.isSubtype(v.type(), narrowed);
      return new BExpr.Conv(ref, needsCast ? ConvKind.CHECKCAST : ConvKind.RETYPE, narrowed, span);
    }
    return ref;
  }

  /**
   * Reads local {@code v} at the current point for a call of a local function that captured it
   * (D083): noted as a capture by enclosing lambdas, checked for definite assignment.
   */
  BExpr captureValue(VarSymbol v, Span span) {
    Scope.Found f = env.scope.lookup(v.name());
    if (f == null || f.var() != v) {
      return new BExpr.Local(v, span);
    }
    BExpr ref = localRef(f, span);
    if (v.cell() != null) {
      return new BExpr.Local(v.cell(), span); // atomic: the function receives the cell (D084)
    }
    // The function's parameter has the variable's declared type, not a narrowed one.
    return ref instanceof BExpr.Conv c && c.expr() instanceof BExpr.Local l ? l : ref;
  }

  /** {@code this} of the current class, noting the capture for lambdas. */
  BExpr thisValue(Span span) {
    if (env.isStatic) {
      error(Code.STATIC_CONTEXT, span, "'this' is not available in a static context");
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    noteThisUse();
    return new BExpr.This(env.cls.thisType(), span);
  }

  void noteThisUse() {
    for (Scope s = env.scope; s != null; s = s.parent) {
      if (s.boundary == Scope.Boundary.LAMBDA) {
        s.lambda.capturesThis = true;
      } else if (s.boundary == Scope.Boundary.CLASS) {
        break;
      }
    }
  }

  /**
   * A field or property of an enclosing class used by simple name. Returns null if no enclosing
   * class has a member with this name.
   */
  private BExpr implicitMember(String name, Span span) {
    boolean throughStatic = env.isStatic;
    ClassSymbol c = env.cls;
    boolean first = true;
    while (c != null) {
      BExpr found = memberOfClass(c, name, span, first, throughStatic);
      if (found != null) {
        return found;
      }
      // J# named nested types are static: only local/anonymous classes see outer instances.
      boolean canReachOuterInstance =
          (c.has(Flags.LOCAL) || c.has(Flags.ANONYMOUS)) && !c.has(Flags.STATIC);
      if (!canReachOuterInstance) {
        throughStatic = true;
      }
      first = false;
      c = c.outer();
    }
    return null;
  }

  private BExpr memberOfClass(
      ClassSymbol c, String name, Span span, boolean isCurrent, boolean staticOnly) {
    ClassType site = c.thisType();
    PropertySymbol p = lookup.findProperty(site, name);
    FieldSymbol f = p == null ? lookup.findField(site, name) : null;
    if (f != null
        && !c.has(Flags.MODULE)
        && hiddenByAccessor(f, site, lookup.findGetter(site, name))) {
      f = null;
    }
    MethodSymbol getter =
        p == null && f == null && !c.has(Flags.MODULE) ? lookup.findGetter(site, name) : null;
    if (p == null && f == null && getter == null) {
      return null;
    }
    boolean isStatic = p != null ? p.isStatic() : f != null ? f.isStatic() : getter.isStatic();
    BExpr recv = null;
    if (!isStatic) {
      if (staticOnly) {
        if (env.cls != c && !isCurrent) {
          report(
              err(
                      Code.STATIC_CONTEXT,
                      span,
                      "cannot use instance member '" + name + "' of " + c.name() + " here")
                  .note("nested classes in J# are static and have no outer instance"));
        } else {
          error(
              Code.STATIC_CONTEXT,
              span,
              "instance member '" + name + "' cannot be used in a static context");
        }
        return new BExpr.Error(Type.ErrorType.INSTANCE, span);
      }
      if (isCurrent) {
        recv = thisValue(span);
      } else {
        noteThisUse();
        recv = new BExpr.OuterThis(c, c.thisType(), span);
      }
    }
    if (p != null) {
      return propertyGet(recv, site, p, span);
    }
    if (f != null) {
      return fieldGet(recv, site, f, span);
    }
    return beanGet(recv, site, getter, span);
  }

  /** A top-level value of the file/package, or a statically imported field. */
  private BExpr staticImportedValue(String name, Span span) {
    ClassSymbol module = moduleOf(env.cls);
    List<ClassSymbol> modules = new ArrayList<>();
    if (module != null) {
      modules.add(module);
    }
    for (ClassSymbol m : packageModules.getOrDefault(env.cls.packageName(), List.of())) {
      if (m != module) {
        modules.add(m);
      }
    }
    for (ClassSymbol m : modules) {
      FieldSymbol f = m.field(name);
      if (f != null && (m == module || !f.has(Flags.PRIVATE))) {
        return fieldGet(null, m.thisType(), f, span);
      }
    }
    FileScope fs = fileScope();
    for (FileScope.StaticImport si : fs.staticSingleImports()) {
      String visible = si.alias() != null ? si.alias() : si.member();
      if (visible.equals(name)) {
        FieldSymbol f = si.owner().field(si.member());
        if (f != null && f.isStatic()) {
          return fieldGet(null, si.owner().thisType(), f, span);
        }
      }
    }
    for (ClassSymbol c : fs.staticOnDemandImports()) {
      FieldSymbol f = lookup.findField(c.thisType(), name);
      if (f != null && f.isStatic() && lookup.isAccessible(f, f.owner(), null, env.cls)) {
        return fieldGet(null, c.thisType(), f, span);
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ member access

  /** The type of a member declared in {@code owner}, as seen from {@code site}. */
  Type memberType(Type site, ClassSymbol owner, Type declared) {
    if (site == null || declared == null) {
      return declared;
    }
    ClassType as = types.asSuper(site, owner);
    if (as == null) {
      return declared;
    }
    if (as.isRaw()) {
      return declared.erasure().withNullness(Nullness.PLATFORM);
    }
    return Types.subst(declared, types.typeArgMap(as));
  }

  /** Captures wildcard type arguments of a receiver type. */
  Type captureSite(Type t) {
    return t instanceof ClassType ct ? types.capture(ct) : t;
  }

  private final Set<FieldSymbol> constantsTried = new HashSet<>();

  /**
   * Computes the constant value of a static final primitive/String field with a constant
   * initializer.
   */
  void ensureConstant(FieldSymbol f) {
    if (!f.isStatic()
        || !f.has(Flags.FINAL)
        || f.declarator() == null
        || f.declarator().init() == null
        || f.constantValue() != null
        || !constantsTried.add(f)) {
      return;
    }
    Type t = f.type();
    if (t != null
        && !(t instanceof PrimType)
        && !(t instanceof ClassType ct && ct.sym().binaryName().equals("java/lang/String"))) {
      return;
    }
    Speculation<BExpr> s =
        speculate(
            () -> {
              Env saved = env;
              env = ClassChecker.fieldEnv(this, f);
              try {
                return f.type() == null
                    ? value(f.declarator().init(), null)
                    : exprCoerced(f.declarator().init(), f.type());
              } finally {
                env = saved;
              }
            });
    if (!s.hasErrors()
        && s.result() instanceof BExpr.Const k
        && k.value() != null
        && (f.type() != null || k.type() instanceof PrimType || k.value() instanceof String)) {
      if (f.type() == null) {
        f.setType(k.type());
      }
      f.setConstantValue(k.value());
    }
  }

  BExpr fieldGet(BExpr recv, Type site, FieldSymbol f, Span span) {
    ensureConstant(f);
    checkAccess(f, f.owner(), recv == null ? null : site, span);
    checkDeprecated(f, span);
    Type t = types.uncapture(memberType(captureSite(site), f.owner(), ensureFieldType(f)));
    if (f.constantValue() != null && f.isStatic() && f.has(Flags.FINAL)) {
      Object v = f.constantValue();
      Type ct = t instanceof PrimType ? t : syms.stringType();
      if (v instanceof Integer i && t instanceof PrimType p && p == PrimType.CHAR) {
        v = (char) i.intValue();
      }
      return new BExpr.Const(v, ct, span);
    }
    return new BExpr.Field(f.isStatic() ? null : recv, f, t, span);
  }

  BExpr propertyGet(BExpr recv, Type site, PropertySymbol p, Span span) {
    if (p.owner().isRecord()
        && p.owner() == env.cls
        && !p.isStatic()
        && recv instanceof BExpr.This
        && p.backingField() != null) {
      // Inside a record, a component reads its field (Java rule); this is what lets an explicit
      // accessor `double c() => round(c)` use the stored value instead of recursing.
      return fieldGet(recv, site, p.backingField(), span);
    }
    MethodSymbol getter = p.getter();
    if (getter == null) {
      error(Code.UNRESOLVED_MEMBER, span, "property '" + p.name() + "' has no getter");
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    checkAccess(getter, p.owner(), recv == null ? null : site, span);
    Type t =
        types.uncapture(memberType(captureSite(site), p.owner(), ensureReturnTypeOf(getter, p)));
    return new BExpr.Call(
        p.isStatic() ? null : recv, getter, List.of(), callKind(getter, recv, false), t, span);
  }

  BExpr beanGet(BExpr recv, Type site, MethodSymbol getter, Span span) {
    checkAccess(getter, getter.owner(), recv == null ? null : site, span);
    checkDeprecated(getter, span);
    Type t = types.uncapture(memberType(captureSite(site), getter.owner(), getter.returnType()));
    return new BExpr.Call(
        getter.isStatic() ? null : recv, getter, List.of(), callKind(getter, recv, false), t, span);
  }

  private Type ensureReturnTypeOf(MethodSymbol getter, PropertySymbol p) {
    return getter.returnType() != null ? getter.returnType() : p.type();
  }

  static BExpr.CallKind callKind(MethodSymbol m, BExpr recv, boolean isSuper) {
    if (m.isStatic()) {
      return BExpr.CallKind.STATIC;
    }
    if (isSuper || m.isConstructor()) {
      return BExpr.CallKind.SPECIAL;
    }
    return m.owner().isInterface() ? BExpr.CallKind.INTERFACE : BExpr.CallKind.VIRTUAL;
  }

  /**
   * True if {@code f} is not accessible here but an accessible JavaBeans accessor of the same name
   * is: {@code shape.name} then means {@code shape.getName()} (a private Java field must not hide
   * its public getter).
   */
  boolean hiddenByAccessor(FieldSymbol f, Type site, MethodSymbol accessor) {
    return f != null
        && accessor != null
        && !lookup.isAccessible(f, f.owner(), site, env.cls)
        && lookup.isAccessible(accessor, accessor.owner(), site, env.cls);
  }

  void checkAccess(Symbol member, ClassSymbol owner, Type site, Span span) {
    if (!lookup.isAccessible(member, owner, site, env.cls)) {
      error(
          Code.INACCESSIBLE_MEMBER,
          span,
          member.kindName()
              + " '"
              + member.name()
              + "' of "
              + owner.displayName()
              + " is "
              + Flags.accessOf(member));
    }
  }

  void checkDeprecated(Symbol s, Span span) {
    if (s.has(Flags.DEPRECATED) && !isSpeculative()) {
      warn(Code.DEPRECATED, span, s.kindName() + " '" + s.name() + "' is deprecated");
    }
  }

  /** {@code recv.name} (not a call). */
  BExpr member(BExpr recv, String name, Span nameSpan, Span span) {
    Type site = recv.type();
    if (site.isError()) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    if (site instanceof PrimType p) {
      if (p == PrimType.VOID) {
        error(Code.VOID_VALUE, recv.span(), "a void expression has no members");
        return new BExpr.Error(Type.ErrorType.INSTANCE, span);
      }
      recv = coerce(recv, syms.boxed(p), recv.span());
      site = recv.type();
    }
    checkReceiverNullness(recv, nameSpan);
    if (site instanceof Type.ArrayType && name.equals("length")) {
      return new BExpr.ArrayLength(recv, PrimType.INT, span);
    }
    if (site instanceof Type.TupleType tt) {
      BExpr t = patterns.tupleElement(recv, tt, name, nameSpan, span);
      if (t != null) {
        return t;
      }
    }
    PropertySymbol p = lookup.findProperty(site, name);
    if (p != null) {
      return propertyGet(recv, site, p, span);
    }
    FieldSymbol f = lookup.findField(site, name);
    if (f != null && !hiddenByAccessor(f, site, lookup.findGetter(site, name))) {
      return fieldGet(recv, site, f, span);
    }
    MethodSymbol acc = lookup.findRecordAccessor(site, name);
    if (acc != null) {
      return beanGet(recv, site, acc, span);
    }
    MethodSymbol getter = lookup.findGetter(site, name);
    if (getter != null) {
      return beanGet(recv, site, getter, span);
    }
    reportNoMember(site, name, nameSpan);
    return new BExpr.Error(Type.ErrorType.INSTANCE, span);
  }

  void reportNoMember(Type site, String name, Span span) {
    Diagnostic.Builder d =
        err(
            Code.UNRESOLVED_MEMBER,
            span,
            site.withNullness(Nullness.NON_NULL).display() + " has no member '" + name + "'");
    String guess = Suggestions.closest(name, lookup.memberNames(site));
    if (guess != null) {
      d.help("did you mean '" + guess + "'?");
    }
    report(d);
  }

  /** Member access on a nullable receiver requires {@code ?.} or a check. */
  void checkReceiverNullness(BExpr recv, Span at) {
    Type t = recv.type();
    if (t instanceof Type.NullType) {
      error(Code.NULLABLE_RECEIVER, at, "member access on 'null'");
      return;
    }
    if (t.isReference() && t.nullness() == Nullness.NULLABLE) {
      if (recv instanceof BExpr.Local l && l.var().isAtomic()) {
        String n = l.var().name();
        report(
            err(Code.NULLABLE_RECEIVER, at, "value of type " + t.display() + " may be null")
                .note("'" + n + "' is atomic, so another thread may change it after a null check")
                .help("check a copy: 'val current = " + n + "; if (current != null) ...'"));
        return;
      }
      report(
          err(Code.NULLABLE_RECEIVER, at, "value of type " + t.display() + " may be null")
              .help(
                  "use '?.' for a null-safe access, '!' to assert non-null, or check for null first"));
    } else if (t.isReference()
        && t.nullness() == Nullness.PLATFORM
        && ctx.options.strictPlatformNullness()
        && !isSpeculative()) {
      warn(
          Code.PLATFORM_NULLNESS,
          at,
          "value of Java type " + t.display() + " has unknown nullness");
    }
  }

  /** Static member {@code Type.name}: field, enum constant, static property. */
  private BExpr staticMember(TypeTarget tt, Expr.Member m) {
    BExpr v = staticMemberValue(tt, m);
    if (v != null) {
      return v;
    }
    Type site = tt.type();
    if (lookup.findProperty(site, m.name()) != null || lookup.findField(site, m.name()) != null) {
      error(
          Code.STATIC_CONTEXT,
          m.nameSpan(),
          "'"
              + m.name()
              + "' is an instance member of "
              + site.display()
              + "; access it through an instance");
    } else {
      reportNoMember(site, m.name(), m.nameSpan());
    }
    return new BExpr.Error(Type.ErrorType.INSTANCE, m.span());
  }

  private BExpr staticMemberValue(TypeTarget tt, Expr.Member m) {
    Type site = tt.type();
    if (!(site instanceof ClassType)) {
      return null;
    }
    PropertySymbol p = lookup.findProperty(site, m.name());
    if (p != null && p.isStatic()) {
      return propertyGet(null, site, p, m.span());
    }
    FieldSymbol f = lookup.findField(site, m.name());
    if (f != null && f.isStatic()) {
      return fieldGet(null, site, f, m.span());
    }
    return null;
  }

  private BExpr superMember(SuperTarget s, Expr.Member m) {
    BExpr recv = new BExpr.This(s.superType(), s.span());
    PropertySymbol p = lookup.findProperty(s.superType(), m.name());
    if (p != null && p.getter() != null) {
      Type t = memberType(s.superType(), p.owner(), p.type());
      if (p.getter().isAbstract()) {
        error(
            Code.INVALID_THIS,
            m.span(),
            "cannot access abstract property '" + m.name() + "' through super");
      }
      return new BExpr.Call(recv, p.getter(), List.of(), BExpr.CallKind.SPECIAL, t, m.span());
    }
    FieldSymbol f = lookup.findField(s.superType(), m.name());
    if (f != null) {
      return fieldGet(recv, s.superType(), f, m.span());
    }
    MethodSymbol g = lookup.findGetter(s.superType(), m.name());
    if (g != null) {
      return new BExpr.Call(
          recv,
          g,
          List.of(),
          BExpr.CallKind.SPECIAL,
          memberType(s.superType(), g.owner(), g.returnType()),
          m.span());
    }
    reportNoMember(s.superType(), m.name(), m.nameSpan());
    return new BExpr.Error(Type.ErrorType.INSTANCE, m.span());
  }

  private BExpr memberExpr(Expr.Member m) {
    if (m.nullSafe()) {
      return safeAccess(m.target(), m.span(), tmp -> member(tmp, m.name(), m.nameSpan(), m.span()));
    }
    return asValue(target(m, false), m.span());
  }

  /**
   * {@code recv?.rest}: evaluates the receiver once; null if it is null, else {@code rest} applied
   * to the non-null receiver.
   */
  BExpr safeAccess(Expr receiverExpr, Span span, java.util.function.Function<BExpr, BExpr> rest) {
    BExpr recv = value(receiverExpr, null);
    if (recv.type().isError()) {
      return recv;
    }
    if (recv.type() instanceof PrimType) {
      error(
          Code.BAD_OPERANDS,
          span,
          "'?.' cannot be applied to primitive type " + recv.type().display());
      return rest.apply(recv);
    }
    if (recv.type().nullness() == Nullness.NON_NULL
        && !(recv.type() instanceof Type.NullType)
        && !isSpeculative()) {
      // Allowed but pointless; behaves like '.'.
      warn(
          Code.REDUNDANT_NON_NULL_ASSERTION,
          span,
          "'?.' on a non-null value of type "
              + recv.type().display()
              + " always takes the non-null path");
    }
    VarSymbol tmp =
        env.newVar(
            "$safe",
            recv.type().withNullness(Nullness.NON_NULL),
            Flags.FINAL | Flags.SYNTHETIC,
            VarSymbol.Kind.LOCAL,
            span);
    env.flow.assigned.set(tmp.id());
    BExpr whenPresent = rest.apply(new BExpr.Local(tmp, span));
    Type t = whenPresent.type();
    Type result;
    if (t == PrimType.VOID) {
      result = PrimType.VOID;
    } else if (t instanceof PrimType p) {
      whenPresent = coerce(whenPresent, syms.boxed(p), span);
      result = syms.boxed(p).withNullness(Nullness.NULLABLE);
    } else {
      result = t.withNullness(Nullness.NULLABLE);
    }
    return new BExpr.SafeAccess(recv, tmp, whenPresent, result, span);
  }

  // ------------------------------------------------------------------ this / typeof / nameof

  private BExpr thisExpr(Expr.This t) {
    if (t.qualifier() == null) {
      return thisValue(t.span());
    }
    for (ClassSymbol c = env.cls; c != null; c = c.outer()) {
      if (c.name().equals(t.qualifier()) || c.qualifiedName().equals(t.qualifier())) {
        if (c == env.cls) {
          return thisValue(t.span());
        }
        if (env.isStatic) {
          error(
              Code.STATIC_CONTEXT,
              t.span(),
              "'" + t.qualifier() + ".this' is not available in a static context");
          return new BExpr.Error(Type.ErrorType.INSTANCE, t.span());
        }
        noteThisUse();
        return new BExpr.OuterThis(c, c.thisType(), t.span());
      }
      if (!c.has(Flags.LOCAL) && !c.has(Flags.ANONYMOUS) || c.has(Flags.STATIC)) {
        break;
      }
    }
    error(
        Code.INVALID_THIS,
        t.span(),
        "'" + t.qualifier() + "' is not an enclosing class with an instance here");
    return new BExpr.Error(Type.ErrorType.INSTANCE, t.span());
  }

  private BExpr typeOf(Expr.TypeOf t) {
    Type ty = resolveType(t.type(), TypeResolver.RawMode.WILDCARD);
    if (ty.isError()) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, t.span());
    }
    if (ty instanceof Type.TypeVar) {
      error(
          Code.NOT_A_TYPE,
          t.span(),
          "typeof cannot be applied to type parameter " + ty.display() + " (generics are erased)");
      return new BExpr.Error(Type.ErrorType.INSTANCE, t.span());
    }
    Type arg =
        ty instanceof PrimType p
            ? (p == PrimType.VOID ? syms.wellKnown("java/lang/Void") : syms.boxed(p))
            : ty.withNullness(Nullness.NON_NULL);
    return new BExpr.ClassLit(ty.withNullness(Nullness.NON_NULL), syms.classType(arg), t.span());
  }

  private BExpr nameOf(Expr.NameOf n) {
    String name =
        switch (n.expr()) {
          case Expr.Name nm -> nm.name();
          case Expr.Member mm -> mm.name();
          default -> null;
        };
    if (name == null) {
      error(
          Code.UNRESOLVED_NAME,
          n.span(),
          "nameof needs a name, e.g. nameof(x) or nameof(obj.member)");
      return new BExpr.Error(syms.stringType(), n.span());
    }
    // Resolve to validate the name (types and methods are allowed too).
    speculate(() -> target(n.expr(), true));
    return new BExpr.Const(name, syms.stringType(), n.span());
  }

  // ------------------------------------------------------------------ casts, as

  private BExpr cast(Expr.Cast c) {
    Type target = resolveType(c.type(), TypeResolver.RawMode.WILDCARD);
    BExpr e = value(c.expr(), target);
    if (target.isError() || e.type().isError()) {
      return new BExpr.Error(target, c.span());
    }
    return castTo(e, target, c.span());
  }

  BExpr castTo(BExpr e, Type target, Span span) {
    Type from = e.type();
    if (!types.isCastable(from, target)) {
      error(Code.INVALID_CAST, span, "cannot cast " + from.display() + " to " + target.display());
      return new BExpr.Error(target, span);
    }
    if (target instanceof PrimType tp) {
      if (from instanceof PrimType) {
        return primConv(e, tp, span);
      }
      PrimType u = types.unboxedType(from);
      if (u == null) {
        // Object -> int: checkcast Integer, then unbox.
        ClassType box = syms.boxed(tp);
        e = new BExpr.Conv(e, ConvKind.CHECKCAST, box, span);
        u = tp;
      }
      if (from.nullness() == Nullness.NULLABLE) {
        report(
            err(
                    Code.NULLABILITY_MISMATCH,
                    span,
                    "cannot cast nullable " + from.display() + " to " + tp.display())
                .help("use '!' or '??' first"));
      }
      BExpr r = new BExpr.Conv(e, ConvKind.UNBOX, u, span);
      return u == tp ? r : primConv(r, tp, span);
    }
    if (from instanceof PrimType fp) {
      return coerce(e, target, span);
    }
    BExpr r = e;
    boolean needCheck =
        !types.isSubtype(from, target)
            || !types.isSameType(from.erasure(), target.erasure())
                && !types.isSubtype(from.erasure(), target.erasure());
    if (needCheck) {
      r = new BExpr.Conv(e, ConvKind.CHECKCAST, target, span);
    } else {
      r = new BExpr.Conv(e, ConvKind.RETYPE, target, span);
    }
    if (target.isReference()
        && target.nullness() == Nullness.NON_NULL
        && from.nullness() != Nullness.NON_NULL
        && !(from instanceof Type.NullType)) {
      r = new BExpr.Conv(r, ConvKind.NON_NULL_ASSERT, target, span);
    }
    return r;
  }

  private BExpr asExpr(Expr.As a) {
    Type target = resolveType(a.type(), TypeResolver.RawMode.WILDCARD);
    BExpr e = value(a.expr(), null);
    if (target.isError() || e.type().isError()) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, a.span());
    }
    if (target instanceof PrimType p) {
      report(
          err(Code.INVALID_CAST, a.span(), "'as' needs a reference type")
              .help("use 'as " + syms.boxed(p).display() + "?' or a cast"));
      return new BExpr.Error(Type.ErrorType.INSTANCE, a.span());
    }
    if (e.type() instanceof PrimType p) {
      e = coerce(e, syms.boxed(p), a.span());
    }
    if (!types.isCastable(e.type(), target)) {
      error(
          Code.INVALID_CAST,
          a.span(),
          "a value of type " + e.type().display() + " can never be " + target.display());
      return new BExpr.Error(target.withNullness(Nullness.NULLABLE), a.span());
    }
    Type result = target.withNullness(Nullness.NULLABLE);
    VarSymbol tmp =
        env.newVar("$as", e.type(), Flags.FINAL | Flags.SYNTHETIC, VarSymbol.Kind.LOCAL, a.span());
    BExpr test =
        new BExpr.InstanceOf(
            new BExpr.Local(tmp, a.span()), target.erasure(), PrimType.BOOLEAN, a.span());
    BExpr yes =
        new BExpr.Conv(new BExpr.Local(tmp, a.span()), ConvKind.CHECKCAST, result, a.span());
    BExpr no = new BExpr.Const(null, Type.NullType.INSTANCE, a.span());
    return new BExpr.Let(tmp, e, new BExpr.Conditional(test, yes, no, result, a.span()), a.span());
  }

  // ------------------------------------------------------------------ conditional & conditions

  /** Flow states after a condition evaluates to true / false. */
  FlowState whenTrue;

  FlowState whenFalse;

  /**
   * Attributes a boolean condition, leaving the true/false flow states in {@link #whenTrue} and
   * {@link #whenFalse} (smart casts and definite assignment through {@code &&}, {@code ||}, {@code
   * !}, null checks and type tests).
   */
  BExpr condition(Expr e) {
    switch (e) {
      case Expr.Paren p -> {
        return condition(p.expr());
      }
      case Expr.Unary u when u.op() == Expr.UnaryOp.NOT -> {
        BExpr inner = condition(u.operand());
        FlowState t = whenTrue;
        whenTrue = whenFalse;
        whenFalse = t;
        Object k = ConstFold.valueOf(inner);
        if (k instanceof Boolean b) {
          return new BExpr.Const(!b, PrimType.BOOLEAN, u.span());
        }
        return new BExpr.Unary(BExpr.UnOp.NOT, inner, PrimType.BOOLEAN, u.span());
      }
      case Expr.Binary b when b.op() == Expr.BinaryOp.AND || b.op() == Expr.BinaryOp.OR -> {
        boolean and = b.op() == Expr.BinaryOp.AND;
        BExpr left = condition(b.left());
        FlowState lt = whenTrue;
        FlowState lf = whenFalse;
        env.flow.set(and ? lt : lf);
        BExpr right = condition(b.right());
        FlowState rt = whenTrue;
        FlowState rf = whenFalse;
        if (and) {
          whenTrue = rt;
          FlowState f = lf.copy();
          f.join(rf);
          whenFalse = f;
        } else {
          FlowState t = lt.copy();
          t.join(rt);
          whenTrue = t;
          whenFalse = rf;
        }
        env.flow.set(whenTrue.copy());
        env.flow.join(whenFalse);
        return foldBool(
            new BExpr.Binary(
                and ? BExpr.BinOp.COND_AND : BExpr.BinOp.COND_OR,
                left,
                right,
                PrimType.BOOLEAN,
                false,
                b.span()));
      }
      default -> {}
    }
    BExpr r = expr(e, PrimType.BOOLEAN);
    // `is` patterns and null checks set whenTrue/whenFalse themselves via narrowCondition.
    if (pendingNarrow != null && pendingNarrow.expr == r) {
      PendingNarrow p = pendingNarrow;
      pendingNarrow = null;
      whenTrue = p.whenTrue;
      whenFalse = p.whenFalse;
    } else {
      whenTrue = env.flow.copy();
      whenFalse = env.flow.copy();
    }
    r = coerceCondition(r, e.span());
    Object k = ConstFold.valueOf(r);
    if (k instanceof Boolean bv) {
      if (bv) {
        whenFalse.alive = false;
      } else {
        whenTrue.alive = false;
      }
    }
    return r;
  }

  private BExpr foldBool(BExpr.Binary b) {
    Object l = ConstFold.valueOf(b.left());
    Object r = ConstFold.valueOf(b.right());
    if (l instanceof Boolean lb && r instanceof Boolean rb) {
      return new BExpr.Const(
          b.op() == BExpr.BinOp.COND_AND ? lb && rb : lb || rb, PrimType.BOOLEAN, b.span());
    }
    return b;
  }

  /** Flow facts produced by a null check or type test, consumed by {@link #condition}. */
  static final class PendingNarrow {
    BExpr expr;
    FlowState whenTrue;
    FlowState whenFalse;
  }

  PendingNarrow pendingNarrow;

  /** Records the true/false flow states for the boolean expression just built. */
  void narrowCondition(BExpr expr, FlowState t, FlowState f) {
    PendingNarrow p = new PendingNarrow();
    p.expr = expr;
    p.whenTrue = t;
    p.whenFalse = f;
    pendingNarrow = p;
  }

  /** The local variable an expression reads directly (possibly smart-cast), or null. */
  static VarSymbol localOf(BExpr e) {
    return switch (e) {
      case BExpr.Local l -> l.var();
      case BExpr.Conv c when c.kind() == ConvKind.CHECKCAST || c.kind() == ConvKind.RETYPE ->
          localOf(c.expr());
      default -> null;
    };
  }

  private BExpr conditional(Expr.Conditional c, Type pt) {
    BExpr cond = condition(c.cond());
    FlowState t = whenTrue;
    FlowState f = whenFalse;
    env.flow.set(t);
    BExpr then = value(c.then(), pt);
    FlowState afterThen = env.flow.copy();
    env.flow.set(f);
    BExpr otherwise = value(c.otherwise(), pt);
    FlowState afterElse = env.flow.copy();
    env.flow.set(afterThen);
    env.flow.join(afterElse);
    Type type;
    if (pt != null
        && pt != PrimType.VOID
        && !(pt instanceof Type.TypeVar tv && tv.sym().isCaptured())) {
      type = pt;
      then = coerce(then, pt, c.then().span());
      otherwise = coerce(otherwise, pt, c.otherwise().span());
    } else {
      type = types.lub(List.of(then.type(), otherwise.type()));
      if (type instanceof Type.NeverType) {
        type = Type.NeverType.INSTANCE;
      } else {
        then = coerce(then, type, c.then().span());
        otherwise = coerce(otherwise, type, c.otherwise().span());
      }
    }
    Object k = ConstFold.valueOf(cond);
    if (k instanceof Boolean b && ConstFold.isConst(then) && ConstFold.isConst(otherwise)) {
      return b ? then : otherwise;
    }
    return new BExpr.Conditional(cond, then, otherwise, type, c.span());
  }

  // ------------------------------------------------------------------ indexing

  private BExpr index(Expr.Index i) {
    if (i.nullSafe()) {
      return safeAccess(i.target(), i.span(), recv -> indexOn(recv, i));
    }
    BExpr recv = value(i.target(), null);
    return indexOn(recv, i);
  }

  BExpr indexOn(BExpr recv, Expr.Index i) {
    if (recv.type().isError()) {
      value(i.index(), null);
      return new BExpr.Error(Type.ErrorType.INSTANCE, i.span());
    }
    checkReceiverNullness(recv, i.target().span());
    if (i.index() instanceof Expr.Range
        || (i.index() instanceof Expr.Unary u && u.op() == Expr.UnaryOp.FROM_END)) {
      return patterns.rangeIndex(recv, i);
    }
    Type t = recv.type();
    if (t instanceof Type.ArrayType a) {
      BExpr idx = exprCoerced(i.index(), PrimType.INT);
      return new BExpr.ArrayElem(recv, idx, a.elem(), i.span());
    }
    if (t instanceof ClassType && types.isSubclassOf(t, "java/lang/String")) {
      BExpr idx = exprCoerced(i.index(), PrimType.INT);
      MethodSymbol charAt = syms.stringType().sym().methods("charAt").getFirst();
      return new BExpr.Call(
          recv, charAt, List.of(idx), BExpr.CallKind.VIRTUAL, PrimType.CHAR, i.span());
    }
    IndexerAccess acc = indexer(recv, i, false);
    if (acc == null) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, i.span());
    }
    return new BExpr.Call(
        recv,
        acc.getter,
        List.of(acc.index),
        callKind(acc.getter, recv, false),
        acc.type,
        i.span());
  }

  /** {@code list[i]} / {@code map[k]}: resolved getter (and setter for writes). */
  record IndexerAccess(MethodSymbol getter, MethodSymbol setter, BExpr index, Type type) {}

  IndexerAccess indexer(BExpr recv, Expr.Index i, boolean forWrite) {
    Type site = recv.type();
    ClassSymbol list = syms.lookup("java/util/List");
    ClassSymbol map = syms.lookup("java/util/Map");
    MethodSymbol getter = null;
    MethodSymbol setter = null;
    BExpr idx;
    ClassType asMap = types.asSuper(site, map);
    if (types.asSuper(site, list) != null) {
      idx = exprCoerced(i.index(), PrimType.INT);
      getter = find(site, "get", 1);
      setter = find(site, "set", 2);
    } else if (asMap != null) {
      Type keyType =
          asMap.args().isEmpty()
              ? syms.objectType()
              : Types.subst(asMap.args().getFirst(), Map.of());
      if (keyType instanceof Type.WildcardType) {
        keyType = syms.objectType();
      }
      idx = exprCoerced(i.index(), keyType.withNullness(Nullness.NULLABLE));
      getter = find(site, "get", 1);
      setter = find(site, "put", 2);
    } else {
      getter = find(site, "get", 1);
      setter = find(site, "set", 2);
      if (getter == null) {
        error(Code.BAD_OPERANDS, i.span(), "type " + site.display() + " cannot be indexed");
        value(i.index(), null);
        return null;
      }
      Type pt = memberType(site, getter.owner(), getter.params().getFirst().type());
      idx = exprCoerced(i.index(), pt);
    }
    if (forWrite && setter == null) {
      error(
          Code.NOT_ASSIGNABLE,
          i.span(),
          "type " + site.display() + " does not support indexed assignment");
      return null;
    }
    Type elem = types.uncapture(memberType(captureSite(site), getter.owner(), getter.returnType()));
    if (asMap != null) {
      elem = elem.withNullness(Nullness.NULLABLE); // Map.get returns null for missing keys
    }
    return new IndexerAccess(getter, setter, idx, elem);
  }

  private MethodSymbol find(Type site, String name, int arity) {
    for (MethodSymbol m : lookup.findMethods(site, name)) {
      if (m.params().size() == arity && !m.isStatic()) {
        if (arity == 1
            && name.equals("get")
            && m.params().getFirst().type() instanceof PrimType p
            && p != PrimType.INT) {
          continue;
        }
        return m;
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ object creation

  private BExpr newExpr(Expr.New n, Type pt) {
    Type t;
    if (n.type() == null) {
      if (pt == null || pt.isError()) {
        if (pt == null) {
          report(
              err(Code.CANNOT_INFER, n.span(), "cannot infer the type of 'new()' here")
                  .help("write the type: new SomeType(...)"));
        }
        return new BExpr.Error(Type.ErrorType.INSTANCE, n.span());
      }
      t = pt.withNullness(Nullness.NON_NULL);
      if (t instanceof ClassType ct
          && ct.args().stream().anyMatch(a -> a instanceof Type.WildcardType)) {
        t = types.nonWildcard(ct);
      }
      if (t instanceof ClassType ict && (ict.sym().isInterface() || ict.sym().isAbstract())) {
        ClassSymbol impl = defaultImplementation(ict.sym());
        if (impl != null) {
          t = new ClassType(impl, ict.args(), Nullness.NON_NULL);
        }
      }
      if (!(t instanceof ClassType)) {
        error(
            Code.TYPE_MISMATCH, n.span(), "'new()' cannot create a value of type " + pt.display());
        return new BExpr.Error(Type.ErrorType.INSTANCE, n.span());
      }
    } else {
      t = resolveType(n.type(), TypeResolver.RawMode.INFER);
      if (t.isError()) {
        if (n.args() != null) {
          for (var a : n.args()) {
            speculate(() -> value(a.value(), null));
          }
        }
        return new BExpr.Error(Type.ErrorType.INSTANCE, n.span());
      }
      if (!(t instanceof ClassType)) {
        error(Code.TYPE_MISMATCH, n.type().span(), "cannot instantiate " + t.display());
        return new BExpr.Error(Type.ErrorType.INSTANCE, n.span());
      }
    }
    ClassType ct = (ClassType) t;
    ClassSymbol c = ct.sym();
    if (n.anonBody() != null) {
      return patterns.anonymousClass(n, ct, pt);
    }
    if (c.isAbstract() || c.isInterface()) {
      Diagnostic.Builder d =
          err(
              Code.ABSTRACT_INSTANTIATION,
              n.span(),
              "cannot create an instance of "
                  + (c.isInterface() ? "interface " : "abstract class ")
                  + c.displayName());
      if (c.isInterface()) {
        d.help(
            "implement it with a class, an anonymous class 'new "
                + c.name()
                + "() { ... }', or a lambda");
      }
      report(d);
      return new BExpr.Error(ct, n.span());
    }
    if (c.isEnum()) {
      error(
          Code.ABSTRACT_INSTANTIATION,
          n.span(),
          "enum " + c.displayName() + " cannot be instantiated; use one of its constants");
      return new BExpr.Error(ct, n.span());
    }
    BExpr created =
        calls.construct(
            ct,
            n.args() == null ? List.of() : n.args(),
            pt,
            n.span(),
            n.type() == null || isDiamondOrRaw(n.type(), c));
    if (n.init() != null) {
      return objectInit(created, n.init(), n.span());
    }
    checkRequiredMembers(created.type(), List.of(), n.span());
    return created;
  }

  /**
   * The class {@code new()} instantiates for a collection interface target (spec 3.4's {@code
   * List<String> xs = new();}): ArrayList, HashMap, HashSet, ArrayDeque, TreeMap, TreeSet.
   */
  private ClassSymbol defaultImplementation(ClassSymbol iface) {
    String impl =
        switch (iface.binaryName()) {
          case "java/util/List",
              "java/util/Collection",
              "java/lang/Iterable",
              "java/util/SequencedCollection" ->
              "java/util/ArrayList";
          case "java/util/Map" -> "java/util/HashMap";
          case "java/util/SequencedMap" -> "java/util/LinkedHashMap";
          case "java/util/Set" -> "java/util/HashSet";
          case "java/util/SequencedSet" -> "java/util/LinkedHashSet";
          case "java/util/Queue", "java/util/Deque" -> "java/util/ArrayDeque";
          case "java/util/SortedMap", "java/util/NavigableMap" -> "java/util/TreeMap";
          case "java/util/SortedSet", "java/util/NavigableSet" -> "java/util/TreeSet";
          case "java/util/concurrent/ConcurrentMap" -> "java/util/concurrent/ConcurrentHashMap";
          default -> null;
        };
    return impl == null ? null : syms.lookup(impl);
  }

  private static boolean isDiamondOrRaw(TypeNode tn, ClassSymbol c) {
    if (c.typeParams().isEmpty()) {
      return false;
    }
    TypeNode inner = tn;
    if (inner instanceof TypeNode.Named named) {
      TypeNode.Segment last = named.last();
      return last.diamond() || last.typeArgs().isEmpty();
    }
    return false;
  }

  BExpr objectInit(BExpr created, List<FieldInit> inits, Span span) {
    Type site = created.type();
    if (site.isError()) {
      for (FieldInit fi : inits) {
        value(fi.value(), null);
      }
      return created;
    }
    List<BExpr.MemberInit> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (FieldInit fi : inits) {
      if (!seen.add(fi.name())) {
        error(Code.DUPLICATE_MEMBER, fi.nameSpan(), "'" + fi.name() + "' is initialized twice");
      }
      PropertySymbol p = lookup.findProperty(site, fi.name());
      if (p != null) {
        if (p.setter() == null) {
          error(Code.NOT_ASSIGNABLE, fi.nameSpan(), "property '" + fi.name() + "' is read-only");
          value(fi.value(), null);
          continue;
        }
        checkAccess(p.setter(), p.owner(), site, fi.nameSpan());
        Type pt = memberType(captureSite(site), p.owner(), p.type());
        out.add(new BExpr.MemberInit(p, exprCoerced(fi.value(), pt, fi.value().span())));
        continue;
      }
      FieldSymbol f = lookup.findField(site, fi.name());
      if (f != null && hiddenByAccessor(f, site, lookup.findSetter(site, fi.name()))) {
        f = null; // a property of a compiled J# class: its private field yields to the setter
      }
      if (f != null && !f.isStatic()) {
        checkAccess(f, f.owner(), site, fi.nameSpan());
        if (f.has(Flags.FINAL)) {
          error(Code.NOT_ASSIGNABLE, fi.nameSpan(), "field '" + fi.name() + "' is final");
        }
        out.add(
            new BExpr.MemberInit(
                f,
                exprCoerced(fi.value(), memberType(site, f.owner(), f.type()), fi.value().span())));
        continue;
      }
      MethodSymbol setter = lookup.findSetter(site, fi.name());
      if (setter != null) {
        checkAccess(setter, setter.owner(), site, fi.nameSpan());
        Type pt = memberType(captureSite(site), setter.owner(), setter.params().getFirst().type());
        out.add(new BExpr.MemberInit(setter, exprCoerced(fi.value(), pt, fi.value().span())));
        continue;
      }
      reportNoMember(site, fi.name(), fi.nameSpan());
      value(fi.value(), null);
    }
    checkRequiredMembers(site, new ArrayList<>(seen), span);
    return new BExpr.ObjectInit(created, out, site, span);
  }

  /** Every {@code required} property/field of the created type must be set by the initializer. */
  void checkRequiredMembers(Type site, List<String> initialized, Span span) {
    if (!(site instanceof ClassType ct) || !ct.sym().isSource() && !ct.sym().has(Flags.JSHARP)) {
      return;
    }
    List<String> missing = new ArrayList<>();
    for (ClassSymbol c : lookup.hierarchy(site)) {
      for (PropertySymbol p : c.properties()) {
        if (p.isRequired() && !initialized.contains(p.name())) {
          missing.add(p.name());
        }
      }
      for (FieldSymbol f : c.fields()) {
        if (f.has(Flags.REQUIRED)
            && !f.has(Flags.BACKING_FIELD)
            && !initialized.contains(f.name())) {
          missing.add(f.name());
        }
      }
    }
    if (!missing.isEmpty()) {
      report(
          err(
                  Code.REQUIRED_MEMBER_MISSING,
                  span,
                  "required member"
                      + (missing.size() > 1 ? "s " : " ")
                      + String.join(", ", missing.stream().map(m -> "'" + m + "'").toList())
                      + " must be set")
              .help(
                  "add an object initializer: new "
                      + ct.sym().name()
                      + "(...) { "
                      + missing.getFirst()
                      + " = ... }"));
    }
  }

  // ------------------------------------------------------------------ arrays

  private BExpr newArray(Expr.NewArray n) {
    Type elem = resolveType(n.elementType());
    if (elem.isError()) {
      for (Expr d : n.dims()) {
        value(d, null);
      }
      return new BExpr.Error(Type.ErrorType.INSTANCE, n.span());
    }
    int rank = n.dims().size() + n.extraDims() + (n.init() != null ? 1 : 0);
    Type t = elem;
    for (int i = 0; i < rank; i++) {
      t = Type.ArrayType.of(t);
    }
    if (elem instanceof Type.TypeVar) {
      error(Code.TYPE_MISMATCH, n.span(), "cannot create a generic array of " + elem.display());
    }
    if (n.init() != null) {
      return arrayInit(n.init(), t);
    }
    List<BExpr> dims = new ArrayList<>();
    for (Expr d : n.dims()) {
      dims.add(exprCoerced(d, PrimType.INT));
    }
    return new BExpr.NewArray((Type.ArrayType) t, dims, null, n.span());
  }

  private BExpr arrayInit(Expr.ArrayInit a, Type pt) {
    if (a.elements().isEmpty() && pt instanceof ClassType ct && !(pt instanceof Type.ArrayType)) {
      // `{}` for a Map target is an empty map literal.
      ClassSymbol mapSym = syms.lookup("java/util/Map");
      if (mapSym != null && types.isSubtype(ct.erasure(), mapSym.thisType().erasure())) {
        return literals.map(new Expr.MapLiteral(List.of(), a.span()), pt);
      }
    }
    if (!(pt instanceof Type.ArrayType at)) {
      report(
          err(Code.CANNOT_INFER, a.span(), "an array initializer '{...}' needs an array type here")
              .help(
                  a.elements().isEmpty()
                      ? "write new T[] {} for an array, or give a map type: Map<K, V> m = {};"
                      : "write new T[] { ... }, or use a list: [a, b]"));
      for (Expr e : a.elements()) {
        value(e, null);
      }
      return new BExpr.Error(Type.ErrorType.INSTANCE, a.span());
    }
    List<BExpr> elems = new ArrayList<>();
    for (Expr e : a.elements()) {
      elems.add(exprCoerced(e, at.elem()));
    }
    return new BExpr.NewArray(
        (Type.ArrayType) at.withNullness(Nullness.NON_NULL), List.of(), elems, a.span());
  }

  // ------------------------------------------------------------------ lambdas

  /** The functional interface type a lambda/method reference converts to, or null (reported). */
  ClassType functionalTarget(Type pt, Span span, String what) {
    if (pt == null) {
      report(
          err(Code.LAMBDA_MISMATCH, span, "cannot infer a function type for this " + what)
              .help("declare the variable's type, e.g. 'Function<int, int> f = x => x + 1;'"));
      return null;
    }
    if (pt.isError()) {
      return null;
    }
    if (pt instanceof ClassType ct && types.findSam(ct.sym()) != null) {
      return ct;
    }
    error(
        Code.LAMBDA_MISMATCH,
        span,
        "a "
            + what
            + " needs a functional interface type, but the expected type is "
            + pt.display());
    return null;
  }

  BExpr lambda(Expr.Lambda l, Type pt) {
    ClassType fi = functionalTarget(pt, l.span(), "lambda");
    if (fi == null) {
      // Still check the body for errors with unknown parameter types.
      if (pt == null) {
        lambdaBody(l, null, null, null);
      }
      return new BExpr.Error(pt == null ? Type.ErrorType.INSTANCE : pt, l.span());
    }
    ClassType plain = types.nonWildcard(fi);
    Types.MethodType ft = types.functionType(plain);
    MethodSymbol sam = types.findSam(fi.sym());
    if (ft.params().size() != l.params().size()) {
      error(
          Code.LAMBDA_MISMATCH,
          l.span(),
          "lambda has "
              + l.params().size()
              + " parameter"
              + (l.params().size() == 1 ? "" : "s")
              + " but "
              + fi.display()
              + " expects "
              + ft.params().size());
      return new BExpr.Error(fi, l.span());
    }
    return lambdaBody(l, plain, sam, ft);
  }

  /**
   * Attributes a lambda body against a function type (or with unknown types if {@code ft} null).
   */
  BExpr lambdaBody(Expr.Lambda l, ClassType fi, MethodSymbol sam, Types.MethodType ft) {
    LambdaFrame frame = new LambdaFrame();
    Env outer = env;
    Env e = env.nested();
    e.scope = new Scope(env.scope, Scope.Boundary.LAMBDA, frame, null);
    e.lambda = frame;
    e.isAsync = l.isAsync();
    e.jumps = new java.util.ArrayDeque<>();
    e.inConstructor = false;
    e.catchVar = null;
    Type ret = ft == null ? null : ft.ret();
    boolean inferRet = ret != null && ret instanceof Type.TypeVar tv && tv.sym().owner() == null;
    if (l.isAsync()) {
      ret = patterns.asyncLambdaResult(ret, l.span());
    }
    e.returnType = ret;
    env = e;
    try {
      List<VarSymbol> params = new ArrayList<>();
      for (int i = 0; i < l.params().size(); i++) {
        Param p = l.params().get(i);
        Type pt = ft == null ? Type.ErrorType.INSTANCE : ft.params().get(i);
        if (p.type() != null) {
          Type declared = resolveType(p.type());
          if (ft != null
              && !declared.isError()
              && !types.isSameType(declared, pt)
              && !types.isSubtype(pt, declared)) {
            error(
                Code.LAMBDA_MISMATCH,
                p.span(),
                "lambda parameter '"
                    + p.name()
                    + "' is declared "
                    + declared.display()
                    + " but "
                    + pt.display()
                    + " is expected");
          }
          pt = declared.isError() ? pt : declared;
        }
        VarSymbol v = env.newVar(p.name(), pt, 0, VarSymbol.Kind.PARAM, p.nameSpan());
        if (!p.name().equals("_")) {
          if (env.scope.parent.lookupWithinBoundary(p.name()) != null
              || outer.scope.lookup(p.name()) != null && env.scope.lookup(p.name()) != null) {
            Scope.Found prev = outer.scope.lookup(p.name());
            if (prev != null) {
              error(
                  Code.DUPLICATE_VARIABLE,
                  p.nameSpan(),
                  "lambda parameter '" + p.name() + "' shadows a local variable");
            }
          }
          env.scope.vars.put(p.name(), v);
        }
        env.flow.assigned.set(v.id());
        params.add(v);
      }
      BStmt body;
      Type resultType = ret;
      switch (l.body()) {
        case Body.ExprBody eb -> {
          if (ret == PrimType.VOID) {
            BExpr v = expr(eb.expr(), null);
            if (!stmts.isStatementExpression(eb.expr())
                && !v.type().isError()
                && v.type() != PrimType.VOID) {
              error(
                  Code.LAMBDA_MISMATCH,
                  eb.expr().span(),
                  "this lambda must not return a value (" + fi.display() + " returns void)");
            }
            body = new BStmt.ExprStmt(v, eb.expr().span());
          } else if (ret == null || inferRet) {
            BExpr v = value(eb.expr(), null);
            frame.returnTypes.add(v.type());
            resultType = v.type();
            body = new BStmt.Return(v, eb.expr().span());
          } else {
            BExpr v = exprCoerced(eb.expr(), ret);
            body = new BStmt.Return(v, eb.expr().span());
          }
        }
        case Body.Block bb -> {
          if (inferRet) {
            env.returnType = null;
          }
          body = stmts.block(bb.block());
          if (env.flow.alive && env.returnType != null && env.returnType != PrimType.VOID) {
            error(
                Code.MISSING_RETURN,
                l.span(),
                "lambda must return a value of type "
                    + env.returnType.display()
                    + " on every path");
          }
          if (env.returnType == null) {
            resultType = frame.returnTypes.isEmpty() ? PrimType.VOID : types.lub(frame.returnTypes);
          }
        }
      }
      if (fi == null) {
        return new BExpr.Error(Type.ErrorType.INSTANCE, l.span());
      }
      return new BExpr.Lambda(
          fi,
          sam,
          params,
          body,
          resultType,
          new ArrayList<>(frame.captures),
          frame.capturesThis,
          l.span());
    } finally {
      env = outer;
      // Captured variables of the lambda are also captured by any enclosing lambdas/classes.
      for (VarSymbol v : frame.captures) {
        Scope.Found f = env.scope.lookup(v.name());
        if (f != null && f.var() == v) {
          for (Scope crossed : f.crossed()) {
            if (crossed.boundary == Scope.Boundary.LAMBDA) {
              crossed.lambda.captures.add(v);
            } else if (crossed.boundary == Scope.Boundary.CLASS) {
              localClassCaptures
                  .computeIfAbsent(crossed.localClass, k -> new LinkedHashSet<>())
                  .add(v);
            }
          }
        }
      }
      if (frame.capturesThis) {
        noteThisUse();
      }
    }
  }

  /** Result type of a lambda attributed against parameter types only (for inference). */
  Type lambdaResultType(Expr.Lambda l, List<Type> paramTypes, ClassType fi) {
    MethodSymbol sam = types.findSam(fi.sym());
    Types.MethodType ft = new Types.MethodType(paramTypes, null);
    Speculation<BExpr> s = speculate(() -> lambdaBody(l, fi, sam, ft));
    if (s.hasErrors() || !(s.result() instanceof BExpr.Lambda lam)) {
      return null;
    }
    return lam.returnType();
  }

  // ------------------------------------------------------------------ method references

  BExpr methodRef(Expr.MethodRef m, Type pt) {
    if (m.target() == null && m.typeTarget() == null) {
      BExpr mismatch = functionValueMismatch(m.name(), pt, m.span());
      if (mismatch != null) {
        return mismatch;
      }
    }
    ClassType fi = functionalTarget(pt, m.span(), "method reference");
    if (fi == null) {
      return new BExpr.Error(pt == null ? Type.ErrorType.INSTANCE : pt, m.span());
    }
    return calls.methodRef(m, types.nonWildcard(fi));
  }

  /**
   * A {@code java.util.function} type for the only function named {@code name} ({@code
   * Function<int, int>}, {@code Predicate<String>}, ...), or null if there is none or several.
   */
  private String functionTypeFor(String name) {
    LocalFunctions.Fn local = env.scope.lookupFunction(name);
    List<MethodSymbol> ms =
        local != null
            ? List.of(local.sym)
            : calls.functionGroup(name) == null ? List.of() : calls.functionGroup(name).methods();
    if (ms.size() != 1 || !ms.getFirst().typeParams().isEmpty()) {
      return null;
    }
    MethodSymbol m = ms.getFirst();
    List<String> ps = m.params().stream().map(p -> p.type().display()).toList();
    Type ret = m.returnType();
    if (ret == null) {
      return null;
    }
    boolean isVoid = ret == PrimType.VOID;
    boolean isBool = ret == PrimType.BOOLEAN;
    String r = ret.display();
    return switch (ps.size()) {
      case 0 -> isVoid ? "Runnable" : "Supplier<" + r + ">";
      case 1 ->
          isVoid
              ? "Consumer<" + ps.get(0) + ">"
              : isBool ? "Predicate<" + ps.get(0) + ">" : "Function<" + ps.get(0) + ", " + r + ">";
      case 2 -> {
        String two = ps.get(0) + ", " + ps.get(1);
        yield isVoid
            ? "BiConsumer<" + two + ">"
            : isBool ? "BiPredicate<" + two + ">" : "BiFunction<" + two + ", " + r + ">";
      }
      default -> null;
    };
  }

  /**
   * The error for a function named without a call (D083) where no function type is expected, or
   * null when {@code pt} is a functional interface.
   */
  BExpr functionValueMismatch(String name, Type pt, Span span) {
    boolean functional =
        pt instanceof ClassType pct && types.findSam(pct.sym()) != null
            || pt != null && pt.isError();
    if (!functional) {
      Expr.MethodRef m = new Expr.MethodRef(null, null, name, span, span);
      if (pt == null) {
        String type = functionTypeFor(name);
        report(
            err(
                    Code.LAMBDA_MISMATCH,
                    m.span(),
                    "cannot infer a function type for '" + m.name() + "'")
                .help(
                    "declare the variable's type ('"
                        + (type != null ? type : "Function<int, int>")
                        + " f = "
                        + m.name()
                        + ";'), or call it: '"
                        + m.name()
                        + "(...)'"));
      } else {
        report(
            err(
                    Code.LAMBDA_MISMATCH,
                    m.span(),
                    "function '" + m.name() + "' is not a value of type " + pt.display())
                .help("call it: '" + m.name() + "(...)'"));
      }
      return new BExpr.Error(pt == null ? Type.ErrorType.INSTANCE : pt, m.span());
    }
    return null;
  }

  // ------------------------------------------------------------------ inferred member types

  /** The type of a field, attributing its initializer first if the type is inferred. */
  Type ensureFieldType(FieldSymbol f) {
    if (f.type() != null) {
      return f.type();
    }
    if (!inferring.add(f)) {
      Span s = f.declarator() != null ? f.declarator().nameSpan() : Span.NONE;
      withRealDiagnostics(
          () -> {
            Env saved = env;
            env = ClassChecker.fieldEnv(this, f);
            error(
                Code.RECURSIVE_INFERENCE,
                s,
                "cannot infer the type of '" + f.name() + "': its initializer depends on itself");
            env = saved;
            return null;
          });
      f.setType(Type.ErrorType.INSTANCE);
      return f.type();
    }
    try {
      withRealDiagnostics(
          () -> {
            Env saved = env;
            env = ClassChecker.fieldEnv(this, f);
            try {
              BExpr init = value(f.declarator().init(), null);
              Type t = init.type();
              if (t instanceof Type.NullType) {
                report(
                    err(
                            Code.CANNOT_INFER,
                            f.declarator().nameSpan(),
                            "cannot infer a type from 'null'")
                        .help("declare the type: 'Type? " + f.name() + " = null;'"));
                t = Type.ErrorType.INSTANCE;
              } else if (t instanceof Type.NeverType) {
                t = Type.ErrorType.INSTANCE;
              }
              t = types.uncapture(t);
              if (f.type() == null) {
                f.setType(t);
              }
              inferredFieldInits.put(f, init);
            } finally {
              env = saved;
            }
            return null;
          });
    } finally {
      inferring.remove(f);
    }
    return f.type();
  }

  /** Ensures an inferred-return-type method has its return type (attributing its body). */
  Type ensureReturnType(MethodSymbol m) {
    if (m.returnType() != null) {
      return m.returnType();
    }
    if (!inferring.add(m)) {
      withRealDiagnostics(
          () -> {
            Env saved = env;
            env = ClassChecker.methodEnv(this, m);
            report(
                err(
                    Code.RECURSIVE_INFERENCE,
                    ((io.github.matrixidot.jsharp.compiler.ast.Decl.Method) m.decl()).nameSpan(),
                    "recursive method '" + m.name() + "' needs an explicit return type"));
            env = saved;
            return null;
          });
      m.setReturnType(Type.ErrorType.INSTANCE);
      return m.returnType();
    }
    try {
      withRealDiagnostics(
          () -> {
            BClass.Method body = ClassChecker.attribMethodBody(this, m);
            inferredMethodBodies.put(m, body);
            return null;
          });
    } finally {
      inferring.remove(m);
    }
    return m.returnType();
  }

  /** Name of the class for diagnostics. */
  static String describe(TypeNode t) {
    return AstPrinter.typeStr(t);
  }

  BLValue lvalue(Expr target) {
    return ops.lvalue(target);
  }
}

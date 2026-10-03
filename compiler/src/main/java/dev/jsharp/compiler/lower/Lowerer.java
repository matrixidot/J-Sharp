package dev.jsharp.compiler.lower;

import dev.jsharp.compiler.Context;
import dev.jsharp.compiler.ast.Accessor;
import dev.jsharp.compiler.bound.BClass;
import dev.jsharp.compiler.bound.BExpr;
import dev.jsharp.compiler.bound.BExpr.BinOp;
import dev.jsharp.compiler.bound.BExpr.CallKind;
import dev.jsharp.compiler.bound.BExpr.ConvKind;
import dev.jsharp.compiler.bound.BLValue;
import dev.jsharp.compiler.bound.BPattern;
import dev.jsharp.compiler.bound.BStmt;
import dev.jsharp.compiler.bound.BSwitch;
import dev.jsharp.compiler.source.Span;
import dev.jsharp.compiler.symbols.ClassSymbol;
import dev.jsharp.compiler.symbols.FieldSymbol;
import dev.jsharp.compiler.symbols.Flags;
import dev.jsharp.compiler.symbols.MethodSymbol;
import dev.jsharp.compiler.symbols.PropertySymbol;
import dev.jsharp.compiler.symbols.Symbol;
import dev.jsharp.compiler.symbols.Symtab;
import dev.jsharp.compiler.symbols.VarSymbol;
import dev.jsharp.compiler.types.Descriptors;
import dev.jsharp.compiler.types.Nullness;
import dev.jsharp.compiler.types.Type;
import dev.jsharp.compiler.types.Type.ClassType;
import dev.jsharp.compiler.types.Type.PrimType;
import dev.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowers checked classes to the core subset understood by code generation: synthesizes generated
 * members (accessors, record and enum machinery, default constructors, bridges, default-argument
 * overloads), places initializers in constructors and {@code <clinit>}, lifts lambdas into
 * synthetic methods, threads captured variables into local classes, and desugars foreach, using,
 * patterns, switches, safe access, compound assignment, async bodies and checked arithmetic.
 */
public final class Lowerer {
  private final Context ctx;
  private final Types types;
  private final Symtab syms;
  private final Map<ClassSymbol, MethodSymbol> anonSuperCtors;
  private final List<BClass> output = new ArrayList<>();

  /** Synthetic fields of local classes: captured variables and the enclosing instance. */
  private final Map<ClassSymbol, Map<VarSymbol, FieldSymbol>> captureFields =
      new IdentityHashMap<>();

  private final Map<ClassSymbol, FieldSymbol> outerThisFields = new IdentityHashMap<>();
  private final Map<ClassSymbol, BClass> checkedByClass = new IdentityHashMap<>();

  /** Source-level parameters of constructors (lowering prepends synthetic ones). */
  private final Map<MethodSymbol, List<MethodSymbol.Param>> originalParams =
      new IdentityHashMap<>();

  // ---- per class / per method state
  private ClassSymbol cls;
  private List<BClass.Method> extraMethods;
  private int lambdaCounter;
  private MethodSymbol method;
  private Type returnType;
  private int tempCounter;

  /**
   * @param anonSuperCtors the superclass constructor each anonymous class constructor calls
   */
  public Lowerer(Context ctx, Types types, Map<ClassSymbol, MethodSymbol> anonSuperCtors) {
    this.ctx = ctx;
    this.types = types;
    this.syms = ctx.syms;
    this.anonSuperCtors = anonSuperCtors;
  }

  /** Lowers all classes (including nested local/anonymous ones, which are flattened). */
  public List<BClass> lowerAll(List<BClass> classes) {
    List<BClass> flat = new ArrayList<>();
    for (BClass c : classes) {
      flatten(c, flat);
    }
    for (BClass c : flat) {
      checkedByClass.put(c.sym(), c);
      prepareSyntheticFields(c);
      for (BClass.Method m : c.methods()) {
        if (m.sym().isConstructor()) {
          originalParams.put(m.sym(), m.sym().params());
        }
      }
    }
    for (BClass c : flat) {
      output.add(lowerClass(c));
    }
    return output;
  }

  private static void flatten(BClass c, List<BClass> out) {
    out.add(c);
    for (BClass n : c.nested()) {
      flatten(n, out);
    }
  }

  private void prepareSyntheticFields(BClass c) {
    ClassSymbol s = c.sym();
    if (c.capturesOuterThis() && s.outer() != null) {
      FieldSymbol f =
          new FieldSymbol("this$0", s, Flags.FINAL | Flags.SYNTHETIC, s.outer().thisType());
      s.addField(f);
      outerThisFields.put(s, f);
    }
    Map<VarSymbol, FieldSymbol> caps = new LinkedHashMap<>();
    for (VarSymbol v : c.captures()) {
      FieldSymbol f =
          new FieldSymbol(
              "val$" + v.name(), s, Flags.PRIVATE | Flags.FINAL | Flags.SYNTHETIC, v.type());
      s.addField(f);
      caps.put(v, f);
    }
    captureFields.put(s, caps);
  }

  // ------------------------------------------------------------------ classes

  private BClass lowerClass(BClass bc) {
    ClassSymbol saved = cls;
    cls = bc.sym();
    extraMethods = new ArrayList<>();
    lambdaCounter = 0;
    try {
      List<BClass.Method> methods = new ArrayList<>();
      // Instance initializers are lowered once and shared by every constructor.
      method = null;
      returnType = PrimType.VOID;
      List<BStmt> instanceInit = new ArrayList<>();
      for (BStmt s : bc.instanceInit()) {
        instanceInit.add(ls(s));
      }
      for (BClass.Method m : bc.methods()) {
        if (m.sym().isConstructor()) {
          methods.add(constructor(bc, m, instanceInit));
        } else {
          methods.add(method(bc, m));
        }
      }
      BClass.Method clinit = staticInitializer(bc);
      if (clinit != null) {
        methods.add(clinit);
      }
      if (cls.isRecord()) {
        recordObjectMethods(methods);
      }
      for (BClass.Bridge b : bc.bridges()) {
        methods.add(bridge(b));
      }
      defaultOverloads(methods);
      methods.addAll(extraMethods);
      return new BClass(
          cls,
          methods,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          bc.captures(),
          bc.capturesOuterThis());
    } finally {
      cls = saved;
    }
  }

  private void enterMethod(MethodSymbol m, Type ret) {
    method = m;
    returnType = ret;
    tempCounter = 0;
  }

  VarSymbol temp(String name, Type type, Span span) {
    return new VarSymbol(
        "$" + name + (tempCounter++),
        type,
        Flags.FINAL | Flags.SYNTHETIC,
        VarSymbol.Kind.LOCAL,
        span,
        -1);
  }

  private static Span spanOf(BClass.Method m) {
    return m.span() == null ? Span.NONE : m.span();
  }

  // ------------------------------------------------------------------ methods

  private BClass.Method method(BClass bc, BClass.Method m) {
    MethodSymbol s = m.sym();
    enterMethod(s, s.returnType());
    Span span = spanOf(m);
    if (s.has(Flags.ASYNC) && m.body() != null) {
      return new BClass.Method(s, m.params(), asyncBody(s, m.params(), m.body(), span), span);
    }
    BStmt body;
    if (m.body() != null) {
      body = ls(m.body());
    } else {
      body = generatedBody(bc, s, m.params(), span);
    }
    if (body != null) {
      body = withNullChecks(s, m.params(), body, span);
    }
    return new BClass.Method(s, m.params(), body, span);
  }

  /** Bodies of compiler-generated methods (property/record accessors, enum helpers). */
  private BStmt generatedBody(BClass bc, MethodSymbol s, List<VarSymbol> params, Span span) {
    if (s.isAbstract() || s.has(Flags.NATIVE)) {
      return null;
    }
    PropertySymbol p = s.property();
    if (p != null && p.backingField() != null) {
      FieldSymbol f = p.backingField();
      BExpr recv = f.isStatic() ? null : self(span);
      if (s == p.getter()) {
        return new BStmt.Return(new BExpr.Field(recv, f, f.type(), span), span);
      }
      if (s == p.setter()) {
        BExpr v = new BExpr.Local(params.getFirst(), span);
        return new BStmt.Block(
            List.of(
                new BStmt.ExprStmt(
                    new BExpr.Assign(new BLValue.FieldLV(recv, f, f.type()), v, f.type(), span),
                    span),
                new BStmt.Return(null, span)),
            span);
      }
    }
    if (cls.isEnum() && s.isStatic() && s.has(Flags.GENERATED)) {
      FieldSymbol values = enumValuesField();
      Type arr = Type.ArrayType.of(cls.thisType());
      if (s.name().equals("values")) {
        MethodSymbol clone = arrayClone();
        BExpr call =
            new BExpr.Call(
                new BExpr.Field(null, values, arr, span),
                clone,
                List.of(),
                CallKind.VIRTUAL,
                arr,
                span);
        return new BStmt.Return(call, span);
      }
      if (s.name().equals("valueOf")) {
        MethodSymbol valueOf = syms.wellKnown("java/lang/Enum").sym().methods("valueOf").getFirst();
        BExpr call =
            new BExpr.Call(
                null,
                valueOf,
                List.of(
                    new BExpr.ClassLit(cls.thisType(), syms.classType(cls.thisType()), span),
                    new BExpr.Local(params.getFirst(), span)),
                CallKind.STATIC,
                cls.thisType(),
                span);
        return new BStmt.Return(call, span);
      }
    }
    return null;
  }

  /** Array {@code clone()} (declared on Object, protected; arrays expose it publicly). */
  private MethodSymbol arrayClone() {
    MethodSymbol clone = new MethodSymbol("clone", syms.objectSym(), Flags.PUBLIC);
    clone.setReturnType(syms.objectType());
    return clone;
  }

  private FieldSymbol enumValuesField() {
    FieldSymbol f = cls.field("$VALUES");
    if (f == null) {
      f =
          new FieldSymbol(
              "$VALUES",
              cls,
              Flags.PRIVATE | Flags.STATIC | Flags.FINAL | Flags.SYNTHETIC,
              Type.ArrayType.of(cls.thisType()));
      cls.addField(f);
    }
    return f;
  }

  /**
   * {@code Objects.requireNonNull(param, "name")} for non-null reference parameters of public entry
   * points.
   */
  private BStmt withNullChecks(MethodSymbol s, List<VarSymbol> params, BStmt body, Span span) {
    if (!(Flags.is(s.flags(), Flags.PUBLIC) || Flags.is(s.flags(), Flags.PROTECTED))
        || s.has(Flags.SYNTHETIC)
        || s.has(Flags.BRIDGE)
        || !cls.isSource()
        || cls.has(Flags.ANONYMOUS)) {
      return body;
    }
    List<BStmt> checks = new ArrayList<>();
    for (VarSymbol p : params) {
      if (p.type().isReference()
          && p.type().nullness() == Nullness.NON_NULL
          && !(p.type() instanceof Type.TypeVar)) {
        checks.add(
            new BStmt.ExprStmt(requireNonNull(new BExpr.Local(p, span), p.name(), span), span));
      }
    }
    if (checks.isEmpty()) {
      return body;
    }
    checks.add(body);
    return new BStmt.Block(checks, body.span());
  }

  private BExpr requireNonNull(BExpr e, String message, Span span) {
    ClassSymbol objects = syms.lookup("java/util/Objects");
    for (MethodSymbol m : objects.methods("requireNonNull")) {
      if (m.params().size() == (message == null ? 1 : 2)
          && (message == null
              || m.params().get(1).type() instanceof ClassType ct
                  && ct.sym().binaryName().equals("java/lang/String"))) {
        List<BExpr> args =
            message == null
                ? List.of(e)
                : List.of(e, new BExpr.Const(message, syms.stringType(), span));
        return new BExpr.Call(
            null, m, args, CallKind.STATIC, e.type().withNullness(Nullness.NON_NULL), span);
      }
    }
    throw new IllegalStateException("Objects.requireNonNull not found");
  }

  // ------------------------------------------------------------------ async

  /** {@code async} body: {@code return Task.run(() -> { body })} on a virtual thread. */
  private BStmt asyncBody(MethodSymbol s, List<VarSymbol> params, BStmt body, Span span) {
    ClassSymbol task = syms.lookup("jsharp/core/Task");
    ClassSymbol callable = syms.lookup("java/util/concurrent/Callable");
    Type declared = s.returnType();
    Type payload =
        declared instanceof ClassType ct && !ct.args().isEmpty()
            ? ct.args().getFirst()
            : syms.objectType();
    if (payload instanceof Type.WildcardType w) {
      payload = w.bound() != null ? w.bound() : syms.objectType();
    }
    boolean isVoid =
        payload instanceof ClassType pc && pc.sym().binaryName().equals("java/lang/Void");
    BStmt lambdaBody = body;
    if (isVoid) {
      lambdaBody =
          new BStmt.Block(
              List.of(
                  body,
                  new BStmt.Return(new BExpr.Const(null, Type.NullType.INSTANCE, span), span)),
              span);
    }
    ClassType callableType = new ClassType(callable, List.of(payload), Nullness.NON_NULL);
    MethodSymbol call = callable.methods("call").getFirst();
    BExpr.Lambda lam =
        new BExpr.Lambda(
            callableType,
            call,
            List.of(),
            lambdaBody,
            payload,
            new ArrayList<>(params),
            !s.isStatic(),
            span);
    MethodSymbol run = null;
    for (MethodSymbol m : task.methods("run")) {
      if (m.params().size() == 1) {
        run = m;
      }
    }
    BExpr indy = lx(lam);
    return new BStmt.Return(
        new BExpr.Call(null, run, List.of(indy), CallKind.STATIC, declared, span), span);
  }

  // ------------------------------------------------------------------ constructors

  private BClass.Method constructor(BClass bc, BClass.Method m, List<BStmt> instanceInit) {
    MethodSymbol k = m.sym();
    enterMethod(k, PrimType.VOID);
    Span span = spanOf(m);
    List<VarSymbol> params = new ArrayList<>();
    List<BStmt> prologue = new ArrayList<>();
    // Synthetic leading parameters: enum name/ordinal; enclosing instance of local classes.
    VarSymbol enumName = null;
    VarSymbol enumOrdinal = null;
    if (cls.isEnum()) {
      enumName =
          new VarSymbol(
              "$enum$name", syms.stringType(), Flags.SYNTHETIC, VarSymbol.Kind.PARAM, span, -1);
      enumOrdinal =
          new VarSymbol(
              "$enum$ordinal", PrimType.INT, Flags.SYNTHETIC, VarSymbol.Kind.PARAM, span, -1);
      params.add(enumName);
      params.add(enumOrdinal);
    }
    FieldSymbol outerField = outerThisFields.get(cls);
    if (outerField != null) {
      VarSymbol outer =
          new VarSymbol(
              "this$0", outerField.type(), Flags.SYNTHETIC, VarSymbol.Kind.PARAM, span, -1);
      params.add(outer);
      prologue.add(assignField(outerField, new BExpr.Local(outer, span), span));
    }
    params.addAll(m.params());
    for (Map.Entry<VarSymbol, FieldSymbol> e :
        captureFields.getOrDefault(cls, Map.of()).entrySet()) {
      VarSymbol p =
          new VarSymbol(
              "val$" + e.getKey().name(),
              e.getKey().type(),
              Flags.SYNTHETIC,
              VarSymbol.Kind.PARAM,
              span,
              -1);
      params.add(p);
      prologue.add(assignField(e.getValue(), new BExpr.Local(p, span), span));
    }
    k.setParams(paramsFrom(params));
    List<BStmt> body = new ArrayList<>(prologue);
    List<BStmt> userStmts = new ArrayList<>();
    BStmt chain = null;
    boolean delegatesToThis = false;
    if (m.body() != null) {
      List<BStmt> stmts = m.body() instanceof BStmt.Block b ? b.stmts() : List.of(m.body());
      int start = 0;
      if (!stmts.isEmpty() && isChainCall(stmts.getFirst())) {
        BExpr.Call call = (BExpr.Call) ((BStmt.ExprStmt) stmts.getFirst()).expr();
        delegatesToThis = call.method().owner() == cls;
        chain = new BStmt.ExprStmt(chainCall(call, enumName, enumOrdinal, span), call.span());
        start = 1;
      }
      for (int i = start; i < stmts.size(); i++) {
        userStmts.add(ls(stmts.get(i)));
      }
    }
    if (chain == null) {
      chain = implicitSuper(k, m.params(), enumName, enumOrdinal, span);
    }
    body.add(chain);
    if (!delegatesToThis) {
      body.addAll(instanceInit);
    }
    // Generated constructors of records and enums with a header assign their components.
    if (m.body() == null && (cls.isRecord() || cls.isEnum())) {
      for (VarSymbol p : m.params()) {
        FieldSymbol f = cls.field(p.name());
        if (f != null && !f.isStatic()) {
          body.add(assignField(f, new BExpr.Local(p, span), span));
        }
      }
    }
    body.addAll(userStmts);
    if (k.has(Flags.COMPACT_CTOR)) {
      for (VarSymbol p : m.params()) {
        FieldSymbol f = cls.field(p.name());
        if (f != null) {
          body.add(assignField(f, new BExpr.Local(p, span), span));
        }
      }
    }
    body.add(new BStmt.Return(null, span));
    BStmt result = new BStmt.Block(body, span);
    result = withNullChecks(k, m.params(), result, span);
    return new BClass.Method(k, params, result, span);
  }

  private static List<MethodSymbol.Param> paramsFrom(List<VarSymbol> vs) {
    List<MethodSymbol.Param> out = new ArrayList<>();
    for (VarSymbol v : vs) {
      out.add(MethodSymbol.Param.of(v.name(), v.type()));
    }
    return out;
  }

  private BStmt assignField(FieldSymbol f, BExpr value, Span span) {
    BExpr recv = f.isStatic() ? null : self(span);
    return new BStmt.ExprStmt(
        new BExpr.Assign(new BLValue.FieldLV(recv, f, f.type()), value, f.type(), span), span);
  }

  private BExpr self(Span span) {
    return new BExpr.This(cls.thisType(), span);
  }

  private static boolean isChainCall(BStmt s) {
    return s instanceof BStmt.ExprStmt es
        && es.expr() instanceof BExpr.Call c
        && c.method().isConstructor()
        && c.receiver() instanceof BExpr.This;
  }

  /** An explicit {@code this(...)}/{@code super(...)}, with synthetic arguments added. */
  private BExpr chainCall(BExpr.Call call, VarSymbol enumName, VarSymbol enumOrdinal, Span span) {
    MethodSymbol target = call.method();
    List<BExpr> args = new ArrayList<>();
    if (enumName != null) {
      args.add(new BExpr.Local(enumName, span));
      args.add(new BExpr.Local(enumOrdinal, span));
    }
    if (target.owner() == cls) {
      FieldSymbol outer = outerThisFields.get(cls);
      if (outer != null) {
        args.add(new BExpr.Field(self(span), outer, outer.type(), span));
      }
    }
    for (int i = 0; i < call.args().size(); i++) {
      args.add(adapt(lx(call.args().get(i)), declaredParamType(target, i)));
    }
    if (target.owner() == cls) {
      for (FieldSymbol f : captureFields.getOrDefault(cls, Map.of()).values()) {
        args.add(new BExpr.Field(self(span), f, f.type(), span));
      }
    }
    return new BExpr.Call(
        new BExpr.This(cls.thisType(), span),
        target,
        args,
        CallKind.SPECIAL,
        PrimType.VOID,
        call.span());
  }

  private Type declaredParamType(MethodSymbol m, int i) {
    List<MethodSymbol.Param> ps = originalParams.getOrDefault(m, m.params());
    return i < ps.size() ? ps.get(i).type() : null;
  }

  /** The implicit superclass constructor call. */
  private BStmt implicitSuper(
      MethodSymbol k,
      List<VarSymbol> userParams,
      VarSymbol enumName,
      VarSymbol enumOrdinal,
      Span span) {
    ClassSymbol sup;
    MethodSymbol target = null;
    List<BExpr> args = new ArrayList<>();
    if (cls.isEnum()) {
      sup = syms.wellKnown("java/lang/Enum").sym();
      target = sup.methods(MethodSymbol.CONSTRUCTOR).getFirst();
      args.add(new BExpr.Local(enumName, span));
      args.add(new BExpr.Local(enumOrdinal, span));
    } else if (cls.isRecord()) {
      sup = syms.wellKnown("java/lang/Record").sym();
      target = sup.methods(MethodSymbol.CONSTRUCTOR).getFirst();
    } else if (cls.has(Flags.ANONYMOUS) && anonSuperCtors.containsKey(cls)) {
      target = anonSuperCtors.get(cls);
      for (int i = 0; i < userParams.size(); i++) {
        args.add(adapt(new BExpr.Local(userParams.get(i), span), declaredParamType(target, i)));
      }
      for (int i = userParams.size(); i < target.params().size(); i++) {
        args.add(defaultConst(target.params().get(i), span));
      }
    } else {
      ClassType st = cls.superclass();
      sup = st == null ? syms.objectSym() : st.sym();
      for (MethodSymbol c : sup.methods(MethodSymbol.CONSTRUCTOR)) {
        if (c.params().isEmpty()) {
          target = c;
        }
      }
      if (target == null) {
        for (MethodSymbol c : sup.methods(MethodSymbol.CONSTRUCTOR)) {
          if (c.params().stream().allMatch(MethodSymbol.Param::hasDefault)) {
            target = c;
            for (MethodSymbol.Param p : c.params()) {
              args.add(defaultConst(p, span));
            }
            break;
          }
        }
      }
      if (target == null) {
        throw new IllegalStateException("no superclass constructor for " + cls);
      }
    }
    return new BStmt.ExprStmt(
        new BExpr.Call(
            new BExpr.This(cls.thisType(), span),
            target,
            args,
            CallKind.SPECIAL,
            PrimType.VOID,
            span),
        span);
  }

  private BExpr defaultConst(MethodSymbol.Param p, Span span) {
    Object v = p.defaultValue();
    if (v == null) {
      return new BExpr.Const(null, Type.NullType.INSTANCE, span);
    }
    Type t =
        switch (v) {
          case Integer i -> PrimType.INT;
          case Long l -> PrimType.LONG;
          case Double d -> PrimType.DOUBLE;
          case Float f -> PrimType.FLOAT;
          case Boolean b -> PrimType.BOOLEAN;
          case Character c -> PrimType.CHAR;
          default -> syms.stringType();
        };
    return adapt(new BExpr.Const(v, t, span), p.type());
  }

  // ------------------------------------------------------------------ static initializer

  private BClass.Method staticInitializer(BClass bc) {
    enterMethod(null, PrimType.VOID);
    List<BStmt> stmts = new ArrayList<>();
    int ordinal = 0;
    List<FieldSymbol> constants = new ArrayList<>();
    for (BStmt s : bc.staticInit()) {
      if (cls.isEnum()
          && s instanceof BStmt.ExprStmt es
          && es.expr() instanceof BExpr.Assign as
          && as.target() instanceof BLValue.FieldLV flv
          && flv.field().has(Flags.ENUM_CONSTANT)
          && as.value() instanceof BExpr.New nw) {
        Span span = es.span();
        List<BExpr> args = new ArrayList<>();
        args.add(new BExpr.Const(flv.field().name(), syms.stringType(), span));
        args.add(new BExpr.Const(ordinal++, PrimType.INT, span));
        for (int i = 0; i < nw.args().size(); i++) {
          args.add(adapt(lx(nw.args().get(i)), declaredParamType(nw.ctor(), i)));
        }
        BExpr created = new BExpr.New(nw.type(), nw.ctor(), args, span);
        stmts.add(new BStmt.ExprStmt(new BExpr.Assign(flv, created, flv.type(), span), span));
        constants.add(flv.field());
        continue;
      }
      stmts.add(ls(s));
    }
    if (cls.isEnum()) {
      FieldSymbol values = enumValuesField();
      List<BExpr> elems = new ArrayList<>();
      for (FieldSymbol f : constants) {
        elems.add(new BExpr.Field(null, f, cls.thisType(), Span.NONE));
      }
      Type.ArrayType arr = Type.ArrayType.of(cls.thisType());
      BStmt assign =
          new BStmt.ExprStmt(
              new BExpr.Assign(
                  new BLValue.FieldLV(null, values, arr),
                  new BExpr.NewArray(arr, List.of(), elems, Span.NONE),
                  arr,
                  Span.NONE),
              Span.NONE);
      stmts.add(constants.size(), assign);
    }
    if (stmts.isEmpty()) {
      return null;
    }
    stmts.add(new BStmt.Return(null, Span.NONE));
    MethodSymbol clinit = new MethodSymbol("<clinit>", cls, Flags.STATIC);
    clinit.setReturnType(PrimType.VOID);
    return new BClass.Method(clinit, List.of(), new BStmt.Block(stmts, Span.NONE), Span.NONE);
  }

  // ------------------------------------------------------------------ records, bridges, overloads

  /** toString/equals/hashCode via ObjectMethods (codegen emits the invokedynamic). */
  private void recordObjectMethods(List<BClass.Method> methods) {
    boolean hasToString = false;
    boolean hasEquals = false;
    boolean hasHashCode = false;
    for (BClass.Method m : methods) {
      MethodSymbol s = m.sym();
      hasToString |= s.name().equals("toString") && s.params().isEmpty();
      hasHashCode |= s.name().equals("hashCode") && s.params().isEmpty();
      hasEquals |= s.name().equals("equals") && s.params().size() == 1;
    }
    if (!hasToString) {
      MethodSymbol m =
          new MethodSymbol(
              "toString", cls, Flags.PUBLIC | Flags.FINAL | Flags.RECORD | Flags.GENERATED);
      m.setReturnType(syms.stringType());
      methods.add(new BClass.Method(m, List.of(), null, Span.NONE));
    }
    if (!hasHashCode) {
      MethodSymbol m =
          new MethodSymbol(
              "hashCode", cls, Flags.PUBLIC | Flags.FINAL | Flags.RECORD | Flags.GENERATED);
      m.setReturnType(PrimType.INT);
      methods.add(new BClass.Method(m, List.of(), null, Span.NONE));
    }
    if (!hasEquals) {
      MethodSymbol m =
          new MethodSymbol(
              "equals", cls, Flags.PUBLIC | Flags.FINAL | Flags.RECORD | Flags.GENERATED);
      m.setParams(
          List.of(MethodSymbol.Param.of("o", syms.objectType().withNullness(Nullness.NULLABLE))));
      m.setReturnType(PrimType.BOOLEAN);
      VarSymbol o = new VarSymbol("o", syms.objectType(), 0, VarSymbol.Kind.PARAM, Span.NONE, -1);
      methods.add(new BClass.Method(m, List.of(o), null, Span.NONE));
    }
  }

  /** A bridge forwarding the overridden method's erased signature to the implementation. */
  private BClass.Method bridge(BClass.Bridge b) {
    MethodSymbol target = b.target();
    MethodSymbol over = b.overridden();
    MethodSymbol bridge =
        new MethodSymbol(
            over.jvmName(),
            cls,
            (target.flags() & (Flags.PUBLIC | Flags.PROTECTED)) | Flags.BRIDGE | Flags.SYNTHETIC);
    List<VarSymbol> params = new ArrayList<>();
    List<MethodSymbol.Param> ps = new ArrayList<>();
    List<BExpr> args = new ArrayList<>();
    for (int i = 0; i < over.params().size(); i++) {
      Type t = over.params().get(i).type().erasure();
      VarSymbol v = new VarSymbol("p" + i, t, 0, VarSymbol.Kind.PARAM, Span.NONE, -1);
      params.add(v);
      ps.add(MethodSymbol.Param.of(v.name(), t));
      Type want = target.params().get(i).type().erasure();
      BExpr a = new BExpr.Local(v, Span.NONE);
      if (!types.isSubtype(t, want)) {
        a = new BExpr.Conv(a, ConvKind.CHECKCAST, want, Span.NONE);
      }
      args.add(a);
    }
    bridge.setParams(ps);
    Type ret = over.returnType() == null ? PrimType.VOID : over.returnType().erasure();
    bridge.setReturnType(ret);
    BExpr call =
        new BExpr.Call(
            self(Span.NONE),
            target,
            args,
            target.owner().isInterface() ? CallKind.INTERFACE : CallKind.VIRTUAL,
            target.returnType(),
            Span.NONE);
    BStmt body =
        ret == PrimType.VOID
            ? new BStmt.Block(
                List.of(new BStmt.ExprStmt(call, Span.NONE), new BStmt.Return(null, Span.NONE)),
                Span.NONE)
            : new BStmt.Return(adapt(call, ret), Span.NONE);
    return new BClass.Method(bridge, params, body, Span.NONE);
  }

  /** Trailing-default overloads so Java callers can omit defaulted arguments. */
  private void defaultOverloads(List<BClass.Method> methods) {
    Set<String> existing = new HashSet<>();
    for (BClass.Method m : methods) {
      existing.add(m.sym().jvmName() + erasedParams(m.sym()));
    }
    List<BClass.Method> added = new ArrayList<>();
    for (BClass.Method m : methods) {
      MethodSymbol s = m.sym();
      if (s.has(Flags.PRIVATE)
          || s.has(Flags.SYNTHETIC)
          || s.has(Flags.OVERRIDE)
          || s.has(Flags.BRIDGE)
          || !cls.isSource()) {
        continue;
      }
      List<MethodSymbol.Param> ps = s.params();
      int trailing = 0;
      for (int i = ps.size() - 1; i >= 0 && ps.get(i).hasDefault() && trailing < 8; i--) {
        trailing++;
      }
      for (int drop = 1; drop <= trailing; drop++) {
        int keep = ps.size() - drop;
        MethodSymbol o =
            new MethodSymbol(
                s.name(),
                cls,
                (s.flags() & (Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE | Flags.STATIC))
                    | Flags.GENERATED
                    | (cls.isInterface() && !s.isStatic() ? Flags.DEFAULT : 0)
                    | (s.isConstructor()
                        ? 0
                        : Flags.FINAL & (cls.isInterface() ? 0 : Flags.FINAL)));
        o.setParams(
            ps.subList(0, keep).stream()
                .map(p -> MethodSymbol.Param.of(p.name(), p.type()))
                .toList());
        o.setReturnType(s.returnType());
        o.setTypeParams(s.typeParams());
        String key = o.jvmName() + erasedParams(o);
        if (!existing.add(key)) {
          continue;
        }
        List<VarSymbol> params = new ArrayList<>();
        List<BExpr> args = new ArrayList<>();
        for (int i = 0; i < keep; i++) {
          VarSymbol v =
              new VarSymbol(
                  ps.get(i).name(), ps.get(i).type(), 0, VarSymbol.Kind.PARAM, Span.NONE, -1);
          params.add(v);
          args.add(new BExpr.Local(v, Span.NONE));
        }
        for (int i = keep; i < ps.size(); i++) {
          args.add(defaultConst(ps.get(i), Span.NONE));
        }
        BStmt body;
        if (s.isConstructor()) {
          BExpr call =
              new BExpr.Call(self(Span.NONE), s, args, CallKind.SPECIAL, PrimType.VOID, Span.NONE);
          // Through the normal constructor path so synthetic parameters are added.
          BClass.Method generated =
              constructor(
                  checkedByClass.get(cls),
                  new BClass.Method(
                      o,
                      params,
                      new BStmt.Block(List.of(new BStmt.ExprStmt(call, Span.NONE)), Span.NONE),
                      Span.NONE),
                  List.of());
          added.add(generated);
          continue;
        }
        CallKind kind =
            s.isStatic()
                ? CallKind.STATIC
                : cls.isInterface() ? CallKind.INTERFACE : CallKind.VIRTUAL;
        BExpr call =
            new BExpr.Call(
                s.isStatic() ? null : self(Span.NONE), s, args, kind, s.returnType(), Span.NONE);
        body =
            s.returnType() == PrimType.VOID
                ? new BStmt.Block(
                    List.of(new BStmt.ExprStmt(call, Span.NONE), new BStmt.Return(null, Span.NONE)),
                    Span.NONE)
                : new BStmt.Return(call, Span.NONE);
        added.add(new BClass.Method(o, params, body, Span.NONE));
      }
    }
    methods.addAll(added);
  }

  private static String erasedParams(MethodSymbol m) {
    List<Type> ts = new ArrayList<>();
    for (MethodSymbol.Param p : m.params()) {
      ts.add(p.type() == null ? Type.ErrorType.INSTANCE : p.type().erasure());
    }
    return Descriptors.params(ts);
  }

  // ------------------------------------------------------------------ adaptation

  /** Converts {@code e} to {@code to} at the JVM level (boxing, unboxing, widening, casts). */
  BExpr adapt(BExpr e, Type to) {
    if (to == null || to == PrimType.VOID || to.isError()) {
      return e;
    }
    Type from = e.type();
    if (from instanceof Type.NeverType || from.isError()) {
      return e;
    }
    if (from instanceof PrimType fp && to instanceof PrimType tp) {
      return fp == tp ? e : new BExpr.Conv(e, ConvKind.PRIMITIVE, tp, e.span());
    }
    if (from instanceof PrimType fp) {
      if (fp == PrimType.VOID) {
        return e;
      }
      PrimType target = to instanceof ClassType tc ? syms.unboxedOf(tc.sym()) : null;
      if (target != null && target != fp) {
        e = new BExpr.Conv(e, ConvKind.PRIMITIVE, target, e.span());
        fp = target;
      }
      return new BExpr.Conv(e, ConvKind.BOX, syms.boxed(fp), e.span());
    }
    if (to instanceof PrimType tp) {
      if (from instanceof Type.NullType) {
        return e;
      }
      PrimType u = types.unboxedType(from);
      if (u == null) {
        e = new BExpr.Conv(e, ConvKind.CHECKCAST, syms.boxed(tp), e.span());
        u = tp;
      }
      BExpr r = new BExpr.Conv(e, ConvKind.UNBOX, u, e.span());
      return u == tp ? r : new BExpr.Conv(r, ConvKind.PRIMITIVE, tp, e.span());
    }
    if (from instanceof Type.NullType) {
      return e;
    }
    Type fe = from.erasure();
    Type te = to.erasure();
    if (!types.isSubtype(fe, te)) {
      return new BExpr.Conv(e, ConvKind.CHECKCAST, to, e.span());
    }
    return e;
  }

  // ------------------------------------------------------------------ statements

  BStmt ls(BStmt s) {
    if (s == null) {
      return null;
    }
    return switch (s) {
      case BStmt.Block b -> new BStmt.Block(lsAll(b.stmts()), b.span());
      case BStmt.LocalDecl d ->
          new BStmt.LocalDecl(
              d.var(), d.init() == null ? null : adapt(lx(d.init()), d.var().type()), d.span());
      case BStmt.ExprStmt es -> exprStmt(es);
      case BStmt.If i -> new BStmt.If(lx(i.cond()), ls(i.then()), ls(i.otherwise()), i.span());
      case BStmt.While w -> new BStmt.While(lx(w.cond()), ls(w.body()), w.label(), w.span());
      case BStmt.DoWhile d -> new BStmt.DoWhile(ls(d.body()), lx(d.cond()), d.label(), d.span());
      case BStmt.For f ->
          new BStmt.For(
              lsAll(f.init()),
              f.cond() == null ? null : lx(f.cond()),
              lxAll(f.update()),
              ls(f.body()),
              f.label(),
              f.span());
      case BStmt.Foreach f -> foreach(f);
      case BStmt.Labeled l -> new BStmt.Labeled(l.label(), ls(l.body()), l.span());
      case BStmt.Break b -> b;
      case BStmt.Continue c -> c;
      case BStmt.Return r ->
          new BStmt.Return(r.value() == null ? null : adapt(lx(r.value()), returnType), r.span());
      case BStmt.Throw t -> new BStmt.Throw(lx(t.exception()), t.span());
      case BStmt.Try t -> tryStmt(t);
      case BStmt.Using u -> using(u);
      case BStmt.Sync sy -> new BStmt.Sync(lx(sy.monitor()), ls(sy.body()), sy.span());
      case BStmt.Switch sw -> switchStmt(sw);
      case BStmt.Empty e -> e;
    };
  }

  private List<BStmt> lsAll(List<BStmt> ss) {
    List<BStmt> out = new ArrayList<>(ss.size());
    for (BStmt s : ss) {
      out.add(ls(s));
    }
    return out;
  }

  private List<BExpr> lxAll(List<BExpr> es) {
    List<BExpr> out = new ArrayList<>(es.size());
    for (BExpr e : es) {
      out.add(lx(e));
    }
    return out;
  }

  /** Expression statements; property assignments become plain setter calls. */
  private BStmt exprStmt(BStmt.ExprStmt es) {
    if (es.expr() instanceof BExpr.Assign as && as.target() instanceof BLValue.PropertyLV p) {
      return new BStmt.ExprStmt(setterCall(p, lx(as.value()), es.span()), es.span());
    }
    return new BStmt.ExprStmt(lx(es.expr()), es.span());
  }

  private BStmt foreach(BStmt.Foreach f) {
    Span span = f.span();
    BExpr iterable = lx(f.iterable());
    List<BStmt> out = new ArrayList<>();
    if (iterable.type() instanceof Type.ArrayType at) {
      VarSymbol arr = temp("arr", at, span);
      VarSymbol len = temp("len", PrimType.INT, span);
      VarSymbol i = temp("i", PrimType.INT, span);
      out.add(new BStmt.LocalDecl(arr, iterable, span));
      out.add(
          new BStmt.LocalDecl(
              len, new BExpr.ArrayLength(new BExpr.Local(arr, span), PrimType.INT, span), span));
      BExpr elem =
          new BExpr.ArrayElem(
              new BExpr.Local(arr, span), new BExpr.Local(i, span), at.elem(), span);
      List<BStmt> body = new ArrayList<>();
      body.add(new BStmt.LocalDecl(f.var(), adapt(elem, f.var().type()), span));
      body.addAll(lsAll(f.destructure()));
      body.add(ls(f.body()));
      BExpr cond =
          new BExpr.Binary(
              BinOp.LT,
              new BExpr.Local(i, span),
              new BExpr.Local(len, span),
              PrimType.BOOLEAN,
              false,
              span);
      BExpr update =
          new BExpr.IncDec(new BLValue.LocalLV(i), true, false, PrimType.INT, false, span);
      out.add(
          new BStmt.For(
              List.of(new BStmt.LocalDecl(i, new BExpr.Const(0, PrimType.INT, span), span)),
              cond,
              List.of(update),
              new BStmt.Block(body, span),
              f.label(),
              span));
      return new BStmt.Block(out, span);
    }
    ClassSymbol iterableSym = syms.lookup("java/lang/Iterable");
    ClassSymbol iteratorSym = syms.lookup("java/util/Iterator");
    MethodSymbol iteratorM = iterableSym.methods("iterator").getFirst();
    MethodSymbol hasNext = iteratorSym.methods("hasNext").getFirst();
    MethodSymbol next = iteratorSym.methods("next").getFirst();
    ClassType itType = ClassType.of(iteratorSym);
    VarSymbol it = temp("it", itType, span);
    Type recvType = iterable.type();
    boolean recvIsInterface =
        recvType instanceof ClassType rc && rc.sym().isInterface()
            || !(recvType instanceof ClassType);
    MethodSymbol iterCall = iteratorM;
    if (recvType instanceof ClassType rc && !rc.sym().isInterface()) {
      // Call through the class (invokevirtual) when the static type is a class.
      for (MethodSymbol m : lookupMethods(rc, "iterator")) {
        if (m.params().isEmpty()) {
          iterCall = m;
          break;
        }
      }
    }
    BExpr iterator =
        new BExpr.Call(
            iterable,
            iterCall,
            List.of(),
            iterCall.owner().isInterface() ? CallKind.INTERFACE : CallKind.VIRTUAL,
            itType,
            span);
    if (recvIsInterface) {
      iterator = new BExpr.Call(iterable, iteratorM, List.of(), CallKind.INTERFACE, itType, span);
    }
    out.add(new BStmt.LocalDecl(it, iterator, span));
    BExpr elem =
        new BExpr.Call(
            new BExpr.Local(it, span),
            next,
            List.of(),
            CallKind.INTERFACE,
            f.elementType() instanceof PrimType p ? syms.boxed(p) : f.elementType(),
            span);
    List<BStmt> body = new ArrayList<>();
    body.add(new BStmt.LocalDecl(f.var(), adapt(elem, f.var().type()), span));
    body.addAll(lsAll(f.destructure()));
    body.add(ls(f.body()));
    BExpr cond =
        new BExpr.Call(
            new BExpr.Local(it, span),
            hasNext,
            List.of(),
            CallKind.INTERFACE,
            PrimType.BOOLEAN,
            span);
    out.add(new BStmt.While(cond, new BStmt.Block(body, span), f.label(), span));
    return new BStmt.Block(out, span);
  }

  private List<MethodSymbol> lookupMethods(ClassType t, String name) {
    List<MethodSymbol> out = new ArrayList<>();
    for (ClassSymbol c = t.sym();
        c != null;
        c = c.superclass() == null ? null : c.superclass().sym()) {
      out.addAll(c.methods(name));
    }
    return out;
  }

  private BStmt tryStmt(BStmt.Try t) {
    List<BStmt.Catch> catches = new ArrayList<>();
    for (BStmt.Catch c : t.catches()) {
      BStmt body = ls(c.body());
      if (c.filter() != null) {
        // `catch (E e) when (cond)`: rethrow when the filter does not match.
        BStmt rethrow = new BStmt.Throw(new BExpr.Local(c.var(), c.span()), c.span());
        BExpr notCond = new BExpr.Unary(BExpr.UnOp.NOT, lx(c.filter()), PrimType.BOOLEAN, c.span());
        body =
            new BStmt.Block(
                List.of(new BStmt.If(notCond, rethrow, null, c.span()), body), c.span());
      }
      catches.add(new BStmt.Catch(c.types(), c.var(), null, body, c.span()));
    }
    return new BStmt.Try(ls(t.body()), catches, ls(t.finallyBody()), t.span());
  }

  /**
   * {@code using (r = init) body} with Java try-with-resources semantics: the resource is closed
   * (if non-null) after the body; an exception from close is suppressed by a primary exception.
   */
  private BStmt using(BStmt.Using u) {
    Span span = u.span();
    VarSymbol r = u.resource();
    VarSymbol primary = temp("primary", syms.throwableType().withNullness(Nullness.NULLABLE), span);
    VarSymbol t =
        new VarSymbol(
            "$t" + (tempCounter++),
            syms.throwableType(),
            Flags.SYNTHETIC,
            VarSymbol.Kind.CATCH,
            span,
            -1);
    VarSymbol s2 =
        new VarSymbol(
            "$s" + (tempCounter++),
            syms.throwableType(),
            Flags.SYNTHETIC,
            VarSymbol.Kind.CATCH,
            span,
            -1);
    BExpr rRef = new BExpr.Local(r, span);
    ClassSymbol ac = syms.lookup("java/lang/AutoCloseable");
    MethodSymbol close = ac.methods("close").getFirst();
    Type rt = r.type();
    if (rt instanceof ClassType rc) {
      for (MethodSymbol m : lookupMethods(rc, "close")) {
        if (m.params().isEmpty() && !m.isStatic()) {
          close = m;
          break;
        }
      }
    }
    CallKind ck = close.owner().isInterface() ? CallKind.INTERFACE : CallKind.VIRTUAL;
    BExpr closeCall = new BExpr.Call(rRef, close, List.of(), ck, PrimType.VOID, span);
    MethodSymbol addSuppressed = syms.throwableType().sym().methods("addSuppressed").getFirst();
    BStmt closeSuppressing =
        new BStmt.Try(
            new BStmt.ExprStmt(closeCall, span),
            List.of(
                new BStmt.Catch(
                    List.of(syms.throwableType()),
                    s2,
                    null,
                    new BStmt.ExprStmt(
                        new BExpr.Call(
                            new BExpr.Local(primary, span),
                            addSuppressed,
                            List.of(new BExpr.Local(s2, span)),
                            CallKind.VIRTUAL,
                            PrimType.VOID,
                            span),
                        span),
                    span)),
            null,
            span);
    BExpr primaryNull =
        new BExpr.Binary(
            BinOp.REF_EQ,
            new BExpr.Local(primary, span),
            nullConst(span),
            PrimType.BOOLEAN,
            false,
            span);
    BStmt closeIt =
        new BStmt.If(primaryNull, new BStmt.ExprStmt(closeCall, span), closeSuppressing, span);
    BStmt finallyBody =
        rt.isReference() && rt.nullness() != Nullness.NON_NULL
            ? new BStmt.If(
                new BExpr.Binary(
                    BinOp.REF_NE, rRef, nullConst(span), PrimType.BOOLEAN, false, span),
                closeIt,
                null,
                span)
            : closeIt;
    BStmt catchAll =
        new BStmt.Block(
            List.of(
                new BStmt.ExprStmt(
                    new BExpr.Assign(
                        new BLValue.LocalLV(primary),
                        new BExpr.Local(t, span),
                        primary.type(),
                        span),
                    span),
                new BStmt.Throw(new BExpr.Local(t, span), span)),
            span);
    BStmt tryStmt =
        new BStmt.Try(
            ls(u.body()),
            List.of(new BStmt.Catch(List.of(syms.throwableType()), t, null, catchAll, span)),
            finallyBody,
            span);
    return new BStmt.Block(
        List.of(
            new BStmt.LocalDecl(r, lx(u.init()), span),
            new BStmt.LocalDecl(primary, nullConst(span), span),
            tryStmt),
        span);
  }

  private static BExpr nullConst(Span span) {
    return new BExpr.Const(null, Type.NullType.INSTANCE, span);
  }

  // ------------------------------------------------------------------ switch & patterns

  private BStmt switchStmt(BStmt.Switch sw) {
    BSwitch s = sw.sw();
    Span span = sw.span();
    VarSymbol sel = s.selectorVar();
    List<BStmt> out = new ArrayList<>();
    out.add(new BStmt.LocalDecl(sel, lx(s.selector()), span));
    BStmt chain = null;
    BStmt defaultBody = null;
    List<BSwitch.Case> cases = s.cases();
    for (BSwitch.Case c : cases) {
      if (c.pattern() == null) {
        defaultBody = new BStmt.Block(lsAll(c.body()), span);
      }
    }
    if (defaultBody == null && s.exhaustive() && !cases.isEmpty()) {
      defaultBody = new BStmt.Throw(matchException(span), span);
    }
    chain = defaultBody;
    for (int i = cases.size() - 1; i >= 0; i--) {
      BSwitch.Case c = cases.get(i);
      if (c.pattern() == null) {
        continue;
      }
      BExpr cond = match(c.pattern(), new BExpr.Local(sel, span), s.selectorType());
      if (c.guard() != null) {
        cond = and(cond, lx(c.guard()));
      }
      chain = new BStmt.If(cond, new BStmt.Block(lsAll(c.body()), span), chain, span);
    }
    if (chain != null) {
      out.add(chain);
    }
    return new BStmt.Labeled(sw.label(), new BStmt.Block(out, span), span);
  }

  private BExpr switchExpr(BExpr.Switch sx) {
    BSwitch s = sx.sw();
    Span span = sx.span();
    VarSymbol sel = s.selectorVar();
    BExpr chain = null;
    for (BSwitch.Case c : s.cases()) {
      if (c.pattern() == null) {
        chain = adapt(lx(c.value()), sx.type());
      }
    }
    if (chain == null) {
      chain = new BExpr.Throw(matchException(span), sx.type(), span);
    }
    List<BSwitch.Case> cases = s.cases();
    for (int i = cases.size() - 1; i >= 0; i--) {
      BSwitch.Case c = cases.get(i);
      if (c.pattern() == null) {
        continue;
      }
      BExpr cond = match(c.pattern(), new BExpr.Local(sel, span), s.selectorType());
      if (c.guard() != null) {
        cond = and(cond, lx(c.guard()));
      }
      chain = new BExpr.Conditional(cond, adapt(lx(c.value()), sx.type()), chain, sx.type(), span);
    }
    return new BExpr.Let(sel, lx(s.selector()), chain, span);
  }

  private BExpr matchException(Span span) {
    ClassSymbol me = syms.lookup("java/lang/MatchException");
    MethodSymbol ctor = me.methods(MethodSymbol.CONSTRUCTOR).getFirst();
    return new BExpr.New(ClassType.of(me), ctor, List.of(nullConst(span), nullConst(span)), span);
  }

  private static BExpr and(BExpr a, BExpr b) {
    if (a instanceof BExpr.Const c && Boolean.TRUE.equals(c.value())) {
      return b;
    }
    return new BExpr.Binary(BinOp.COND_AND, a, b, PrimType.BOOLEAN, false, a.span());
  }

  private static BExpr or(BExpr a, BExpr b) {
    return new BExpr.Binary(BinOp.COND_OR, a, b, PrimType.BOOLEAN, false, a.span());
  }

  private static BExpr trueConst(Span span) {
    return new BExpr.Const(true, PrimType.BOOLEAN, span);
  }

  /** Assigns a binding and yields true (usable inside conditions). */
  private BExpr bind(VarSymbol v, BExpr value, Span span) {
    BStmt assign =
        new BStmt.ExprStmt(
            new BExpr.Assign(new BLValue.LocalLV(v), adapt(value, v.type()), v.type(), span), span);
    return new BExpr.Block(List.of(assign), trueConst(span), span);
  }

  /**
   * A boolean expression testing pattern {@code p} against {@code subject} (a side-effect free
   * expression of type {@code type}), assigning bindings when it matches.
   */
  BExpr match(BPattern p, BExpr subject, Type type) {
    Span span = p.span();
    return switch (p) {
      case BPattern.Any any ->
          any.binding() == null ? trueConst(span) : bind(any.binding(), subject, span);
      case BPattern.TypeTest tt -> {
        Type target = tt.type();
        BExpr test = typeTest(subject, type, target, span);
        if (tt.binding() == null) {
          yield test;
        }
        BExpr cast = castTo(subject, type, target, span);
        yield and(test, bind(tt.binding(), cast, span));
      }
      case BPattern.Constant c -> constantTest(subject, type, lx(c.value()), span);
      case BPattern.Relational r -> {
        BExpr v = subject;
        BExpr nonNull = null;
        if (!(type instanceof PrimType)) {
          nonNull =
              new BExpr.Binary(
                  BinOp.REF_NE, subject, nullConst(span), PrimType.BOOLEAN, false, span);
        }
        BExpr cmp =
            new BExpr.Binary(
                r.op(), adapt(v, r.operandType()), lx(r.value()), PrimType.BOOLEAN, false, span);
        yield nonNull == null ? cmp : and(nonNull, cmp);
      }
      case BPattern.Recursive r -> {
        Type t = r.type() == null ? type : r.type();
        BExpr test = typeTest(subject, type, t, span);
        VarSymbol tmp = temp("rec", t.withNullness(Nullness.NON_NULL), span);
        BExpr body = trueConst(span);
        // Build sub-matches from the last one backwards so each accessor result is bound once.
        for (int i = r.subpatterns().size() - 1; i >= 0; i--) {
          MethodSymbol acc = r.accessors().get(i);
          Type accType = r.accessorTypes().get(i);
          BExpr call =
              new BExpr.Call(
                  new BExpr.Local(tmp, span),
                  acc,
                  List.of(),
                  acc.owner().isInterface() ? CallKind.INTERFACE : CallKind.VIRTUAL,
                  accType,
                  span);
          BPattern sub = r.subpatterns().get(i);
          if (sub instanceof BPattern.Any any && any.binding() == null) {
            continue;
          }
          VarSymbol v = temp("c", accType, span);
          BExpr subMatch = match(sub, new BExpr.Local(v, span), accType);
          body = new BExpr.Let(v, call, and(subMatch, body), span);
        }
        if (r.binding() != null) {
          body = and(bind(r.binding(), new BExpr.Local(tmp, span), span), body);
        }
        BExpr cast = castTo(subject, type, t, span);
        yield and(test, new BExpr.Let(tmp, cast, body, span));
      }
      case BPattern.And a -> and(match(a.left(), subject, type), match(a.right(), subject, type));
      case BPattern.Or o -> or(match(o.left(), subject, type), match(o.right(), subject, type));
      case BPattern.Not n ->
          new BExpr.Unary(
              BExpr.UnOp.NOT, match(n.pattern(), subject, type), PrimType.BOOLEAN, span);
    };
  }

  /** {@code subject instanceof target}, simplified when statically known. */
  private BExpr typeTest(BExpr subject, Type subjectType, Type target, Span span) {
    if (subjectType instanceof PrimType sp) {
      PrimType tp = types.primitiveView(target);
      return new BExpr.Const(
          sp == tp || tp == null || target.isReference() && types.isSubtype(syms.boxed(sp), target),
          PrimType.BOOLEAN,
          span);
    }
    if (types.isSubtype(subjectType.erasure(), target.erasure())) {
      return new BExpr.Binary(
          BinOp.REF_NE, subject, nullConst(span), PrimType.BOOLEAN, false, span);
    }
    Type erased = target instanceof PrimType p ? syms.boxed(p) : target.erasure();
    return new BExpr.InstanceOf(subject, erased, PrimType.BOOLEAN, span);
  }

  private BExpr castTo(BExpr subject, Type subjectType, Type target, Span span) {
    if (target instanceof PrimType tp) {
      return adapt(subject, tp);
    }
    if (subjectType instanceof PrimType) {
      return adapt(subject, target);
    }
    if (types.isSubtype(subjectType.erasure(), target.erasure())) {
      return new BExpr.Conv(subject, ConvKind.RETYPE, target.withNullness(Nullness.NON_NULL), span);
    }
    return new BExpr.Conv(
        subject, ConvKind.CHECKCAST, target.withNullness(Nullness.NON_NULL), span);
  }

  private BExpr constantTest(BExpr subject, Type type, BExpr value, Span span) {
    if (value.type() instanceof Type.NullType) {
      if (type instanceof PrimType) {
        return new BExpr.Const(false, PrimType.BOOLEAN, span);
      }
      return new BExpr.Binary(BinOp.REF_EQ, subject, value, PrimType.BOOLEAN, false, span);
    }
    if (value instanceof BExpr.Field f && f.field().has(Flags.ENUM_CONSTANT)) {
      return new BExpr.Binary(BinOp.REF_EQ, subject, value, PrimType.BOOLEAN, false, span);
    }
    PrimType vp = types.primitiveView(value.type());
    if (type instanceof PrimType tp) {
      PrimType op = vp == null ? tp : Types.promote(tp, vp);
      if (tp == PrimType.BOOLEAN) {
        op = PrimType.BOOLEAN;
      }
      return new BExpr.Binary(
          BinOp.EQ, adapt(subject, op), adapt(value, op), PrimType.BOOLEAN, false, span);
    }
    if (vp != null && value.type() instanceof PrimType) {
      // Boxed or Object subject against a primitive constant: box the constant, compare by equals.
      value = adapt(value, syms.boxed(vp));
    }
    return objectsEquals(value, subject, span);
  }

  private BExpr objectsEquals(BExpr a, BExpr b, Span span) {
    ClassSymbol objects = syms.lookup("java/util/Objects");
    MethodSymbol equals = null;
    for (MethodSymbol m : objects.methods("equals")) {
      if (m.params().size() == 2) {
        equals = m;
      }
    }
    return new BExpr.Call(null, equals, List.of(a, b), CallKind.STATIC, PrimType.BOOLEAN, span);
  }

  // ------------------------------------------------------------------ expressions

  BExpr lx(BExpr e) {
    return switch (e) {
      case BExpr.Const c -> c;
      case BExpr.Local l -> localRef(l);
      case BExpr.This t -> t;
      case BExpr.OuterThis o -> outerThis(o.outer(), o.span());
      case BExpr.Field f -> field(f);
      case BExpr.Call c -> call(c);
      case BExpr.New n -> newExpr(n);
      case BExpr.ObjectInit oi -> objectInit(oi);
      case BExpr.NewArray na ->
          new BExpr.NewArray(
              na.type(),
              lxAll(na.dims()),
              na.elems() == null ? null : adaptAll(lxAll(na.elems()), na.type().elem()),
              na.span());
      case BExpr.ArrayElem ae ->
          new BExpr.ArrayElem(lx(ae.array()), lx(ae.index()), ae.type(), ae.span());
      case BExpr.ArrayLength al -> new BExpr.ArrayLength(lx(al.array()), al.type(), al.span());
      case BExpr.Unary u -> new BExpr.Unary(u.op(), lx(u.operand()), u.type(), u.span());
      case BExpr.Binary b -> binary(b);
      case BExpr.ValueEquals ve -> valueEquals(ve);
      case BExpr.Assign as -> assign(as);
      case BExpr.CompoundAssign ca -> compound(ca);
      case BExpr.IncDec id -> incDec(id);
      case BExpr.Conv c -> conv(c);
      case BExpr.InstanceOf io ->
          new BExpr.InstanceOf(lx(io.expr()), io.target(), io.type(), io.span());
      case BExpr.Conditional c ->
          new BExpr.Conditional(
              lx(c.cond()),
              adapt(lx(c.then()), c.type()),
              adapt(lx(c.otherwise()), c.type()),
              c.type(),
              c.span());
      case BExpr.Concat c -> new BExpr.Concat(lxAll(c.parts()), c.type(), c.span());
      case BExpr.Lambda l -> lambda(l);
      case BExpr.MethodRef m -> methodRef(m);
      case BExpr.ClassLit cl -> cl;
      case BExpr.Let l -> new BExpr.Let(l.var(), lx(l.init()), lx(l.body()), l.span());
      case BExpr.Throw t -> new BExpr.Throw(lx(t.exception()), t.type(), t.span());
      case BExpr.SafeAccess sa -> safeAccess(sa);
      case BExpr.Coalesce co -> coalesce(co);
      case BExpr.Block b -> new BExpr.Block(lsAll(b.stmts()), lx(b.value()), b.span());
      case BExpr.Await aw -> await(aw);
      case BExpr.Switch sx -> switchExpr(sx);
      case BExpr.IsPattern ip -> isPattern(ip);
      case BExpr.Nop n -> n;
      case BExpr.Indy i -> i;
      case BExpr.Error err ->
          throw new IllegalStateException(
              "error node reached lowering at offset " + err.span() + " in " + cls);
    };
  }

  private List<BExpr> adaptAll(List<BExpr> es, Type to) {
    List<BExpr> out = new ArrayList<>();
    for (BExpr e : es) {
      out.add(adapt(e, to));
    }
    return out;
  }

  /** A captured variable inside a local class becomes a field read. */
  private BExpr localRef(BExpr.Local l) {
    VarSymbol v = l.var();
    BExpr access = capturedAccess(v, l.span());
    return access != null ? access : l;
  }

  private BExpr capturedAccess(VarSymbol v, Span span) {
    if (method != null && method.owner() != cls) {
      return null;
    }
    BExpr recv = self(span);
    for (ClassSymbol c = cls; c != null; ) {
      Map<VarSymbol, FieldSymbol> caps = captureFields.get(c);
      if (caps != null && caps.containsKey(v)) {
        FieldSymbol f = caps.get(v);
        return new BExpr.Field(recv, f, f.type(), span);
      }
      FieldSymbol outer = outerThisFields.get(c);
      if (outer == null || !(c.has(Flags.LOCAL) || c.has(Flags.ANONYMOUS))) {
        return null;
      }
      recv = new BExpr.Field(recv, outer, outer.type(), span);
      c = c.outer();
    }
    return null;
  }

  private boolean isCapturedField(VarSymbol v) {
    for (ClassSymbol c = cls;
        c != null && (c.has(Flags.LOCAL) || c.has(Flags.ANONYMOUS));
        c = c.outer()) {
      Map<VarSymbol, FieldSymbol> caps = captureFields.get(c);
      if (caps != null && caps.containsKey(v)) {
        return true;
      }
      if (outerThisFields.get(c) == null) {
        break;
      }
    }
    return false;
  }

  /** {@code Outer.this} from a local/anonymous class: follow {@code this$0} fields. */
  private BExpr outerThis(ClassSymbol target, Span span) {
    BExpr e = self(span);
    for (ClassSymbol c = cls; c != null && c != target; c = c.outer()) {
      FieldSymbol f = outerThisFields.get(c);
      if (f == null) {
        throw new IllegalStateException("no enclosing instance of " + target + " from " + cls);
      }
      e = new BExpr.Field(e, f, f.type(), span);
    }
    return e;
  }

  private BExpr field(BExpr.Field f) {
    return new BExpr.Field(
        f.receiver() == null ? null : lx(f.receiver()), f.field(), f.type(), f.span());
  }

  /**
   * True if a getter/setter can be replaced by a direct field access (perf rule in ARCHITECTURE).
   */
  private FieldSymbol directBackingField(MethodSymbol accessor, BExpr receiver) {
    PropertySymbol p = accessor.property();
    if (p == null || p.backingField() == null || p.owner() != cls || p.decl() == null) {
      return null;
    }
    if (!accessor.isStatic() && !(receiver instanceof BExpr.This)) {
      return null;
    }
    boolean overridable =
        !cls.isFinal()
            && !accessor.has(Flags.FINAL)
            && !accessor.has(Flags.PRIVATE)
            && !accessor.isStatic();
    if (overridable || accessor.has(Flags.OPEN) || accessor.isAbstract()) {
      return null;
    }
    if (p.decl().accessors() == null) {
      return null;
    }
    for (Accessor a : p.decl().accessors()) {
      boolean match =
          accessor == p.getter() ? a.kind() == Accessor.Kind.GET : a.kind() != Accessor.Kind.GET;
      if (match && a.body() != null) {
        return null; // custom accessor
      }
    }
    return p.backingField();
  }

  private BExpr call(BExpr.Call c) {
    MethodSymbol m = c.method();
    BExpr recv = c.receiver() == null ? null : lx(c.receiver());
    if (m.property() != null && m == m.property().getter() && c.args().isEmpty()) {
      FieldSymbol f = directBackingField(m, recv);
      if (f != null) {
        return new BExpr.Field(f.isStatic() ? null : recv, f, c.type(), c.span());
      }
    }
    List<BExpr> args = new ArrayList<>();
    for (int i = 0; i < c.args().size(); i++) {
      args.add(adapt(lx(c.args().get(i)), declaredParamType(m, i)));
    }
    if (m.isConstructor()
        && m.owner() != cls
        && (outerThisFields.containsKey(m.owner())
            || !captureFields.getOrDefault(m.owner(), Map.of()).isEmpty())) {
      throw new IllegalStateException("unexpected constructor call to local class");
    }
    return new BExpr.Call(recv, m, args, c.kind(), c.type(), c.span());
  }

  private BExpr newExpr(BExpr.New n) {
    MethodSymbol k = n.ctor();
    ClassSymbol target = n.type().sym();
    List<BExpr> args = new ArrayList<>();
    FieldSymbol outer = outerThisFields.get(target);
    if (outer != null) {
      ClassSymbol enclosing = target.outer();
      args.add(enclosing == cls ? self(n.span()) : outerThis(enclosing, n.span()));
    }
    for (int i = 0; i < n.args().size(); i++) {
      args.add(adapt(lx(n.args().get(i)), declaredParamType(k, i)));
    }
    for (VarSymbol v : captureFields.getOrDefault(target, Map.of()).keySet()) {
      args.add(lx(new BExpr.Local(v, n.span())));
    }
    return new BExpr.New(n.type(), k, args, n.span());
  }

  private BExpr objectInit(BExpr.ObjectInit oi) {
    Span span = oi.span();
    BExpr created = lx(oi.creation());
    VarSymbol tmp = temp("obj", created.type(), span);
    List<BStmt> stmts = new ArrayList<>();
    for (BExpr.MemberInit mi : oi.inits()) {
      BExpr target = new BExpr.Local(tmp, span);
      BExpr value = lx(mi.value());
      Symbol member = mi.member();
      switch (member) {
        case PropertySymbol p -> {
          MethodSymbol setter = p.setter();
          stmts.add(
              new BStmt.ExprStmt(
                  new BExpr.Call(
                      target,
                      setter,
                      List.of(adapt(value, setter.params().getFirst().type())),
                      setter.owner().isInterface() ? CallKind.INTERFACE : CallKind.VIRTUAL,
                      PrimType.VOID,
                      span),
                  span));
        }
        case FieldSymbol f ->
            stmts.add(
                new BStmt.ExprStmt(
                    new BExpr.Assign(
                        new BLValue.FieldLV(target, f, f.type()),
                        adapt(value, f.type()),
                        f.type(),
                        span),
                    span));
        case MethodSymbol setter ->
            stmts.add(
                new BStmt.ExprStmt(
                    new BExpr.Call(
                        target,
                        setter,
                        List.of(adapt(value, setter.params().getFirst().type())),
                        setter.owner().isInterface() ? CallKind.INTERFACE : CallKind.VIRTUAL,
                        PrimType.VOID,
                        span),
                    span));
        default -> throw new IllegalStateException(member.toString());
      }
    }
    return new BExpr.Let(
        tmp, created, new BExpr.Block(stmts, new BExpr.Local(tmp, span), span), span);
  }

  private BExpr binary(BExpr.Binary b) {
    BExpr l = lx(b.left());
    BExpr r = lx(b.right());
    if (b.checked()) {
      String name =
          switch (b.op()) {
            case ADD -> "addExact";
            case SUB -> "subtractExact";
            case MUL -> "multiplyExact";
            default -> null;
          };
      if (name != null) {
        return mathExact(name, List.of(l, r), (PrimType) b.type(), b.span());
      }
    }
    return new BExpr.Binary(b.op(), l, r, b.type(), false, b.span());
  }

  private BExpr mathExact(String name, List<BExpr> args, PrimType t, Span span) {
    ClassSymbol math = syms.lookup("java/lang/Math");
    for (MethodSymbol m : math.methods(name)) {
      if (m.params().size() == args.size() && m.params().getFirst().type() == t) {
        return new BExpr.Call(null, m, args, CallKind.STATIC, t, span);
      }
    }
    throw new IllegalStateException("Math." + name + " for " + t);
  }

  private BExpr valueEquals(BExpr.ValueEquals ve) {
    BExpr l = lx(ve.left());
    BExpr r = lx(ve.right());
    BExpr eq;
    if (isIdentityType(l.type()) || isIdentityType(r.type())) {
      eq =
          new BExpr.Binary(
              ve.negate() ? BinOp.REF_NE : BinOp.REF_EQ, l, r, PrimType.BOOLEAN, false, ve.span());
      return eq;
    }
    eq = objectsEquals(l, r, ve.span());
    return ve.negate() ? new BExpr.Unary(BExpr.UnOp.NOT, eq, PrimType.BOOLEAN, ve.span()) : eq;
  }

  /** Types whose equals is identity: enums and {@code Class}. */
  private boolean isIdentityType(Type t) {
    return t instanceof ClassType c
        && (c.sym().isEnum() || c.sym().binaryName().equals("java/lang/Class"));
  }

  private BExpr conv(BExpr.Conv c) {
    BExpr inner = lx(c.expr());
    return switch (c.kind()) {
      case NULL_CHECK -> requireNonNull(inner, null, c.span());
      case NON_NULL_ASSERT -> requireNonNull(inner, null, c.span());
      default -> new BExpr.Conv(inner, c.kind(), c.type(), c.span());
    };
  }

  // ------------------------------------------------------------------ assignments

  private BExpr assign(BExpr.Assign as) {
    BLValue lv = lowerLValue(as.target(), null);
    BExpr value = adapt(lx(as.value()), lv.type());
    if (lv instanceof BLValue.PropertyLV p) {
      VarSymbol tmp = temp("v", value.type(), as.span());
      List<BStmt> stmts =
          List.of(
              new BStmt.ExprStmt(
                  setterCall(p, new BExpr.Local(tmp, as.span()), as.span()), as.span()));
      return new BExpr.Let(
          tmp,
          value,
          new BExpr.Block(stmts, new BExpr.Local(tmp, as.span()), as.span()),
          as.span());
    }
    if (lv instanceof BLValue.LocalLV l) {
      BExpr captured = capturedAccess(l.var(), as.span());
      if (captured != null) {
        throw new IllegalStateException("assignment to captured variable " + l.var());
      }
    }
    return new BExpr.Assign(lv, value, as.type(), as.span());
  }

  /** Lowers the subexpressions of an lvalue; with {@code temps}, receivers are hoisted to temps. */
  private BLValue lowerLValue(BLValue lv, List<VarSymbol[]> temps) {
    return switch (lv) {
      case BLValue.LocalLV l -> l;
      case BLValue.FieldLV f -> {
        BExpr recv = f.receiver() == null ? null : lx(f.receiver());
        if (recv != null && f.field().property() != null) {
          // writes to an auto-property's backing field from its own class stay field writes
        }
        yield new BLValue.FieldLV(hoist(recv, temps), f.field(), f.type());
      }
      case BLValue.ArrayLV a ->
          new BLValue.ArrayLV(hoist(lx(a.array()), temps), hoist(lx(a.index()), temps), a.type());
      case BLValue.PropertyLV p -> {
        BExpr recv = p.receiver() == null ? null : hoist(lx(p.receiver()), temps);
        List<BExpr> extra = new ArrayList<>();
        for (int i = 0; i < p.extraArgs().size(); i++) {
          extra.add(
              hoist(adapt(lx(p.extraArgs().get(i)), declaredParamType(p.setter(), i)), temps));
        }
        if (p.property() != null) {
          FieldSymbol f = directBackingField(p.setter(), recv);
          if (f != null) {
            yield new BLValue.FieldLV(f.isStatic() ? null : recv, f, p.type());
          }
        }
        yield new BLValue.PropertyLV(
            recv, p.property(), p.getter(), p.setter(), extra, p.type(), p.kind());
      }
    };
  }

  /**
   * Evaluates {@code e} into a temp (recorded in {@code temps}) unless it is trivially
   * re-evaluable.
   */
  private BExpr hoist(BExpr e, List<VarSymbol[]> temps) {
    if (temps == null
        || e == null
        || e instanceof BExpr.Local
        || e instanceof BExpr.This
        || e instanceof BExpr.Const) {
      return e;
    }
    VarSymbol t = temp("r", e.type(), e.span());
    temps.add(new VarSymbol[] {t});
    hoisted.put(t, e);
    return new BExpr.Local(t, e.span());
  }

  private final Map<VarSymbol, BExpr> hoisted = new IdentityHashMap<>();

  /** Wraps {@code body} in Lets for the hoisted receiver temps (outermost first). */
  private BExpr withTemps(List<VarSymbol[]> temps, BExpr body, Span span) {
    for (int i = temps.size() - 1; i >= 0; i--) {
      VarSymbol t = temps.get(i)[0];
      body = new BExpr.Let(t, hoisted.remove(t), body, span);
    }
    return body;
  }

  private BExpr read(BLValue lv, Span span) {
    return switch (lv) {
      case BLValue.LocalLV l -> new BExpr.Local(l.var(), span);
      case BLValue.FieldLV f -> new BExpr.Field(f.receiver(), f.field(), f.type(), span);
      case BLValue.ArrayLV a -> new BExpr.ArrayElem(a.array(), a.index(), a.type(), span);
      case BLValue.PropertyLV p -> {
        if (p.getter() == null) {
          throw new IllegalStateException("property without getter in compound assignment");
        }
        yield new BExpr.Call(
            p.receiver(),
            p.getter(),
            p.extraArgs(),
            p.getter().isStatic()
                ? CallKind.STATIC
                : p.getter().owner().isInterface() ? CallKind.INTERFACE : CallKind.VIRTUAL,
            p.type(),
            span);
      }
    };
  }

  private BExpr write(BLValue lv, BExpr value, Span span) {
    if (lv instanceof BLValue.PropertyLV p) {
      VarSymbol tmp = temp("v", value.type(), span);
      return new BExpr.Let(
          tmp,
          value,
          new BExpr.Block(
              List.of(new BStmt.ExprStmt(setterCall(p, new BExpr.Local(tmp, span), span), span)),
              new BExpr.Local(tmp, span),
              span),
          span);
    }
    return new BExpr.Assign(lv, value, lv.type(), span);
  }

  private BExpr setterCall(BLValue.PropertyLV p, BExpr value, Span span) {
    List<BExpr> args = new ArrayList<>(p.extraArgs());
    args.add(adapt(value, p.setter().params().get(p.extraArgs().size()).type()));
    BExpr recv = p.receiver();
    CallKind kind =
        p.setter().isStatic()
            ? CallKind.STATIC
            : p.setter().owner().isInterface() ? CallKind.INTERFACE : CallKind.VIRTUAL;
    Type ret = p.setter().returnType() == null ? PrimType.VOID : p.setter().returnType();
    return new BExpr.Call(p.setter().isStatic() ? null : recv, p.setter(), args, kind, ret, span);
  }

  private BExpr compound(BExpr.CompoundAssign ca) {
    Span span = ca.span();
    if (ca.target() instanceof BLValue.LocalLV l
        && ca.op() != null
        && !ca.checked()
        && capturedAccess(l.var(), span) == null) {
      return new BExpr.CompoundAssign(
          l, ca.op(), lx(ca.value()), ca.opType(), ca.type(), false, span);
    }
    List<VarSymbol[]> temps = new ArrayList<>();
    BLValue lv = lowerLValue(ca.target(), temps);
    BExpr v = lx(ca.value());
    BExpr result;
    if (ca.op() == null) {
      // x ??= v: assign only when null.
      VarSymbol cur = temp("cur", lv.type(), span);
      BExpr test =
          new BExpr.Binary(
              BinOp.REF_NE,
              new BExpr.Local(cur, span),
              nullConst(span),
              PrimType.BOOLEAN,
              false,
              span);
      BExpr assign = write(lv, adapt(v, lv.type()), span);
      Type nn = ca.type();
      result =
          new BExpr.Let(
              cur,
              read(lv, span),
              new BExpr.Conditional(
                  test,
                  new BExpr.Conv(new BExpr.Local(cur, span), ConvKind.RETYPE, nn, span),
                  adapt(assign, nn),
                  nn,
                  span),
              span);
    } else {
      BExpr cur = read(lv, span);
      BExpr computed;
      if (ca.op() == BinOp.ADD && ca.opType() instanceof ClassType) {
        computed = new BExpr.Concat(List.of(cur, v), syms.stringType(), span);
      } else if (ca.checked()
          && (ca.op() == BinOp.ADD || ca.op() == BinOp.SUB || ca.op() == BinOp.MUL)) {
        String name =
            ca.op() == BinOp.ADD
                ? "addExact"
                : ca.op() == BinOp.SUB ? "subtractExact" : "multiplyExact";
        computed =
            mathExact(name, List.of(adapt(cur, ca.opType()), v), (PrimType) ca.opType(), span);
      } else {
        computed = new BExpr.Binary(ca.op(), adapt(cur, ca.opType()), v, ca.opType(), false, span);
      }
      result = write(lv, adapt(computed, lv.type()), span);
    }
    return withTemps(temps, result, span);
  }

  private BExpr incDec(BExpr.IncDec id) {
    Span span = id.span();
    PrimType prim = types.primitiveView(id.type());
    if (id.target() instanceof BLValue.LocalLV l
        && id.type() instanceof PrimType
        && !id.checked()
        && capturedAccess(l.var(), span) == null) {
      return id; // codegen uses iinc / direct arithmetic for primitive locals
    }
    List<VarSymbol[]> temps = new ArrayList<>();
    BLValue lv = lowerLValue(id.target(), temps);
    PrimType opType = Types.promote(prim);
    BExpr one = new BExpr.Const(ConstFold1.one(opType), opType, span);
    VarSymbol old = temp("old", id.type(), span);
    BExpr oldVal = new BExpr.Local(old, span);
    BExpr computed;
    if (id.checked() && (opType == PrimType.INT || opType == PrimType.LONG)) {
      computed =
          mathExact(
              id.increment() ? "addExact" : "subtractExact",
              List.of(adapt(oldVal, opType), one),
              opType,
              span);
    } else {
      computed =
          new BExpr.Binary(
              id.increment() ? BinOp.ADD : BinOp.SUB,
              adapt(oldVal, opType),
              one,
              opType,
              false,
              span);
    }
    BExpr assign = write(lv, adapt(computed, lv.type()), span);
    BExpr result;
    if (id.prefix()) {
      result = new BExpr.Let(old, read(lv, span), assign, span);
    } else {
      result =
          new BExpr.Let(
              old,
              read(lv, span),
              new BExpr.Block(List.of(new BStmt.ExprStmt(assign, span)), oldVal, span),
              span);
    }
    return withTemps(temps, result, span);
  }

  /** Small constant helper. */
  private static final class ConstFold1 {
    static Object one(PrimType t) {
      return switch (t) {
        case LONG -> 1L;
        case FLOAT -> 1f;
        case DOUBLE -> 1d;
        default -> 1;
      };
    }
  }

  // ------------------------------------------------------------------ null safety

  private BExpr safeAccess(BExpr.SafeAccess sa) {
    Span span = sa.span();
    BExpr recv = lx(sa.receiver());
    BExpr test =
        new BExpr.Binary(
            BinOp.REF_NE,
            new BExpr.Local(sa.tmp(), span),
            nullConst(span),
            PrimType.BOOLEAN,
            false,
            span);
    BExpr present = lx(sa.whenPresent());
    if (sa.type() == PrimType.VOID) {
      return new BExpr.Let(
          sa.tmp(),
          recv,
          new BExpr.Conditional(test, present, new BExpr.Nop(span), PrimType.VOID, span),
          span);
    }
    return new BExpr.Let(
        sa.tmp(),
        recv,
        new BExpr.Conditional(test, adapt(present, sa.type()), nullConst(span), sa.type(), span),
        span);
  }

  private BExpr coalesce(BExpr.Coalesce co) {
    Span span = co.span();
    BExpr left = lx(co.left());
    VarSymbol tmp = temp("c", left.type(), span);
    BExpr test =
        new BExpr.Binary(
            BinOp.REF_NE,
            new BExpr.Local(tmp, span),
            nullConst(span),
            PrimType.BOOLEAN,
            false,
            span);
    return new BExpr.Let(
        tmp,
        left,
        new BExpr.Conditional(
            test,
            adapt(new BExpr.Local(tmp, span), co.type()),
            adapt(lx(co.right()), co.type()),
            co.type(),
            span),
        span);
  }

  private BExpr isPattern(BExpr.IsPattern ip) {
    BExpr subject = lx(ip.expr());
    Span span = ip.span();
    if (subject instanceof BExpr.Local) {
      return match(ip.pattern(), subject, subject.type());
    }
    VarSymbol tmp = temp("is", subject.type(), span);
    return new BExpr.Let(
        tmp, subject, match(ip.pattern(), new BExpr.Local(tmp, span), subject.type()), span);
  }

  private BExpr await(BExpr.Await aw) {
    BExpr task = lx(aw.task());
    Span span = aw.span();
    if (aw.type() == PrimType.VOID) {
      BExpr call =
          new BExpr.Call(
              null,
              aw.join(),
              List.of(task),
              CallKind.STATIC,
              syms.objectType().withNullness(Nullness.NULLABLE),
              span);
      return new BExpr.Block(List.of(new BStmt.ExprStmt(call, span)), new BExpr.Nop(span), span);
    }
    BExpr call =
        new BExpr.Call(
            null,
            aw.join(),
            List.of(task),
            CallKind.STATIC,
            aw.type() instanceof PrimType p ? syms.boxed(p) : aw.type(),
            span);
    return adapt(call, aw.type());
  }

  // ------------------------------------------------------------------ lambdas

  private BExpr lambda(BExpr.Lambda l) {
    Span span = l.span();
    boolean capturesThis = l.capturesThis();
    List<VarSymbol> caps = new ArrayList<>();
    for (VarSymbol v : l.captures()) {
      if (isCapturedField(v)) {
        capturesThis = true;
      } else {
        caps.add(v);
      }
    }
    if (cls.isInterface() && capturesThis && method != null && method.isStatic()) {
      capturesThis = false;
    }
    String base =
        method == null
            ? "static"
            : method.name().equals("<init>")
                ? "new"
                : method.name().equals("<clinit>")
                    ? "static"
                    : method.name().replace('<', '$').replace('>', '$');
    MethodSymbol impl =
        new MethodSymbol(
            "lambda$" + base + "$" + (lambdaCounter++),
            cls,
            Flags.PRIVATE | Flags.SYNTHETIC | (capturesThis ? 0 : Flags.STATIC));
    List<VarSymbol> params = new ArrayList<>(caps);
    params.addAll(l.params());
    impl.setParams(paramsFrom(params));
    Type ret = l.returnType() == null ? PrimType.VOID : l.returnType();
    if (ret instanceof Type.NullType || ret instanceof Type.NeverType) {
      ret = syms.objectType();
    }
    impl.setReturnType(ret);
    // Lower the body in the implementation method's context.
    MethodSymbol savedMethod = method;
    Type savedRet = returnType;
    int savedTemps = tempCounter;
    enterMethod(impl, ret);
    BStmt body = ls(l.body());
    if (ret == PrimType.VOID) {
      body = new BStmt.Block(List.of(body, new BStmt.Return(null, span)), span);
    }
    method = savedMethod;
    returnType = savedRet;
    tempCounter = savedTemps;
    extraMethods.add(new BClass.Method(impl, params, body, span));
    List<BExpr> captured = new ArrayList<>();
    if (capturesThis) {
      captured.add(self(span));
    }
    for (VarSymbol v : caps) {
      captured.add(lx(new BExpr.Local(v, span)));
    }
    List<Type> ftParams = new ArrayList<>();
    for (VarSymbol p : l.params()) {
      ftParams.add(p.type());
    }
    BExpr.ImplKind kind =
        capturesThis
            ? (cls.isInterface() ? BExpr.ImplKind.INTERFACE : BExpr.ImplKind.SPECIAL)
            : BExpr.ImplKind.STATIC;
    Types.MethodType ft = types.functionType(l.type());
    return new BExpr.Indy(
        l.type(),
        l.sam(),
        impl,
        kind,
        captured,
        ft == null ? ftParams : ft.params(),
        ft == null ? ret : ft.ret(),
        span);
  }

  private BExpr methodRef(BExpr.MethodRef m) {
    Span span = m.span();
    Types.MethodType ft = types.functionType(m.type());
    MethodSymbol target = m.target();
    boolean needsSynthetic =
        m.kind() == BExpr.RefKind.ARRAY_CONSTRUCTOR
            || target.isVarargs()
            || (m.kind() == BExpr.RefKind.CONSTRUCTOR
                && (outerThisFields.containsKey(target.owner())
                    || !captureFields.getOrDefault(target.owner(), Map.of()).isEmpty()))
            || target.params().size()
                != ft.params().size() - (m.kind() == BExpr.RefKind.UNBOUND ? 1 : 0)
            || target.has(Flags.PROTECTED)
                && !target.owner().packageName().equals(cls.packageName());
    if (needsSynthetic) {
      BExpr.Lambda synthetic = syntheticLambda(m, ft);
      BExpr lowered = lambda(synthetic);
      if (m.kind() == BExpr.RefKind.BOUND) {
        // The receiver is evaluated (and null-checked) once, when the reference is created.
        VarSymbol recv = synthetic.captures().getFirst();
        return new BExpr.Let(recv, requireNonNull(lx(m.receiver()), null, span), lowered, span);
      }
      return lowered;
    }
    BExpr.ImplKind kind =
        switch (m.kind()) {
          case STATIC -> BExpr.ImplKind.STATIC;
          case CONSTRUCTOR -> BExpr.ImplKind.NEW;
          default ->
              target.owner().isInterface() ? BExpr.ImplKind.INTERFACE : BExpr.ImplKind.VIRTUAL;
        };
    List<BExpr> captured = new ArrayList<>();
    if (m.kind() == BExpr.RefKind.BOUND) {
      captured.add(requireNonNull(lx(m.receiver()), null, span));
    }
    return new BExpr.Indy(m.type(), m.sam(), target, kind, captured, ft.params(), ft.ret(), span);
  }

  /** A lambda equivalent of a method reference that LambdaMetafactory cannot adapt directly. */
  private BExpr.Lambda syntheticLambda(BExpr.MethodRef m, Types.MethodType ft) {
    Span span = m.span();
    List<VarSymbol> params = new ArrayList<>();
    for (int i = 0; i < ft.params().size(); i++) {
      params.add(new VarSymbol("p" + i, ft.params().get(i), 0, VarSymbol.Kind.PARAM, span, -1));
    }
    List<VarSymbol> captures = new ArrayList<>();
    BExpr body;
    MethodSymbol target = m.target();
    switch (m.kind()) {
      case ARRAY_CONSTRUCTOR -> {
        Type.ArrayType at = (Type.ArrayType) m.refType();
        body =
            new BExpr.NewArray(
                at,
                List.of(adapt(new BExpr.Local(params.getFirst(), span), PrimType.INT)),
                null,
                span);
      }
      case CONSTRUCTOR ->
          body =
              new BExpr.New(
                  (ClassType) m.refType(), target, callArgs(target, params, 0, span), span);
      case STATIC ->
          body =
              new BExpr.Call(
                  null,
                  target,
                  callArgs(target, params, 0, span),
                  CallKind.STATIC,
                  target.returnType(),
                  span);
      case UNBOUND -> {
        BExpr recv =
            adapt(new BExpr.Local(params.getFirst(), span), target.owner().thisType().erasure());
        body =
            new BExpr.Call(
                recv,
                target,
                callArgs(target, params, 1, span),
                target.owner().isInterface() ? CallKind.INTERFACE : CallKind.VIRTUAL,
                target.returnType(),
                span);
      }
      case BOUND -> {
        // Bound by methodRef() to the receiver, evaluated once at creation.
        VarSymbol recvVar = temp("recv", m.receiver().type(), span);
        captures.add(recvVar);
        body =
            new BExpr.Call(
                new BExpr.Local(recvVar, span),
                target,
                callArgs(target, params, 0, span),
                target.owner().isInterface() ? CallKind.INTERFACE : CallKind.VIRTUAL,
                target.returnType(),
                span);
      }
      default -> throw new IllegalStateException();
    }
    BStmt stmt =
        ft.ret() == PrimType.VOID
            ? new BStmt.ExprStmt(body, span)
            : new BStmt.Return(adapt(body, ft.ret()), span);
    return new BExpr.Lambda(m.type(), m.sam(), params, stmt, ft.ret(), captures, false, span);
  }

  /**
   * Arguments for calling {@code target} with lambda params from index {@code from} (packing
   * varargs).
   */
  private List<BExpr> callArgs(MethodSymbol target, List<VarSymbol> params, int from, Span span) {
    List<BExpr> args = new ArrayList<>();
    List<MethodSymbol.Param> ps = target.params();
    int n = params.size() - from;
    boolean pack =
        target.isVarargs()
            && !(n == ps.size() && params.get(params.size() - 1).type() instanceof Type.ArrayType);
    int fixed = pack ? ps.size() - 1 : ps.size();
    for (int i = 0; i < fixed && from + i < params.size(); i++) {
      args.add(adapt(new BExpr.Local(params.get(from + i), span), ps.get(i).type()));
    }
    if (pack) {
      Type.ArrayType at = (Type.ArrayType) ps.getLast().type();
      List<BExpr> elems = new ArrayList<>();
      for (int i = from + fixed; i < params.size(); i++) {
        elems.add(adapt(new BExpr.Local(params.get(i), span), at.elem()));
      }
      args.add(new BExpr.NewArray((Type.ArrayType) at.erasure(), List.of(), elems, span));
    }
    for (int i = args.size(); i < ps.size(); i++) {
      args.add(defaultConst(ps.get(i), span));
    }
    return args;
  }

  /** Unused helper retained for symmetry. */
  static boolean isSynthetic(Symbol s) {
    return s.has(Flags.SYNTHETIC);
  }

  /** Local helper so generic list creation reads cleanly. */
  static <T> List<T> listOf(T a) {
    List<T> l = new ArrayList<>();
    l.add(a);
    return l;
  }
}

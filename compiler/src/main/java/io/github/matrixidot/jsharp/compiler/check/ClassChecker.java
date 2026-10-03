package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.ast.Accessor;
import io.github.matrixidot.jsharp.compiler.ast.Body;
import io.github.matrixidot.jsharp.compiler.ast.Decl;
import io.github.matrixidot.jsharp.compiler.ast.EnumConstant;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.ast.Stmt;
import io.github.matrixidot.jsharp.compiler.ast.TypeParam;
import io.github.matrixidot.jsharp.compiler.ast.VarDeclarator;
import io.github.matrixidot.jsharp.compiler.bound.BClass;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.bound.BLValue;
import io.github.matrixidot.jsharp.compiler.bound.BStmt;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.resolve.Suggestions;
import io.github.matrixidot.jsharp.compiler.resolve.TypeScope;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.PropertySymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symbol;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Descriptors;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import io.github.matrixidot.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks classes: member bodies (methods, constructors, accessors, initializers, enum constants,
 * top-level statements) and class-level rules (overrides and {@code override}, abstract members,
 * constructor chaining, final fields, variance). Produces one {@link BClass} per class.
 */
public final class ClassChecker {
  private ClassChecker() {}

  private static Map<ClassSymbol, Scope> localScopes(Attr a) {
    return a.localScopes;
  }

  /** Checks all (non-local) classes; local/anonymous classes appear as nested results. */
  public static List<BClass> checkAll(Attr a, List<ClassSymbol> classes) {
    List<BClass> out = new ArrayList<>();
    for (ClassSymbol c : classes) {
      if (c.has(Flags.MODULE)) {
        a.registerModule(c);
      }
    }
    for (ClassSymbol c : classes) {
      out.add(check(a, c));
    }
    checkCaptures(a);
    return out;
  }

  static BClass checkLocal(Attr a, ClassSymbol c, Scope enclosing) {
    localScopes(a).put(c, enclosing);
    Env saved = a.env;
    try {
      return check(a, c);
    } finally {
      a.env = saved;
    }
  }

  // ------------------------------------------------------------------ environments

  private static Scope rootScope(Attr a, ClassSymbol c) {
    Scope enclosing = null;
    for (ClassSymbol x = c; x != null; x = x.outer()) {
      Scope s = localScopes(a).get(x);
      if (s != null) {
        enclosing = s;
        break;
      }
    }
    if (enclosing == null) {
      return Scope.root();
    }
    return new Scope(enclosing, Scope.Boundary.CLASS, null, localBoundaryClass(a, c));
  }

  private static ClassSymbol localBoundaryClass(Attr a, ClassSymbol c) {
    for (ClassSymbol x = c; x != null; x = x.outer()) {
      if (localScopes(a).containsKey(x)) {
        return x;
      }
    }
    return c;
  }

  static Env staticEnv(Attr a, ClassSymbol c) {
    Env e = new Env();
    e.cls = c;
    e.typeScope = a.memberEnter.classScope(c);
    e.isStatic = true;
    e.scope = rootScope(a, c);
    e.returnType = PrimType.VOID;
    e.localClasses = new ArrayList<>();
    return e;
  }

  static Env fieldEnv(Attr a, FieldSymbol f) {
    Env e = staticEnv(a, f.owner());
    e.isStatic = f.isStatic();
    e.inConstructor = !f.isStatic();
    return e;
  }

  static Env methodEnv(Attr a, MethodSymbol m) {
    Env e = new Env();
    e.cls = m.owner();
    TypeScope base = a.memberEnter.classScope(m.owner());
    e.typeScope = m.typeParams().isEmpty() ? base : new TypeScope.MethodLevel(m.typeParams(), base);
    e.method = m;
    e.isStatic = m.isStatic();
    e.scope = rootScope(a, m.owner()).child();
    e.inConstructor = m.isConstructor();
    e.beforeSuperCall = m.isConstructor();
    e.isAsync = m.has(Flags.ASYNC) || m.has(Flags.ENTRY_POINT);
    e.localClasses = new ArrayList<>();
    if (m.isConstructor()) {
      e.returnType = PrimType.VOID;
    } else if (m.has(Flags.ASYNC)) {
      Span s = m.decl() instanceof Decl.Method md ? md.nameSpan() : Span.NONE;
      Env saved = a.env;
      a.env = e;
      e.returnType = a.patterns.asyncPayload(m.returnType(), s, "method");
      a.env = saved;
    } else {
      e.returnType = m.returnType();
    }
    return e;
  }

  private static List<VarSymbol> declareParams(Attr a, Env e, MethodSymbol m) {
    List<VarSymbol> params = new ArrayList<>();
    List<io.github.matrixidot.jsharp.compiler.ast.Param> syntax = null;
    if (m.decl() instanceof Decl.Method md && md.params().size() == m.params().size()) {
      syntax = md.params();
    } else if (m.decl() instanceof Decl.Constructor k
        && k.params() != null
        && k.params().size() == m.params().size()) {
      syntax = k.params();
    }
    for (int i = 0; i < m.params().size(); i++) {
      MethodSymbol.Param p = m.params().get(i);
      Span span = syntax != null ? syntax.get(i).nameSpan() : Span.NONE;
      boolean isFinal =
          syntax != null
              && syntax
                  .get(i)
                  .modifiers()
                  .has(io.github.matrixidot.jsharp.compiler.ast.Modifier.FINAL);
      VarSymbol v =
          e.newVar(
              p.name(),
              p.type() == null ? Type.ErrorType.INSTANCE : p.type(),
              isFinal ? Flags.FINAL : 0,
              VarSymbol.Kind.PARAM,
              span);
      if (!p.name().equals("_")) {
        e.scope.vars.put(p.name(), v);
      }
      e.flow.assigned.set(v.id());
      params.add(v);
    }
    return params;
  }

  // ------------------------------------------------------------------ method bodies

  /** Attributes a method or constructor body (also used for inferred return types). */
  static BClass.Method attribMethodBody(Attr a, MethodSymbol m) {
    BClass.Method cached = a.inferredMethodBodies.get(m);
    if (cached != null) {
      return cached;
    }
    Env saved = a.env;
    Env e = methodEnv(a, m);
    a.env = e;
    try {
      List<VarSymbol> params = declareParams(a, e, m);
      Body body =
          switch (m.decl()) {
            case Decl.Method md -> md.body();
            case Decl.Constructor k -> k.body();
            default -> null;
          };
      if (body == null) {
        return new BClass.Method(m, params, null, Span.NONE);
      }
      Span span = body.span();
      BStmt stmt;
      if (m.isConstructor()) {
        stmt = constructorBody(a, m, (Decl.Constructor) m.decl(), body);
      } else if (body instanceof Body.ExprBody eb) {
        if (m.returnType() == null) {
          BExpr v = a.value(eb.expr(), null);
          Type t = a.types.uncapture(v.type());
          if (t instanceof Type.NullType) {
            a.error(
                Code.CANNOT_INFER,
                ((Decl.Method) m.decl()).nameSpan(),
                "cannot infer a return type from 'null'");
            t = Type.ErrorType.INSTANCE;
          }
          m.setReturnType(t);
          stmt = new BStmt.Return(v, eb.expr().span());
        } else if (e.returnType == PrimType.VOID) {
          BExpr v = a.expr(eb.expr(), null);
          if (!a.stmts.isStatementExpression(eb.expr())
              && !v.type().isError()
              && v.type() != PrimType.VOID) {
            a.report(
                a.err(Code.TYPE_MISMATCH, eb.expr().span(), "a void method cannot return a value")
                    .help("declare a return type, or use a block body"));
          }
          stmt = new BStmt.ExprStmt(v, eb.expr().span());
        } else {
          stmt = new BStmt.Return(a.exprCoerced(eb.expr(), e.returnType), eb.expr().span());
        }
      } else {
        BStmt.Block blk = a.stmts.block(((Body.Block) body).block());
        if (e.flow.alive
            && e.returnType != null
            && e.returnType != PrimType.VOID
            && !e.returnType.isError()) {
          Span at = m.decl() instanceof Decl.Method md ? md.nameSpan() : span;
          a.report(
              a.err(
                  Code.MISSING_RETURN,
                  at,
                  "method '"
                      + m.name()
                      + "' must return a value of type "
                      + e.returnType.display()
                      + " on every path"));
        }
        stmt = blk;
      }
      a.pendingLocal.computeIfAbsent(m, x -> new ArrayList<>()).addAll(e.localClasses);
      return new BClass.Method(m, params, stmt, span);
    } finally {
      a.env = saved;
    }
  }

  private static BStmt constructorBody(Attr a, MethodSymbol m, Decl.Constructor k, Body body) {
    Env e = a.env;
    ClassSymbol c = m.owner();
    e.flow.assignedFields.addAll(initializedByInitializers(a, c));
    List<BStmt> out = new ArrayList<>();
    boolean delegates = false;
    if (body instanceof Body.Block bb) {
      List<Stmt> stmts = bb.block().stmts();
      boolean explicitChain = !stmts.isEmpty() && isChainCall(stmts.getFirst());
      if (explicitChain) {
        Expr.Call call = (Expr.Call) ((Stmt.ExprStmt) stmts.getFirst()).expr();
        delegates = call.callee() instanceof Expr.This;
      } else {
        e.beforeSuperCall = false;
        checkImplicitSuper(a, c, k.nameSpan());
      }
      Scope saved = e.scope;
      e.scope = saved.child();
      out.addAll(a.stmts.statements(stmts, 0));
      e.scope = saved;
    } else {
      e.beforeSuperCall = false;
      checkImplicitSuper(a, c, k.nameSpan());
      out.add(new BStmt.ExprStmt(a.expr(((Body.ExprBody) body).expr(), null), body.span()));
    }
    if (!delegates && e.flow.alive && !m.has(Flags.COMPACT_CTOR)) {
      for (FieldSymbol f : c.fields()) {
        if (f.has(Flags.FINAL)
            && !f.isStatic()
            && !f.has(Flags.BACKING_FIELD)
            && !e.flow.assignedFields.contains(f)
            && !c.isRecord()) {
          a.report(
              a.err(
                      Code.FINAL_FIELD_UNINITIALIZED,
                      k.nameSpan(),
                      "final field '" + f.name() + "' is not initialized by this constructor")
                  .note(
                      "declared here",
                      a.file(),
                      f.declarator() != null ? f.declarator().nameSpan() : k.nameSpan()));
        }
      }
    }
    return new BStmt.Block(out, body.span());
  }

  private static boolean isChainCall(Stmt s) {
    return s instanceof Stmt.ExprStmt es
        && es.expr() instanceof Expr.Call call
        && (call.callee() instanceof Expr.Super
            || call.callee() instanceof Expr.This t && t.qualifier() == null);
  }

  /** An implicit {@code super()} needs an accessible no-argument superclass constructor. */
  private static void checkImplicitSuper(Attr a, ClassSymbol c, Span at) {
    if (c.isRecord() || c.isEnum() || c.isInterface() || c.has(Flags.ANONYMOUS)) {
      return;
    }
    ClassType sup = c.superclass();
    if (sup == null) {
      return;
    }
    for (MethodSymbol k : sup.sym().methods(MethodSymbol.CONSTRUCTOR)) {
      boolean noArgs =
          k.params().stream().allMatch(p -> p.hasDefault())
              || k.params().size() == 1 && k.isVarargs();
      if (noArgs && a.lookup.isAccessible(k, sup.sym(), null, c)) {
        return;
      }
    }
    a.report(
        a.err(
                Code.INVALID_CONSTRUCTOR_CALL,
                at,
                "superclass " + sup.display() + " has no accessible no-argument constructor")
            .help("call one of its constructors explicitly: super(...)"));
  }

  /** Final fields assigned by field initializers or instance initializer blocks. */
  private static Set<FieldSymbol> initializedByInitializers(Attr a, ClassSymbol c) {
    Set<FieldSymbol> out = new HashSet<>();
    for (FieldSymbol f : c.fields()) {
      if (f.declarator() != null && f.declarator().init() != null) {
        out.add(f);
      }
      if (f.has(Flags.BACKING_FIELD)
          && f.property() != null
          && f.property().decl() != null
          && f.property().decl().initializer() != null) {
        out.add(f);
      }
    }
    Set<FieldSymbol> fromBlocks = a.initAssigned.get(c);
    if (fromBlocks != null) {
      out.addAll(fromBlocks);
    }
    return out;
  }

  // ------------------------------------------------------------------ classes

  static BClass check(Attr a, ClassSymbol c) {
    List<BClass.Method> methods = new ArrayList<>();
    List<BStmt> instanceInit = new ArrayList<>();
    List<BStmt> staticInit = new ArrayList<>();
    List<BClass> nested = new ArrayList<>();
    List<BClass.Bridge> bridges = new ArrayList<>();
    Env saved = a.env;
    a.env = staticEnv(a, c);
    try {
      computeConstants(a, c);
      if (!c.has(Flags.MODULE)) {
        checkOverrides(a, c, bridges);
        checkAbstractImplemented(a, c);
        checkVariance(a, c);
        checkDefaultArgs(a, c);
      }
      // Initializers first: constructors need to know which final fields they assign.
      initializers(a, c, instanceInit, staticInit, nested);
      Set<MethodSymbol> done = new HashSet<>();
      for (MethodSymbol m : c.allMethods()) {
        if (!done.add(m)) {
          continue;
        }
        methods.add(memberBody(a, c, m, nested));
      }
      if (c.has(Flags.MODULE)) {
        checkDefaultArgs(a, c);
      }
      checkGeneratedCtorFinals(a, c);
    } finally {
      a.env = saved;
    }
    List<VarSymbol> captures = new ArrayList<>(a.localClassCaptures.getOrDefault(c, Set.of()));
    boolean outerThis = (c.has(Flags.LOCAL) || c.has(Flags.ANONYMOUS)) && !c.has(Flags.STATIC);
    return new BClass(c, methods, instanceInit, staticInit, nested, bridges, captures, outerThis);
  }

  private static BClass.Method memberBody(
      Attr a, ClassSymbol c, MethodSymbol m, List<BClass> nested) {
    BClass.Method result;
    if (m.has(Flags.ENTRY_POINT)) {
      result = entryPoint(a, c, m);
    } else if (m.property() != null && m.decl() instanceof Decl.Property p) {
      result = accessorBody(a, m, m.property(), p);
    } else if (m.decl() instanceof Decl.Method || m.decl() instanceof Decl.Constructor) {
      result = attribMethodBody(a, m);
      a.inferredMethodBodies.remove(m);
    } else {
      Env e = methodEnv(a, m);
      Env saved = a.env;
      a.env = e;
      List<VarSymbol> params = declareParams(a, e, m);
      a.env = saved;
      if (m.isConstructor()
          && m.has(Flags.GENERATED)
          && !c.isRecord()
          && !c.isEnum()
          && !c.has(Flags.MODULE)) {
        a.env = staticEnv(a, c);
        checkImplicitSuper(a, c, c.decl() != null ? c.decl().nameSpan() : Span.NONE);
        a.env = saved;
      }
      result = new BClass.Method(m, params, null, Span.NONE);
    }
    collectLocalClasses(a, result, nested);
    return result;
  }

  private static void collectLocalClasses(Attr a, BClass.Method m, List<BClass> nested) {
    List<BClass> pending = a.pendingLocal.remove(m.sym());
    if (pending != null) {
      nested.addAll(pending);
    }
  }

  private static BClass.Method entryPoint(Attr a, ClassSymbol module, MethodSymbol main) {
    Env e = methodEnv(a, main);
    Env saved = a.env;
    a.env = e;
    try {
      List<VarSymbol> params = declareParams(a, e, main);
      // `args` is visible to top-level statements, like C#.
      List<Stmt> stmts = new ArrayList<>();
      Span span = Span.NONE;
      for (Decl d : module.unit().members()) {
        if (d instanceof Decl.TopLevelStmt t) {
          stmts.add(t.stmt());
          span = span == Span.NONE ? t.span() : span.to(t.span());
        }
      }
      List<BStmt> body = a.stmts.statements(stmts, 0);
      a.pendingLocal.computeIfAbsent(main, k -> new ArrayList<>()).addAll(e.localClasses);
      return new BClass.Method(main, params, new BStmt.Block(body, span), span);
    } finally {
      a.env = saved;
    }
  }

  private static BClass.Method accessorBody(
      Attr a, MethodSymbol m, PropertySymbol p, Decl.Property decl) {
    Env e = methodEnv(a, m);
    e.accessorOf = p;
    e.backingField = p.backingField();
    Env saved = a.env;
    a.env = e;
    try {
      List<VarSymbol> params = declareParams(a, e, m);
      boolean isGetter = m == p.getter();
      Body body = null;
      if (decl.getter() != null && isGetter) {
        body = new Body.ExprBody(decl.getter());
      } else if (decl.accessors() != null) {
        for (Accessor acc : decl.accessors()) {
          boolean match =
              isGetter ? acc.kind() == Accessor.Kind.GET : acc.kind() != Accessor.Kind.GET;
          if (match) {
            body = acc.body();
          }
        }
      }
      if (body == null) {
        return new BClass.Method(m, params, null, decl.span()); // auto accessor: generated later
      }
      if (!isGetter) {
        e.returnType = PrimType.VOID;
        e.inConstructor = p.isInitOnly();
      }
      BStmt stmt;
      if (body instanceof Body.ExprBody eb) {
        if (isGetter) {
          stmt = new BStmt.Return(a.exprCoerced(eb.expr(), e.returnType), eb.expr().span());
        } else {
          stmt = new BStmt.ExprStmt(a.expr(eb.expr(), null), eb.expr().span());
        }
      } else {
        stmt = a.stmts.block(((Body.Block) body).block());
        if (isGetter && e.flow.alive) {
          a.error(
              Code.MISSING_RETURN,
              decl.nameSpan(),
              "getter of '" + p.name() + "' must return a value on every path");
        }
      }
      a.pendingLocal.computeIfAbsent(m, k -> new ArrayList<>()).addAll(e.localClasses);
      return new BClass.Method(m, params, stmt, body.span());
    } finally {
      a.env = saved;
    }
  }

  /** Evaluates constant initializers of static final fields (for inlining and ConstantValue). */
  private static void computeConstants(Attr a, ClassSymbol c) {
    for (FieldSymbol f : c.fields()) {
      a.ensureConstant(f);
    }
  }

  private static void initializers(
      Attr a,
      ClassSymbol c,
      List<BStmt> instanceInit,
      List<BStmt> staticInit,
      List<BClass> nested) {
    List<Decl> members = c.has(Flags.MODULE) ? c.unit().members() : c.decl().members();
    // Enum constants come first in the static initializer.
    if (c.isEnum() && c.decl() != null) {
      for (EnumConstant ec : c.decl().enumConstants()) {
        staticInit.add(enumConstant(a, c, ec, nested));
      }
    }
    Set<FieldSymbol> assignedInBlocks = new HashSet<>();
    for (Decl d : members) {
      switch (d) {
        case Decl.Field f -> {
          for (VarDeclarator v : f.vars()) {
            FieldSymbol fs = c.field(v.name());
            if (fs == null || fs.declarator() != v || v.init() == null) {
              continue;
            }
            BExpr init = fieldInit(a, fs, nested);
            if (fs.constantValue() != null && fs.isStatic()) {
              continue; // emitted as a ConstantValue attribute
            }
            BExpr recv = fs.isStatic() ? null : new BExpr.This(c.thisType(), v.span());
            BStmt s =
                new BStmt.ExprStmt(
                    new BExpr.Assign(
                        new BLValue.FieldLV(recv, fs, fs.type()), init, fs.type(), v.span()),
                    v.span());
            (fs.isStatic() ? staticInit : instanceInit).add(s);
          }
        }
        case Decl.Property p when p.initializer() != null -> {
          PropertySymbol ps = c.property(p.name());
          if (ps == null || ps.backingField() == null || ps.decl() != p) {
            continue;
          }
          FieldSymbol bf = ps.backingField();
          Env e = fieldEnv(a, bf);
          Env saved = a.env;
          a.env = e;
          try {
            BExpr init = a.exprCoerced(p.initializer(), ps.type());
            BExpr recv = bf.isStatic() ? null : new BExpr.This(c.thisType(), p.span());
            (bf.isStatic() ? staticInit : instanceInit)
                .add(
                    new BStmt.ExprStmt(
                        new BExpr.Assign(
                            new BLValue.FieldLV(recv, bf, bf.type()), init, bf.type(), p.span()),
                        p.span()));
            nested.addAll(e.localClasses);
          } finally {
            a.env = saved;
          }
        }
        case Decl.Initializer init -> {
          Env e = staticEnv(a, c);
          e.isStatic = init.isStatic();
          e.inConstructor = !init.isStatic();
          Env saved = a.env;
          a.env = e;
          try {
            BStmt.Block b = a.stmts.block(init.body());
            (init.isStatic() ? staticInit : instanceInit).add(b);
            assignedInBlocks.addAll(e.flow.assignedFields);
            nested.addAll(e.localClasses);
          } finally {
            a.env = saved;
          }
        }
        default -> {}
      }
    }
    a.initAssigned.put(c, assignedInBlocks);
  }

  private static BExpr fieldInit(Attr a, FieldSymbol fs, List<BClass> nested) {
    BExpr cached = a.inferredFieldInits.remove(fs);
    if (cached != null) {
      return cached;
    }
    Env e = fieldEnv(a, fs);
    Env saved = a.env;
    a.env = e;
    try {
      Type t = a.ensureFieldType(fs);
      BExpr cachedNow = a.inferredFieldInits.remove(fs);
      if (cachedNow != null) {
        nested.addAll(e.localClasses);
        return cachedNow;
      }
      VarDeclarator v = fs.declarator();
      BExpr init =
          v.init() instanceof Expr.ArrayInit ai ? a.exprCoerced(ai, t) : a.exprCoerced(v.init(), t);
      nested.addAll(e.localClasses);
      return init;
    } finally {
      a.env = saved;
    }
  }

  private static BStmt enumConstant(Attr a, ClassSymbol c, EnumConstant ec, List<BClass> nested) {
    FieldSymbol f = c.field(ec.name());
    Env e = staticEnv(a, c);
    Env saved = a.env;
    a.env = e;
    try {
      List<Calls.ArgInfo> infos = a.calls.prepare(ec.args() == null ? List.of() : ec.args());
      Calls.Selected sel =
          a.calls.select(
              c.methods(MethodSymbol.CONSTRUCTOR),
              c.thisType(),
              infos,
              null,
              null,
              null,
              ec.span(),
              "constructor of enum " + c.name(),
              true);
      BExpr created;
      if (sel == null) {
        created = new BExpr.Error(c.thisType(), ec.span());
      } else {
        BExpr call = a.calls.finish(sel, null, c.thisType(), infos, ec.span(), false);
        created =
            new BExpr.New(
                c.thisType(),
                sel.method,
                call instanceof BExpr.Call cc ? cc.args() : List.of(),
                ec.span());
      }
      nested.addAll(e.localClasses);
      return new BStmt.ExprStmt(
          new BExpr.Assign(
              new BLValue.FieldLV(null, f, c.thisType()), created, c.thisType(), ec.span()),
          ec.span());
    } finally {
      a.env = saved;
    }
  }

  /** A class without constructors must not have uninitialized final fields. */
  private static void checkGeneratedCtorFinals(Attr a, ClassSymbol c) {
    if (c.isRecord() || c.isInterface() || c.has(Flags.MODULE)) {
      return;
    }
    boolean onlyGenerated =
        c.methods(MethodSymbol.CONSTRUCTOR).stream().allMatch(k -> k.has(Flags.GENERATED));
    if (!onlyGenerated) {
      return;
    }
    Set<FieldSymbol> init = initializedByInitializers(a, c);
    for (FieldSymbol f : c.fields()) {
      if (f.has(Flags.FINAL)
          && !f.isStatic()
          && !f.has(Flags.BACKING_FIELD)
          && !init.contains(f)
          && f.declarator() != null) {
        a.report(
            a.err(
                    Code.FINAL_FIELD_UNINITIALIZED,
                    f.declarator().nameSpan(),
                    "final field '" + f.name() + "' is never initialized")
                .help("give it an initializer or assign it in a constructor"));
      }
    }
    for (FieldSymbol f : c.fields()) {
      if (f.has(Flags.FINAL)
          && f.isStatic()
          && f.declarator() != null
          && f.declarator().init() == null) {
        a.report(
            a.err(
                Code.FINAL_FIELD_UNINITIALIZED,
                f.declarator().nameSpan(),
                "static final field '" + f.name() + "' needs an initializer"));
      }
    }
  }

  // ------------------------------------------------------------------ overrides

  /** Methods of supertypes that {@code m} overrides (override-equivalent after substitution). */
  static List<MethodSymbol> overridden(Attr a, ClassSymbol c, MethodSymbol m) {
    List<MethodSymbol> out = new ArrayList<>();
    if (m.isConstructor() || m.isStatic() || m.has(Flags.PRIVATE) && !m.has(Flags.OVERRIDE)) {
      return out;
    }
    String mine = erasedParams(m.params().stream().map(p -> p.type()).toList());
    Set<ClassSymbol> seen = new HashSet<>();
    for (ClassSymbol s : a.lookup.hierarchy(c.thisType())) {
      if (s == c || !seen.add(s)) {
        continue;
      }
      ClassType as = a.types.asSuper(c.thisType(), s);
      for (MethodSymbol n : s.methods(m.jvmName())) {
        if (n.isConstructor()
            || n.isStatic()
            || n.has(Flags.PRIVATE)
            || n.params().size() != m.params().size()) {
          continue;
        }
        if (!a.lookup.isAccessible(n, s, null, c)
            && !n.has(Flags.PUBLIC)
            && !n.has(Flags.PROTECTED)) {
          continue; // package-private in another package: not overridden
        }
        Map<TypeVarSymbol, Type> subst = as == null ? Map.of() : a.types.typeArgMap(as);
        Map<TypeVarSymbol, Type> methodVars =
            Types.zip(n.typeParams(), m.typeParams().stream().map(TypeVarSymbol::asType).toList());
        List<Type> theirs = new ArrayList<>();
        for (MethodSymbol.Param p : n.params()) {
          theirs.add(Types.subst(Types.subst(p.type(), subst), methodVars));
        }
        if (erasedParams(theirs).equals(mine)) {
          out.add(n);
        }
      }
    }
    return out;
  }

  private static String erasedParams(List<Type> ts) {
    List<Type> erased = new ArrayList<>();
    for (Type t : ts) {
      erased.add(t == null ? Type.ErrorType.INSTANCE : t.erasure());
    }
    return Descriptors.params(erased);
  }

  private static void checkOverrides(Attr a, ClassSymbol c, List<BClass.Bridge> bridges) {
    for (MethodSymbol m : c.allMethods()) {
      if (m.isConstructor() || m.isStatic() || m.has(Flags.GENERATED) && !m.has(Flags.GETTER)) {
        continue;
      }
      Span at = nameSpan(m);
      List<MethodSymbol> over = overridden(a, c, m);
      boolean declaredOverride = m.has(Flags.OVERRIDE);
      if (over.isEmpty() && addsAccessor(a, c, m)) {
        continue; // `override T p { get; set; }` over a get-only property adds a new setter
      }
      if (over.isEmpty()) {
        if (declaredOverride && at != null) {
          Diagnostic.Builder d =
              a.err(Code.NOTHING_TO_OVERRIDE, at, "'" + m.name() + "' overrides nothing");
          Set<String> names = new LinkedHashSet<>();
          for (ClassSymbol s : a.lookup.hierarchy(c.thisType())) {
            if (s != c) {
              s.allMethods().forEach(x -> names.add(x.name()));
            }
          }
          String guess = Suggestions.closest(m.name(), names);
          d.help(
              guess != null
                  ? "did you mean '" + guess + "'?"
                  : "remove 'override', or check the parameter types");
          a.report(d);
        }
        continue;
      }
      if (!declaredOverride && !m.has(Flags.GENERATED) && at != null) {
        MethodSymbol o = over.getFirst();
        a.report(
            a.err(
                    Code.MISSING_OVERRIDE,
                    at,
                    "'"
                        + m.name()
                        + "' overrides "
                        + o.signature()
                        + " and must be marked 'override'")
                .help("add 'override' before the return type"));
      }
      for (MethodSymbol o : over) {
        if (o.has(Flags.FINAL) && at != null) {
          Diagnostic.Builder d =
              a.err(Code.CANNOT_OVERRIDE, at, "cannot override final method " + o.signature());
          if (o.owner().isSource()) {
            d.help("J# methods are final unless declared 'open', 'abstract' or 'override'");
          }
          a.report(d);
        }
        if (accessRank(m.flags()) < accessRank(o.flags()) && at != null) {
          a.error(
              Code.CANNOT_OVERRIDE,
              at,
              "'"
                  + m.name()
                  + "' cannot reduce visibility of "
                  + o.signature()
                  + " ("
                  + Flags.access(o.flags())
                  + ")");
        }
        ClassType as = a.types.asSuper(c.thisType(), o.owner());
        Map<TypeVarSymbol, Type> subst =
            new java.util.HashMap<>(as == null ? Map.of() : a.types.typeArgMap(as));
        // Generic methods: the overrider's type parameters stand for the overridden ones.
        if (o.typeParams().size() == m.typeParams().size()) {
          for (int i = 0; i < o.typeParams().size(); i++) {
            subst.put(
                o.typeParams().get(i),
                new Type.TypeVar(
                    m.typeParams().get(i),
                    io.github.matrixidot.jsharp.compiler.types.Nullness.NON_NULL));
          }
        }
        Type theirRet = o.returnType() == null ? null : Types.subst(o.returnType(), subst);
        Type myRet = m.returnType();
        if (theirRet != null && myRet != null && !myRet.isError() && !theirRet.isError()) {
          boolean ok =
              myRet instanceof PrimType || theirRet instanceof PrimType
                  ? myRet == theirRet
                  : a.types.isSubtype(myRet, theirRet);
          if (!ok && at != null) {
            a.error(
                Code.TYPE_MISMATCH,
                at,
                "return type "
                    + myRet.display()
                    + " is not compatible with "
                    + theirRet.display()
                    + " of overridden "
                    + o.signature());
          }
        }
        // Bridge if the erased descriptors differ.
        if (!m.has(Flags.PRIVATE)
            && !Descriptors.method(
                    erasedParamsList(o),
                    o.returnType() == null ? PrimType.VOID : o.returnType().erasure())
                .equals(
                    Descriptors.method(
                        erasedParamsList(m), myRet == null ? PrimType.VOID : myRet.erasure()))) {
          boolean already = bridges.stream().anyMatch(b -> sameErasure(b.overridden(), o));
          if (!already) {
            bridges.add(new BClass.Bridge(m, o));
          }
        }
      }
    }
  }

  private static List<Type> erasedParamsList(MethodSymbol m) {
    List<Type> out = new ArrayList<>();
    for (MethodSymbol.Param p : m.params()) {
      out.add(p.type() == null ? Type.ErrorType.INSTANCE : p.type().erasure());
    }
    return out;
  }

  private static boolean sameErasure(MethodSymbol x, MethodSymbol y) {
    return x.jvmName().equals(y.jvmName())
        && Descriptors.method(
                erasedParamsList(x),
                x.returnType() == null ? PrimType.VOID : x.returnType().erasure())
            .equals(
                Descriptors.method(
                    erasedParamsList(y),
                    y.returnType() == null ? PrimType.VOID : y.returnType().erasure()));
  }

  /**
   * True for a setter of an {@code override} property whose getter overrides: the property
   * overrides a get-only one and adds a setter (allowed, as in C#).
   */
  private static boolean addsAccessor(Attr a, ClassSymbol c, MethodSymbol m) {
    if (!m.has(Flags.SETTER) || m.property() == null || m.property().getter() == null) {
      return false;
    }
    return !overridden(a, c, m.property().getter()).isEmpty();
  }

  private static int accessRank(long f) {
    if (Flags.is(f, Flags.PUBLIC)) {
      return 3;
    }
    if (Flags.is(f, Flags.PROTECTED)) {
      return 2;
    }
    if (Flags.is(f, Flags.PRIVATE)) {
      return 0;
    }
    return 1;
  }

  private static Span nameSpan(Symbol s) {
    if (s instanceof MethodSymbol m) {
      return switch (m.decl()) {
        case Decl.Method md -> md.nameSpan();
        case Decl.Property p -> p.nameSpan();
        case Decl.Constructor k -> k.nameSpan();
        case null, default -> null;
      };
    }
    return null;
  }

  /** A concrete class must implement every abstract method it inherits. */
  private static void checkAbstractImplemented(Attr a, ClassSymbol c) {
    if (c.isInterface()) {
      return;
    }
    List<String> missing = new ArrayList<>();
    Set<String> reported = new HashSet<>();
    for (ClassSymbol s : a.lookup.hierarchy(c.thisType())) {
      if (s == c) {
        continue;
      }
      for (MethodSymbol n : s.allMethods()) {
        if (!n.isAbstract() || n.isStatic() || c.isAbstract()) {
          continue;
        }
        if (!implemented(a, c, s, n)) {
          String key = n.name() + n.params().size();
          if (reported.add(key)) {
            missing.add(n.signature());
          }
        }
      }
    }
    // Two inherited default methods with the same signature must be resolved by an override.
    Map<String, MethodSymbol> defaults = new java.util.HashMap<>();
    for (ClassSymbol s : a.lookup.hierarchy(c.thisType())) {
      if (!s.isInterface()) {
        continue;
      }
      for (MethodSymbol n : s.allMethods()) {
        if (!n.has(Flags.DEFAULT)) {
          continue;
        }
        String key =
            n.name() + erasedParams(n.params().stream().map(MethodSymbol.Param::type).toList());
        MethodSymbol prev = defaults.putIfAbsent(key, n);
        if (prev != null
            && prev.owner() != n.owner()
            && a.types.asSuper(prev.owner().thisType(), n.owner()) == null
            && a.types.asSuper(n.owner().thisType(), prev.owner()) == null
            && c.methods(n.name()).stream()
                .noneMatch(k -> k.params().size() == n.params().size())) {
          missing.add(
              n.signature()
                  + " (inherited from both "
                  + prev.owner().name()
                  + " and "
                  + n.owner().name()
                  + ")");
        }
      }
    }
    if (!missing.isEmpty()) {
      Span at =
          c.decl() != null && c.decl().nameSpan() != null && !c.has(Flags.ANONYMOUS)
              ? c.decl().nameSpan()
              : c.decl() != null ? c.decl().span() : Span.NONE;
      Diagnostic.Builder d =
          a.err(
              Code.ABSTRACT_NOT_IMPLEMENTED,
              at,
              (c.has(Flags.ANONYMOUS) ? "anonymous class" : c.kindName() + " " + c.name())
                  + " does not implement "
                  + (missing.size() == 1 ? "" : missing.size() + " abstract members: ")
                  + String.join(", ", missing.subList(0, Math.min(4, missing.size()))));
      d.help(
          "implement "
              + (missing.size() == 1 ? "it" : "them")
              + " with 'override', or declare the class 'abstract'");
      a.report(d);
    }
  }

  private static boolean implemented(Attr a, ClassSymbol c, ClassSymbol owner, MethodSymbol n) {
    ClassType as = a.types.asSuper(c.thisType(), owner);
    Map<TypeVarSymbol, Type> subst = as == null ? Map.of() : a.types.typeArgMap(as);
    List<Type> theirs = new ArrayList<>();
    for (MethodSymbol.Param p : n.params()) {
      theirs.add(Types.subst(p.type(), subst));
    }
    String want = erasedParams(theirs);
    for (ClassSymbol s : a.lookup.hierarchy(c.thisType())) {
      for (MethodSymbol k : s.methods(n.jvmName())) {
        if (k == n || k.isAbstract() || k.isStatic() || k.params().size() != n.params().size()) {
          continue;
        }
        ClassType as2 = a.types.asSuper(c.thisType(), s);
        Map<TypeVarSymbol, Type> subst2 = as2 == null ? Map.of() : a.types.typeArgMap(as2);
        List<Type> mine = new ArrayList<>();
        for (MethodSymbol.Param p : k.params()) {
          mine.add(Types.subst(p.type(), subst2));
        }
        if (erasedParams(mine).equals(want)) {
          return true;
        }
      }
    }
    return false;
  }

  // ------------------------------------------------------------------ variance

  private static void checkVariance(Attr a, ClassSymbol c) {
    if (!c.isInterface()
        || c.typeParams().stream().allMatch(tv -> tv.variance() == TypeParam.Variance.INVARIANT)) {
      return;
    }
    for (MethodSymbol m : c.allMethods()) {
      if (m.isStatic() || m.decl() == null) {
        continue;
      }
      Span at = nameSpan(m);
      if (at == null) {
        continue;
      }
      if (m.returnType() != null) {
        variance(a, m.returnType(), 1, at, "return type of '" + m.name() + "'");
      }
      for (MethodSymbol.Param p : m.params()) {
        if (p.type() != null) {
          variance(a, p.type(), -1, at, "parameter '" + p.name() + "' of '" + m.name() + "'");
        }
      }
    }
  }

  /**
   * Checks that variant type variables occur only in allowed positions (+1 out, -1 in, 0
   * invariant).
   */
  private static void variance(Attr a, Type t, int polarity, Span at, String where) {
    switch (t) {
      case Type.TypeVar v -> {
        TypeParam.Variance dv = v.sym().variance();
        if (dv == TypeParam.Variance.OUT && polarity != 1) {
          a.report(
              a.err(
                      Code.INVALID_VARIANCE,
                      at,
                      "'out' type parameter " + v.sym().name() + " cannot appear in " + where)
                  .note("'out' parameters may only be produced (returned)"));
        } else if (dv == TypeParam.Variance.IN && polarity != -1) {
          a.report(
              a.err(
                      Code.INVALID_VARIANCE,
                      at,
                      "'in' type parameter " + v.sym().name() + " cannot appear in " + where)
                  .note("'in' parameters may only be consumed (taken as arguments)"));
        }
      }
      case ClassType ct -> {
        List<TypeVarSymbol> tps = ct.sym().typeParams();
        for (int i = 0; i < ct.args().size(); i++) {
          Type arg = ct.args().get(i);
          TypeParam.Variance v =
              i < tps.size() ? tps.get(i).variance() : TypeParam.Variance.INVARIANT;
          int p =
              switch (v) {
                case OUT -> polarity;
                case IN -> -polarity;
                case INVARIANT -> 0;
              };
          if (arg instanceof Type.WildcardType w) {
            if (w.bound() != null) {
              variance(
                  a,
                  w.bound(),
                  w.kind() == Type.WildcardType.Kind.SUPER ? -polarity : polarity,
                  at,
                  where);
            }
          } else {
            variance(a, arg, p, at, where);
          }
        }
      }
      case Type.ArrayType arr -> variance(a, arr.elem(), polarity == 1 ? 1 : 0, at, where);
      default -> {}
    }
  }

  // ------------------------------------------------------------------ default arguments & captures

  /** Evaluates default argument values of every source method (reporting non-constants). */
  private static void checkDefaultArgs(Attr a, ClassSymbol c) {
    for (MethodSymbol m : c.allMethods()) {
      for (MethodSymbol.Param p : m.params()) {
        if (p.defaultExpr() != null) {
          Object v = a.calls.evaluateDefault(m, p);
          if (v != Calls.INVALID) {
            int idx = m.params().indexOf(p);
            List<MethodSymbol.Param> ps = new ArrayList<>(m.params());
            ps.set(
                idx,
                new MethodSymbol.Param(
                    p.name(), p.type(), p.isVarargs(), p.defaultExpr(), true, v));
            m.setParams(ps);
          }
        }
      }
    }
  }

  /** Captured variables must be effectively final. */
  private static void checkCaptures(Attr a) {
    Set<VarSymbol> reported = new HashSet<>();
    for (VarSymbol v : a.capturedVars) {
      if (v.reassigned() && reported.add(v)) {
        Span at = a.captureSites.get(v);
        a.report(
            Diagnostic.error(
                    Code.CAPTURED_NOT_FINAL,
                    a.captureFiles.get(v),
                    at,
                    "variable '"
                        + v.name()
                        + "' is captured by a lambda or local class but reassigned")
                .note("captured variables must be effectively final, as in Java")
                .help(
                    "copy it into a 'val' before the lambda: 'val "
                        + v.name()
                        + "Copy = "
                        + v.name()
                        + ";'"));
      }
    }
    a.capturedVars.clear();
  }

  /** Unused: kept for symmetry with nested scopes. */
  static Nullness none() {
    return Nullness.NON_NULL;
  }
}

package dev.jsharp.compiler.check;

import dev.jsharp.compiler.ast.Arg;
import dev.jsharp.compiler.ast.Expr;
import dev.jsharp.compiler.ast.TypeNode;
import dev.jsharp.compiler.bound.BExpr;
import dev.jsharp.compiler.diag.Code;
import dev.jsharp.compiler.diag.Diagnostic;
import dev.jsharp.compiler.resolve.FileScope;
import dev.jsharp.compiler.resolve.Suggestions;
import dev.jsharp.compiler.source.Span;
import dev.jsharp.compiler.symbols.ClassSymbol;
import dev.jsharp.compiler.symbols.Flags;
import dev.jsharp.compiler.symbols.MethodSymbol;
import dev.jsharp.compiler.symbols.TypeVarSymbol;
import dev.jsharp.compiler.symbols.VarSymbol;
import dev.jsharp.compiler.types.Nullness;
import dev.jsharp.compiler.types.Type;
import dev.jsharp.compiler.types.Type.ClassType;
import dev.jsharp.compiler.types.Type.PrimType;
import dev.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Method calls: name resolution of callees, overload resolution in three phases (strict, with
 * boxing, with varargs; JLS 15.12.2), generic type inference including lambda result types, named
 * and default arguments, extension methods, constructor calls and chaining, functional-value
 * invocation and method references.
 */
final class Calls {
  private final Attr a;
  private final Map<MethodSymbol, Object[]> defaultsCache = new IdentityHashMap<>();
  private final Map<String, Map<String, List<MethodSymbol>>> packageExtensions = new HashMap<>();

  Calls(Attr a) {
    this.a = a;
  }

  private Types types() {
    return a.types;
  }

  // ------------------------------------------------------------------ arguments

  /**
   * An argument: either attributed standalone ({@code bound}) or deferred until a target type is
   * known (lambdas, method references, {@code new()}, array initializers).
   */
  static final class ArgInfo {
    final String name;
    final Expr expr;
    final BExpr bound;
    final Type type;
    final boolean deferred;
    final Span span;

    ArgInfo(String name, Expr expr, BExpr bound, Type type, boolean deferred, Span span) {
      this.name = name;
      this.expr = expr;
      this.bound = bound;
      this.type = type;
      this.deferred = deferred;
      this.span = span;
    }

    static ArgInfo ofType(Type t, Span span) {
      return new ArgInfo(null, null, null, t, false, span);
    }
  }

  static boolean isDeferred(Expr e) {
    return switch (e) {
      case Expr.Lambda l -> true;
      case Expr.MethodRef m -> true;
      case Expr.New n -> n.type() == null;
      case Expr.ArrayInit ai -> true;
      case Expr.Paren p -> isDeferred(p.expr());
      case Expr.Conditional c -> isDeferred(c.then()) || isDeferred(c.otherwise());
      case Expr.Switch s -> s.arms().stream().anyMatch(arm -> isDeferred(arm.body()));
      case Expr.Tuple t -> t.elements().stream().anyMatch(x -> isDeferred(x.value()));
      default -> false;
    };
  }

  /** Expressions whose type benefits from a target (generic calls, diamond {@code new}). */
  private static boolean isRetargetable(Expr e) {
    return switch (e) {
      case Expr.Call c -> true;
      case Expr.New n -> n.type() != null;
      case Expr.Paren p -> isRetargetable(p.expr());
      case Expr.Conditional c -> isRetargetable(c.then()) || isRetargetable(c.otherwise());
      case Expr.Binary b -> b.op() == Expr.BinaryOp.COALESCE;
      default -> false;
    };
  }

  List<ArgInfo> prepare(List<Arg> args) {
    List<ArgInfo> out = new ArrayList<>();
    for (Arg arg : args) {
      if (isDeferred(arg.value())) {
        out.add(new ArgInfo(arg.name(), arg.value(), null, null, true, arg.span()));
      } else {
        BExpr b = a.value(arg.value(), null);
        out.add(new ArgInfo(arg.name(), arg.value(), b, b.type(), false, arg.span()));
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ call expressions

  BExpr call(Expr.Call c, Type pt) {
    Expr callee = c.callee();
    Span span = c.span();
    switch (callee) {
      case Expr.Name n -> {
        return simpleCall(n.name(), n.typeArgs(), n.span(), c.args(), pt, span);
      }
      case Expr.Member m -> {
        if (m.nullSafe()) {
          return a.safeAccess(m.target(), span, recv -> instanceCall(recv, m.name(), m.typeArgs(), m.nameSpan(), prepare(c.args()), pt, span));
        }
        Attr.Target t = a.target(m.target(), true);
        return switch (t) {
          case Attr.ValueTarget v -> instanceCall(v.expr(), m.name(), m.typeArgs(), m.nameSpan(), prepare(c.args()), pt, span);
          case Attr.TypeTarget tt -> staticCall(tt.type(), m.name(), m.typeArgs(), m.nameSpan(), c.args(), pt, span);
          case Attr.SuperTarget s -> superCall(s, m.name(), m.typeArgs(), m.nameSpan(), c.args(), pt, span);
          case Attr.PackageTarget p -> {
            a.error(Code.UNRESOLVED_NAME, m.nameSpan(), "cannot find function '" + m.name() + "' in package " + p.name());
            prepare(c.args());
            yield new BExpr.Error(Type.ErrorType.INSTANCE, span);
          }
        };
      }
      case Expr.This t when t.qualifier() == null -> {
        return constructorChain(false, c.args(), span);
      }
      case Expr.Super s -> {
        return constructorChain(true, c.args(), span);
      }
      default -> {
        BExpr f = a.value(callee, null);
        return invokeFunctional(f, c.args(), span);
      }
    }
  }

  private List<Type> explicitTypeArgs(List<TypeNode> tas) {
    if (tas.isEmpty()) {
      return null;
    }
    List<Type> out = new ArrayList<>();
    for (TypeNode t : tas) {
      out.add(a.typeResolver.resolveTypeArg(t, a.typeScope()));
    }
    return out;
  }

  /** {@code name(args)}: local functional values, enclosing classes, module functions, imports. */
  private BExpr simpleCall(String name, List<TypeNode> typeArgs, Span nameSpan, List<Arg> args, Type pt, Span span) {
    Env env = a.env;
    Scope.Found local = env.scope.lookup(name);
    if (local != null) {
      BExpr f = a.asValue(a.target(new Expr.Name(name, List.of(), nameSpan), true), nameSpan);
      return invokeFunctional(f, args, span);
    }
    // Enclosing classes, innermost first: the first class with a method of this name wins.
    boolean staticOnly = env.isStatic;
    boolean first = true;
    for (ClassSymbol c = env.cls; c != null; c = c.outer()) {
      if (a.lookup.hasMethodNamed(c.thisType(), name)) {
        List<MethodSymbol> cands = a.lookup.findMethods(c.thisType(), name);
        List<ArgInfo> infos = prepare(args);
        Selected sel = select(cands, c.thisType(), infos, explicitTypeArgs(typeArgs), pt, null, span, name, true);
        if (sel == null) {
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        BExpr recv = null;
        if (!sel.method.isStatic()) {
          if (staticOnly) {
            a.report(
                a.err(Code.STATIC_CONTEXT, nameSpan, "instance method '" + name + "' cannot be called from a static context")
                    .note(first ? "this code is static" : "nested classes in J# are static and have no outer instance"));
            return new BExpr.Error(Type.ErrorType.INSTANCE, span);
          }
          if (first) {
            recv = a.thisValue(span);
          } else {
            a.noteThisUse();
            recv = new BExpr.OuterThis(c, c.thisType(), span);
          }
        }
        return finish(sel, recv, c.thisType(), infos, span, false);
      }
      if (!c.has(Flags.LOCAL) && !c.has(Flags.ANONYMOUS)) {
        staticOnly = true;
      }
      first = false;
    }
    // Top-level functions: this file's module, then the package's other modules.
    List<MethodSymbol> moduleCands = new ArrayList<>();
    ClassSymbol module = a.moduleOf(env.cls);
    if (module != null) {
      moduleCands.addAll(module.methods(name));
    }
    for (ClassSymbol m : a.packageModules.getOrDefault(env.cls.packageName(), List.of())) {
      if (m != module) {
        for (MethodSymbol ms : m.methods(name)) {
          if (!ms.has(Flags.PRIVATE)) {
            moduleCands.add(ms);
          }
        }
      }
    }
    moduleCands.removeIf(ms -> ms.has(Flags.ENTRY_POINT));
    // Static imports (explicit single imports first, then on-demand including the Prelude).
    List<MethodSymbol> imported = new ArrayList<>();
    FileScope fs = a.fileScope();
    for (FileScope.StaticImport si : fs.staticSingleImports()) {
      String visible = si.alias() != null ? si.alias() : si.member();
      if (visible.equals(name)) {
        for (MethodSymbol ms : si.owner().methods(si.member())) {
          if (ms.isStatic()) {
            imported.add(ms);
          }
        }
      }
    }
    List<MethodSymbol> onDemand = new ArrayList<>();
    for (ClassSymbol c : fs.staticOnDemandImports()) {
      for (MethodSymbol ms : a.lookup.findMethods(c.thisType(), name)) {
        if (ms.isStatic() && a.lookup.isAccessible(ms, ms.owner(), null, env.cls)) {
          onDemand.add(ms);
        }
      }
    }
    for (List<MethodSymbol> tier : List.of(moduleCands, imported, onDemand)) {
      if (!tier.isEmpty()) {
        List<ArgInfo> infos = prepare(args);
        Selected sel = select(tier, null, infos, explicitTypeArgs(typeArgs), pt, null, span, name, tier == onDemand || tier == imported);
        if (sel == null && tier == moduleCands && (!imported.isEmpty() || !onDemand.isEmpty())) {
          // A module function with the same name but different parameters: fall back to imports.
          Selected again = select(imported.isEmpty() ? onDemand : imported, null, infos, explicitTypeArgs(typeArgs), pt, null, span, name, false);
          if (again != null) {
            return finish(again, null, null, infos, span, false);
          }
          select(tier, null, infos, explicitTypeArgs(typeArgs), pt, null, span, name, true);
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        if (sel == null) {
          if (tier == moduleCands) {
            select(tier, null, infos, explicitTypeArgs(typeArgs), pt, null, span, name, true);
          }
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        return finish(sel, null, null, infos, span, false);
      }
    }
    // A type name used like a call: `Point(1, 2)` -> hint at `new`.
    if (a.typeScope().find(name) instanceof dev.jsharp.compiler.resolve.TypeScope.FoundClass) {
      a.report(a.err(Code.NOT_CALLABLE, nameSpan, "'" + name + "' is a type; use 'new " + name + "(...)' to create an instance"));
      prepare(args);
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    Diagnostic.Builder d = a.err(Code.UNRESOLVED_NAME, nameSpan, "cannot find function '" + name + "'");
    Set<String> names = new LinkedHashSet<>();
    for (ClassSymbol c = env.cls; c != null; c = c.outer()) {
      for (MethodSymbol m : c.allMethods()) {
        names.add(m.name());
      }
    }
    if (module != null) {
      module.allMethods().forEach(m -> names.add(m.name()));
    }
    for (ClassSymbol c : fs.staticOnDemandImports()) {
      c.allMethods().forEach(m -> names.add(m.name()));
    }
    String guess = Suggestions.closest(name, names);
    if (guess != null) {
      d.help("did you mean '" + guess + "'?");
    }
    a.report(d.label("not found"));
    prepare(args);
    return new BExpr.Error(Type.ErrorType.INSTANCE, span);
  }

  /** {@code recv.name(args)}: members first, then extension methods. */
  BExpr instanceCall(BExpr recv, String name, List<TypeNode> typeArgs, Span nameSpan, List<ArgInfo> infos, Type pt, Span span) {
    Type site = recv.type();
    if (site.isError()) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    if (site == PrimType.VOID) {
      a.error(Code.VOID_VALUE, recv.span(), "a void expression has no members");
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    List<Type> explicit = explicitTypeArgs(typeArgs);
    List<MethodSymbol> extensions = findExtensions(name);
    // Extensions declared on the exact (primitive or nullable) receiver type come first, because
    // members are not callable on those receivers.
    boolean nullableRecv = site.isReference() && site.nullness() == Nullness.NULLABLE;
    if ((site instanceof PrimType || nullableRecv) && !extensions.isEmpty()) {
      Selected sel = select(extensions, null, withReceiver(recv, infos), explicit, pt, recv, span, name, false);
      if (sel != null) {
        return finish(sel, null, null, withReceiver(recv, infos), span, false);
      }
    }
    BExpr memberRecv = recv;
    Type memberSite = site;
    if (site instanceof PrimType p) {
      memberRecv = a.coerce(recv, a.syms.boxed(p), recv.span());
      memberSite = memberRecv.type();
    }
    List<MethodSymbol> members = a.lookup.findMethods(memberSite, name);
    if (!members.isEmpty()) {
      Type captured = a.captureSite(memberSite);
      Selected sel = select(members, captured, infos, explicit, pt, null, span, name, extensions.isEmpty());
      if (sel != null) {
        a.checkReceiverNullness(memberRecv, nameSpan);
        if (sel.method.isStatic()) {
          memberRecv = null;
        }
        return finish(sel, memberRecv, captured, infos, span, false);
      }
      if (extensions.isEmpty()) {
        return new BExpr.Error(Type.ErrorType.INSTANCE, span);
      }
    }
    if (!extensions.isEmpty()) {
      List<ArgInfo> withRecv = withReceiver(recv, infos);
      Selected sel = select(extensions, null, withRecv, explicit, pt, recv, span, name, false);
      if (sel != null) {
        return finish(sel, null, null, withRecv, span, false);
      }
      if (!members.isEmpty()) {
        select(members, a.captureSite(memberSite), infos, explicit, pt, null, span, name, true);
      } else {
        select(extensions, null, withRecv, explicit, pt, recv, span, name, true);
      }
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    // No method: maybe a property holding a function value, e.g. `obj.handler(x)`.
    if (a.lookup.findProperty(memberSite, name) != null || a.lookup.findField(memberSite, name) != null) {
      BExpr f = a.member(recv, name, nameSpan, span);
      if (f.type() instanceof ClassType ct && types().findSam(ct.sym()) != null) {
        return invokeFunctionalPrepared(f, infos, span);
      }
      a.error(Code.NOT_CALLABLE, nameSpan, "'" + name + "' is a " + (a.lookup.findProperty(memberSite, name) != null ? "property" : "field") + " of type " + f.type().display() + ", not a method");
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    a.checkReceiverNullness(recv, nameSpan);
    Diagnostic.Builder d = a.err(Code.UNRESOLVED_MEMBER, nameSpan, memberSite.withNullness(Nullness.NON_NULL).display() + " has no method '" + name + "'");
    String guess = Suggestions.closest(name, a.lookup.memberNames(memberSite));
    if (guess != null) {
      d.help("did you mean '" + guess + "'?");
    }
    a.report(d);
    return new BExpr.Error(Type.ErrorType.INSTANCE, span);
  }

  private static List<ArgInfo> withReceiver(BExpr recv, List<ArgInfo> infos) {
    List<ArgInfo> out = new ArrayList<>();
    out.add(new ArgInfo(null, null, recv, recv.type(), false, recv.span()));
    out.addAll(infos);
    return out;
  }

  private BExpr staticCall(Type site, String name, List<TypeNode> typeArgs, Span nameSpan, List<Arg> args, Type pt, Span span) {
    if (site.isError()) {
      prepare(args);
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    List<MethodSymbol> cands = new ArrayList<>(a.lookup.findMethods(site, name));
    if (cands.isEmpty()) {
      a.reportNoMember(site, name, nameSpan);
      prepare(args);
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    List<ArgInfo> infos = prepare(args);
    Selected sel = select(cands, site, infos, explicitTypeArgs(typeArgs), pt, null, span, name, true);
    if (sel == null) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    if (!sel.method.isStatic()) {
      // `Base.method()` from a subclass is not valid; instance methods need an instance.
      a.report(
          a.err(Code.STATIC_CONTEXT, nameSpan, "'" + name + "' is an instance method of " + site.display() + "; call it on an instance"));
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    return finish(sel, null, site, infos, span, false);
  }

  private BExpr superCall(Attr.SuperTarget s, String name, List<TypeNode> typeArgs, Span nameSpan, List<Arg> args, Type pt, Span span) {
    List<MethodSymbol> cands = new ArrayList<>(a.lookup.findMethods(s.superType(), name));
    // Default methods of directly implemented interfaces are callable through super as well.
    for (ClassType it : a.env.cls.interfaces()) {
      for (MethodSymbol m : a.lookup.findMethods(it, name)) {
        if (m.has(Flags.DEFAULT) && !cands.contains(m)) {
          cands.add(m);
        }
      }
    }
    if (cands.isEmpty()) {
      a.reportNoMember(s.superType(), name, nameSpan);
      prepare(args);
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    List<ArgInfo> infos = prepare(args);
    Selected sel = select(cands, s.superType(), infos, explicitTypeArgs(typeArgs), pt, null, span, name, true);
    if (sel == null) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    if (sel.method.isAbstract()) {
      a.error(Code.INVALID_THIS, nameSpan, "cannot call abstract method '" + name + "' through super");
    }
    BExpr recv = new BExpr.This(a.env.cls.thisType(), s.span());
    return finish(sel, recv, s.superType(), infos, span, true);
  }

  /** {@code this(...)} / {@code super(...)} inside a constructor. */
  private BExpr constructorChain(boolean isSuper, List<Arg> args, Span span) {
    Env env = a.env;
    if (!env.inConstructor || env.method == null || !env.method.isConstructor()) {
      a.error(Code.INVALID_CONSTRUCTOR_CALL, span, "'" + (isSuper ? "super" : "this") + "(...)' can only be called at the start of a constructor");
      prepare(args);
      return new BExpr.Error(PrimType.VOID, span);
    }
    if (!env.beforeSuperCall) {
      a.error(Code.INVALID_CONSTRUCTOR_CALL, span, "'" + (isSuper ? "super" : "this") + "(...)' must be the first statement of the constructor");
    }
    ClassSymbol c = env.cls;
    ClassType target;
    if (isSuper) {
      target = c.superclass();
      if (target == null || c.isEnum() || c.isRecord()) {
        a.error(Code.INVALID_CONSTRUCTOR_CALL, span, c.kindName() + "s cannot call super(...)");
        prepare(args);
        return new BExpr.Error(PrimType.VOID, span);
      }
    } else {
      target = c.thisType();
    }
    // Arguments are evaluated before `this` exists: a static context for instance members.
    boolean savedStatic = env.isStatic;
    env.isStatic = true;
    List<ArgInfo> infos;
    try {
      infos = prepare(args);
    } finally {
      env.isStatic = savedStatic;
    }
    List<MethodSymbol> ctors = target.sym().methods(MethodSymbol.CONSTRUCTOR);
    if (!isSuper) {
      ctors = ctors.stream().filter(k -> k != env.method).toList();
    }
    Selected sel = select(ctors, target, infos, null, null, null, span, (isSuper ? "super" : "this") + " constructor of " + target.sym().name(), true);
    env.beforeSuperCall = false;
    if (sel == null) {
      return new BExpr.Error(PrimType.VOID, span);
    }
    BExpr recv = new BExpr.This(c.thisType(), span);
    BExpr call = finish(sel, recv, target, infos, span, true);
    if (!isSuper) {
      // Delegating constructors do not run field initializers and inherit their assignments.
      for (var f : c.fields()) {
        if (f.has(Flags.FINAL) && !f.isStatic()) {
          env.flow.assignedFields.add(f);
        }
      }
    }
    return call;
  }

  /** Calls the single abstract method of a functional-interface value: {@code f(x)}. */
  private BExpr invokeFunctional(BExpr f, List<Arg> args, Span span) {
    return invokeFunctionalPrepared(f, prepare(args), span);
  }

  private BExpr invokeFunctionalPrepared(BExpr f, List<ArgInfo> infos, Span span) {
    if (f.type().isError()) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    if (!(f.type() instanceof ClassType ct) || types().findSam(ct.sym()) == null) {
      a.error(Code.NOT_CALLABLE, f.span(), "a value of type " + f.type().display() + " cannot be called");
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    a.checkReceiverNullness(f, f.span());
    MethodSymbol sam = types().findSam(ct.sym());
    Selected sel = select(List.of(sam), a.captureSite(ct), infos, null, null, null, span, sam.name(), true);
    if (sel == null) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    return finish(sel, f, a.captureSite(ct), infos, span, false);
  }

  // ------------------------------------------------------------------ constructors

  /**
   * {@code new T(args)}. If {@code inferArgs}, the class's type arguments are inferred (diamond,
   * raw name, or {@code new()}).
   */
  BExpr construct(ClassType ct, List<Arg> args, Type pt, Span span, boolean inferArgs) {
    ClassSymbol c = ct.sym();
    List<ArgInfo> infos = prepare(args);
    List<MethodSymbol> ctors = c.methods(MethodSymbol.CONSTRUCTOR);
    boolean infer = inferArgs && !c.typeParams().isEmpty() && (ct.args().isEmpty() || pt != null && ct.sym() == (pt instanceof ClassType pc ? pc.sym() : null));
    ClassType site = infer ? c.thisType() : ct;
    Type expected = infer && pt != null ? pt.withNullness(Nullness.NON_NULL) : null;
    if (infer && pt instanceof ClassType pc && pc.sym() == c && !pc.args().isEmpty()
        && pc.args().stream().noneMatch(x -> x instanceof Type.WildcardType)) {
      // new() with a fully known target: no inference needed.
      site = pc.withNullness(Nullness.NON_NULL) instanceof ClassType x ? x : site;
      infer = false;
    }
    Selected sel = selectCtor(ctors, site, infos, infer ? c.typeParams() : List.of(), expected, span, c.name());
    if (sel == null) {
      return new BExpr.Error(ct, span);
    }
    ClassType created = infer ? (ClassType) Types.subst(c.thisType(), sel.solution) : (ClassType) site;
    if (infer) {
      created = (ClassType) types().uncapture(created);
    }
    List<BExpr> finalArgs = finalArgs(sel, created, infos, span);
    return new BExpr.New(created, sel.method, finalArgs, span);
  }

  private Selected selectCtor(List<MethodSymbol> ctors, ClassType site, List<ArgInfo> infos, List<TypeVarSymbol> classVars, Type expected, Span span, String name) {
    extraInferenceVars = classVars;
    extraExpected = expected;
    try {
      return select(ctors, site, infos, null, null, null, span, "constructor of " + name, true);
    } finally {
      extraInferenceVars = List.of();
      extraExpected = null;
    }
  }

  private List<TypeVarSymbol> extraInferenceVars = List.of();
  private Type extraExpected;

  // ------------------------------------------------------------------ overload resolution

  enum Phase {
    STRICT,
    LOOSE,
    VARARGS
  }

  /** A selected method with its argument mapping and inferred type arguments. */
  static final class Selected {
    MethodSymbol method;
    Phase phase;
    Map<TypeVarSymbol, Type> solution;
    /** For each parameter: the argument indices mapped to it (varargs may have several). */
    List<List<Integer>> mapping;
    List<Type> paramTypes;
    boolean usesDefaults;
    int defaultsUsed;
  }

  /**
   * Selects the most specific applicable method, or reports why none applies (when {@code
   * report}) and returns null.
   *
   * @param extReceiver non-null when candidates are extension methods (argument 0 is the receiver)
   */
  Selected select(
      List<MethodSymbol> candidates,
      Type site,
      List<ArgInfo> args,
      List<Type> explicit,
      Type expected,
      BExpr extReceiver,
      Span span,
      String what,
      boolean report) {
    List<MethodSymbol> accessible = new ArrayList<>();
    MethodSymbol inaccessible = null;
    for (MethodSymbol m : candidates) {
      if (a.lookup.isAccessible(m, m.owner(), m.isStatic() ? null : site, a.env.cls)) {
        accessible.add(m);
      } else {
        inaccessible = m;
      }
    }
    for (Phase phase : Phase.values()) {
      List<Selected> app = new ArrayList<>();
      for (MethodSymbol m : accessible) {
        Selected s = tryApply(m, site, args, explicit, expected, phase);
        if (s != null) {
          app.add(s);
        }
      }
      if (!app.isEmpty()) {
        Selected best = mostSpecific(app, args);
        if (best == null) {
          if (report) {
            reportAmbiguous(app, span, what);
          }
          return report ? app.getFirst() : null;
        }
        return best;
      }
    }
    if (report) {
      if (accessible.isEmpty() && inaccessible != null) {
        a.error(
            Code.INACCESSIBLE_MEMBER,
            span,
            inaccessible.kindName() + " " + inaccessible.signature() + " is " + Flags.access(inaccessible.flags()));
      } else {
        reportNotApplicable(accessible, site, args, explicit, expected, span, what, extReceiver != null);
      }
    }
    return null;
  }

  private Selected tryApply(MethodSymbol m, Type site, List<ArgInfo> args, List<Type> explicit, Type expected, Phase phase) {
    List<MethodSymbol.Param> params = m.params();
    int n = params.size();
    boolean varargs = phase == Phase.VARARGS && m.isVarargs();
    if (phase == Phase.VARARGS && !m.isVarargs()) {
      return null;
    }
    // ---- map arguments to parameters
    List<List<Integer>> mapping = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      mapping.add(new ArrayList<>());
    }
    int pos = 0;
    boolean sawNamed = false;
    for (int i = 0; i < args.size(); i++) {
      ArgInfo arg = args.get(i);
      if (arg.name != null) {
        sawNamed = true;
        int idx = -1;
        for (int p = 0; p < n; p++) {
          if (params.get(p).name().equals(arg.name)) {
            idx = p;
          }
        }
        if (idx < 0 || !mapping.get(idx).isEmpty()) {
          return null;
        }
        mapping.get(idx).add(i);
        continue;
      }
      if (sawNamed) {
        return null; // positional after named
      }
      if (varargs && pos >= n - 1) {
        mapping.get(n - 1).add(i);
        continue;
      }
      if (pos >= n) {
        return null;
      }
      mapping.get(pos++).add(i);
    }
    int defaultsUsed = 0;
    for (int p = 0; p < n; p++) {
      if (mapping.get(p).isEmpty()) {
        if (varargs && p == n - 1) {
          continue;
        }
        if (params.get(p).hasDefault()) {
          defaultsUsed++;
          continue;
        }
        return null;
      }
    }
    // ---- instantiate parameter types
    Map<TypeVarSymbol, Type> classSubst = siteSubst(site, m);
    List<TypeVarSymbol> vars = new ArrayList<>(m.typeParams());
    vars.addAll(extraInferenceVars);
    Map<TypeVarSymbol, Type> explicitMap = null;
    if (explicit != null) {
      if (explicit.size() != m.typeParams().size()) {
        return null;
      }
      explicitMap = Types.zip(m.typeParams(), explicit);
      vars = new ArrayList<>(extraInferenceVars);
    }
    List<Type> ptypes = new ArrayList<>();
    for (MethodSymbol.Param p : params) {
      Type t = Types.subst(p.type() == null ? Type.ErrorType.INSTANCE : p.type(), classSubst);
      if (explicitMap != null) {
        t = Types.subst(t, explicitMap);
      }
      ptypes.add(t);
    }
    Infer inf = vars.isEmpty() ? null : new Infer(types(), vars);
    // ---- check / constrain arguments
    for (int p = 0; p < n; p++) {
      for (int idx : mapping.get(p)) {
        ArgInfo arg = args.get(idx);
        Type pt = ptypes.get(p);
        if (varargs && p == n - 1) {
          pt = pt instanceof Type.ArrayType at ? at.elem() : pt;
        }
        if (arg.deferred) {
          if (!potentiallyCompatible(arg.expr, pt, inf)) {
            return null;
          }
          continue;
        }
        Type at = arg.type;
        if (at.isError()) {
          continue;
        }
        if (inf != null && inf.mentionsVars(pt)) {
          if (phase == Phase.STRICT && at instanceof PrimType && inf.isVar(pt)) {
            return null;
          }
          if (at instanceof Type.NullType && inf.isVar(pt)) {
            inf.subtype(at, pt);
            continue;
          }
          inf.subtype(phase == Phase.STRICT ? at : types().boxIfPrimitive(at), pt);
          if (inf.failed) {
            return null;
          }
          continue;
        }
        if (!compatible(arg, pt, phase)) {
          return null;
        }
      }
    }
    Map<TypeVarSymbol, Type> solution = explicitMap != null ? new IdentityHashMap<>(explicitMap) : new IdentityHashMap<>();
    if (inf != null) {
      Type ret = Types.subst(m.returnType() == null ? Type.ErrorType.INSTANCE : m.returnType(), classSubst);
      if (explicitMap != null) {
        ret = Types.subst(ret, explicitMap);
      }
      Type exp = extraExpected != null ? extraExpected : expected;
      if (!extraInferenceVars.isEmpty() && exp != null && site instanceof ClassType) {
        inf.subtype(site, exp);
      } else if (exp != null && exp != PrimType.VOID && !exp.isError() && inf.mentionsVars(ret)) {
        Infer trial = copyInfer(inf, vars);
        trial.subtype(ret, types().boxIfPrimitive(exp));
        Map<TypeVarSymbol, Type> tsol = trial.solve(true);
        if (trial.check(tsol)) {
          inf.subtype(ret, types().boxIfPrimitive(exp));
        }
      }
      // Lambdas: infer from their bodies once their parameter types are known.
      for (int round = 0; round < 3; round++) {
        Map<TypeVarSymbol, Type> partial = inf.solve(false);
        boolean added = false;
        for (int p = 0; p < n; p++) {
          for (int idx : mapping.get(p)) {
            ArgInfo arg = args.get(idx);
            if (!arg.deferred) {
              continue;
            }
            Type pt = ptypes.get(p);
            if (varargs && p == n - 1 && pt instanceof Type.ArrayType at) {
              pt = at.elem();
            }
            added |= constrainDeferred(arg.expr, pt, inf, partial);
          }
        }
        if (!added) {
          break;
        }
      }
      Map<TypeVarSymbol, Type> sol = inf.solve(true);
      if (!inf.check(sol)) {
        return null;
      }
      solution.putAll(sol);
      List<Type> inst = new ArrayList<>();
      for (Type t : ptypes) {
        inst.add(Types.subst(t, sol));
      }
      ptypes = inst;
      // Re-check standalone arguments against the instantiated types.
      for (int p = 0; p < n; p++) {
        for (int idx : mapping.get(p)) {
          ArgInfo arg = args.get(idx);
          if (arg.deferred || arg.type.isError()) {
            continue;
          }
          Type pt = ptypes.get(p);
          if (varargs && p == n - 1 && pt instanceof Type.ArrayType at) {
            pt = at.elem();
          }
          if (!compatible(arg, pt, phase == Phase.STRICT ? Phase.LOOSE : phase)) {
            return null;
          }
        }
      }
    }
    // Lambdas with fully known types must type-check against the parameter (overload pruning).
    for (int p = 0; p < n; p++) {
      for (int idx : mapping.get(p)) {
        ArgInfo arg = args.get(idx);
        if (arg.deferred && unwrap(arg.expr) instanceof Expr.Lambda lam) {
          Type pt = ptypes.get(p);
          if (varargs && p == n - 1 && pt instanceof Type.ArrayType at) {
            pt = at.elem();
          }
          if (pt instanceof ClassType ct && types().findSam(ct.sym()) != null) {
            ClassType fi = types().nonWildcard(ct);
            Types.MethodType ft = types().functionType(fi);
            if (ft != null && ft.params().size() == lam.params().size()) {
              final ClassType fiF = fi;
              Attr.Speculation<BExpr> s = a.speculate(() -> a.lambdaBody(lam, fiF, types().findSam(fiF.sym()), ft));
              if (s.hasErrors()) {
                return null;
              }
            }
          }
        }
      }
    }
    Selected s = new Selected();
    s.method = m;
    s.phase = phase;
    s.solution = solution;
    s.mapping = mapping;
    s.paramTypes = ptypes;
    s.defaultsUsed = defaultsUsed;
    s.usesDefaults = defaultsUsed > 0;
    return s;
  }

  private Infer copyInfer(Infer inf, List<TypeVarSymbol> vars) {
    Infer c = new Infer(types(), vars);
    for (var e : inf.bounds.entrySet()) {
      Infer.Bounds to = c.bounds.get(e.getKey());
      to.lower.addAll(e.getValue().lower);
      to.upper.addAll(e.getValue().upper);
      to.eq.addAll(e.getValue().eq);
    }
    c.failed = inf.failed;
    return c;
  }

  private static Expr unwrap(Expr e) {
    return e instanceof Expr.Paren p ? unwrap(p.expr()) : e;
  }

  /** Substitution of the declaring class's type parameters as seen from {@code site}. */
  private Map<TypeVarSymbol, Type> siteSubst(Type site, MethodSymbol m) {
    if (site == null) {
      return Map.of();
    }
    ClassType as = types().asSuper(site, m.owner());
    if (as == null) {
      return Map.of();
    }
    if (as.isRaw()) {
      Map<TypeVarSymbol, Type> erased = new IdentityHashMap<>();
      for (TypeVarSymbol tv : m.owner().typeParams()) {
        erased.put(tv, tv.erasedBound().withNullness(Nullness.PLATFORM));
      }
      return erased;
    }
    return types().typeArgMap(as);
  }

  private boolean compatible(ArgInfo arg, Type pt, Phase phase) {
    Types.Conv c = types().assignConversion(arg.type, pt, Attr.intConstant(arg.bound));
    boolean ok =
        switch (c) {
          case NONE -> false;
          case BOX, UNBOX -> phase != Phase.STRICT;
          default -> true;
        };
    if (!ok && arg.expr != null && isRetargetable(arg.expr) && !pt.isError() && !(pt instanceof Type.TypeVar)) {
      final Type target = pt;
      Attr.Speculation<BExpr> s = a.speculate(() -> a.exprCoerced(arg.expr, target));
      return !s.hasErrors();
    }
    return ok;
  }

  private boolean potentiallyCompatible(Expr e, Type pt, Infer inf) {
    Expr u = unwrap(e);
    if (pt.isError()) {
      return true;
    }
    return switch (u) {
      case Expr.Lambda l -> {
        if (inf != null && inf.isVar(pt)) {
          yield false;
        }
        if (!(pt instanceof ClassType ct) || types().findSam(ct.sym()) == null) {
          yield false;
        }
        Types.MethodType ft = types().functionType(types().nonWildcard(ct));
        yield ft != null && ft.params().size() == l.params().size();
      }
      case Expr.MethodRef mr -> !(inf != null && inf.isVar(pt)) && pt instanceof ClassType ct && types().findSam(ct.sym()) != null;
      case Expr.New nw -> pt instanceof ClassType;
      case Expr.ArrayInit ai -> pt instanceof Type.ArrayType;
      default -> true;
    };
  }

  /**
   * For a deferred lambda/method-ref argument whose parameter types are known, attributes it
   * speculatively and constrains the function type's return type. Returns true if new bounds were
   * added.
   */
  private boolean constrainDeferred(Expr e, Type pt, Infer inf, Map<TypeVarSymbol, Type> partial) {
    Expr u = unwrap(e);
    if (!(pt instanceof ClassType ct) || types().findSam(ct.sym()) == null) {
      return false;
    }
    ClassType fi = types().nonWildcard(ct);
    Types.MethodType ft = types().functionType(fi);
    if (ft == null || !inf.mentionsVars(ft.ret())) {
      return false;
    }
    List<Type> ps = new ArrayList<>();
    for (Type p : ft.params()) {
      Type s = Types.subst(p, partial);
      if (inf.mentionsVars(s)) {
        return false;
      }
      ps.add(s);
    }
    Type result = null;
    if (u instanceof Expr.Lambda lam && ps.size() == lam.params().size()) {
      result = a.lambdaResultType(lam, ps, (ClassType) Types.subst(fi, partial));
      if (result != null && lam.isAsync()) {
        result = a.patterns.asyncResultForInference(result);
      }
    } else if (u instanceof Expr.MethodRef mr) {
      result = methodRefResultType(mr, ps);
    }
    if (result == null || result.isError()) {
      return false;
    }
    if (result == PrimType.VOID) {
      return false;
    }
    int before = boundsCount(inf);
    inf.subtype(types().boxIfPrimitive(result), ft.ret());
    return boundsCount(inf) > before;
  }

  private static int boundsCount(Infer inf) {
    int n = 0;
    for (Infer.Bounds b : inf.bounds.values()) {
      n += b.lower.size() + b.upper.size() + b.eq.size();
    }
    return n;
  }

  /** Most specific applicable method (JLS 15.12.2.5, simplified), or null if ambiguous. */
  private Selected mostSpecific(List<Selected> app, List<ArgInfo> args) {
    if (app.size() == 1) {
      return app.getFirst();
    }
    List<Selected> maximal = new ArrayList<>();
    for (Selected s : app) {
      boolean dominated = false;
      for (Selected o : app) {
        if (o != s && moreSpecific(o, s, args) && !moreSpecific(s, o, args)) {
          dominated = true;
          break;
        }
      }
      if (!dominated) {
        maximal.add(s);
      }
    }
    if (maximal.size() == 1) {
      return maximal.getFirst();
    }
    // Tie-breakers: fewer defaulted parameters, non-generic, then identical erasures (inherited
    // duplicates) resolve to the first.
    int minDefaults = maximal.stream().mapToInt(s -> s.defaultsUsed).min().orElse(0);
    List<Selected> fewer = maximal.stream().filter(s -> s.defaultsUsed == minDefaults).toList();
    if (fewer.size() == 1) {
      return fewer.getFirst();
    }
    List<Selected> nonGeneric = fewer.stream().filter(s -> s.method.typeParams().isEmpty()).toList();
    if (nonGeneric.size() == 1) {
      return nonGeneric.getFirst();
    }
    List<Selected> valueLambdaPreferred = preferValueReturningFunctional(fewer, args);
    if (valueLambdaPreferred.size() == 1) {
      return valueLambdaPreferred.getFirst();
    }
    String sig0 = erasedParams(fewer.getFirst().method);
    if (fewer.stream().allMatch(s -> erasedParams(s.method).equals(sig0))) {
      for (Selected s : fewer) {
        if (!s.method.isAbstract()) {
          return s;
        }
      }
      return fewer.getFirst();
    }
    return null;
  }

  /** For lambdas with value-returning bodies, prefer {@code Callable}-like over {@code Runnable}-like targets. */
  private List<Selected> preferValueReturningFunctional(List<Selected> cands, List<ArgInfo> args) {
    List<Selected> out = new ArrayList<>();
    for (Selected s : cands) {
      boolean ok = true;
      for (int p = 0; p < s.mapping.size(); p++) {
        for (int idx : s.mapping.get(p)) {
          ArgInfo arg = args.get(idx);
          if (arg.deferred && unwrap(arg.expr) instanceof Expr.Lambda lam
              && lam.body() instanceof dev.jsharp.compiler.ast.Body.ExprBody
              && s.paramTypes.get(p) instanceof ClassType ct) {
            Types.MethodType ft = types().functionType(types().nonWildcard(ct));
            if (ft != null && ft.ret() == PrimType.VOID) {
              ok = false;
            }
          }
        }
      }
      if (ok) {
        out.add(s);
      }
    }
    return out.isEmpty() ? cands : out;
  }

  private static String erasedParams(MethodSymbol m) {
    return dev.jsharp.compiler.types.Descriptors.params(m.params().stream().map(p -> p.type() == null ? (Type) Type.ErrorType.INSTANCE : p.type().erasure()).toList());
  }

  private boolean moreSpecific(Selected s1, Selected s2, List<ArgInfo> args) {
    for (int i = 0; i < args.size(); i++) {
      Type t1 = paramTypeForArg(s1, i);
      Type t2 = paramTypeForArg(s2, i);
      if (t1 == null || t2 == null) {
        continue;
      }
      if (t1 instanceof PrimType p1 && t2 instanceof PrimType p2) {
        if (!Types.isWidening(p1, p2)) {
          return false;
        }
        continue;
      }
      if (t1 instanceof PrimType && !(t2 instanceof PrimType)) {
        ArgInfo arg = args.get(i);
        if (arg.type instanceof PrimType) {
          continue; // the primitive parameter matches a primitive argument better
        }
        return false;
      }
      if (!(t1 instanceof PrimType) && t2 instanceof PrimType) {
        ArgInfo arg = args.get(i);
        if (arg.type != null && arg.type.isReference()) {
          continue;
        }
        return false;
      }
      if (args.get(i).deferred && t1 instanceof ClassType c1 && t2 instanceof ClassType c2
          && types().findSam(c1.sym()) != null && types().findSam(c2.sym()) != null && c1.sym() != c2.sym()
          && !types().isSubtype(t1, t2)) {
        continue; // unrelated functional interfaces: decided by lambda compatibility instead
      }
      if (!types().isSubtype(t1, t2)) {
        return false;
      }
    }
    return true;
  }

  private Type paramTypeForArg(Selected s, int argIndex) {
    for (int p = 0; p < s.mapping.size(); p++) {
      if (s.mapping.get(p).contains(argIndex)) {
        Type t = s.paramTypes.get(p);
        if (s.phase == Phase.VARARGS && p == s.mapping.size() - 1 && t instanceof Type.ArrayType at) {
          return at.elem();
        }
        return t;
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ diagnostics

  private void reportAmbiguous(List<Selected> app, Span span, String what) {
    Diagnostic.Builder d = a.err(Code.AMBIGUOUS_CALL, span, "call to '" + what + "' is ambiguous");
    for (Selected s : app.subList(0, Math.min(app.size(), 4))) {
      d.note("candidate: " + s.method.signature());
    }
    d.help("add a cast to an argument to pick one overload");
    a.report(d);
  }

  private void reportNotApplicable(
      List<MethodSymbol> cands, Type site, List<ArgInfo> args, List<Type> explicit, Type expected, Span span, String what, boolean isExtension) {
    if (args.stream().anyMatch(x -> !x.deferred && x.type.isError())) {
      return; // an argument already has an error
    }
    if (cands.size() == 1) {
      explainSingle(cands.getFirst(), site, args, explicit, span, isExtension);
      return;
    }
    StringBuilder argDesc = new StringBuilder("(");
    for (int i = isExtension ? 1 : 0; i < args.size(); i++) {
      if (argDesc.length() > 1) {
        argDesc.append(", ");
      }
      ArgInfo x = args.get(i);
      argDesc.append(x.deferred ? describeDeferred(x.expr) : x.type.display());
    }
    argDesc.append(')');
    Diagnostic.Builder d =
        a.err(Code.NO_APPLICABLE_METHOD, span, "no overload of '" + what + "' accepts arguments " + argDesc);
    int shown = 0;
    for (MethodSymbol m : cands) {
      if (shown++ < 5) {
        d.note("candidate: " + m.signature());
      }
    }
    a.report(d);
  }

  private static String describeDeferred(Expr e) {
    return switch (unwrap(e)) {
      case Expr.Lambda l -> "lambda";
      case Expr.MethodRef m -> "method reference";
      case Expr.New n -> "new()";
      default -> "expression";
    };
  }

  /** Explains precisely why the only candidate does not apply. */
  private void explainSingle(MethodSymbol m, Type site, List<ArgInfo> args, List<Type> explicit, Span span, boolean isExtension) {
    List<MethodSymbol.Param> params = m.params();
    int offset = isExtension ? 1 : 0;
    long positional = args.stream().filter(x -> x.name == null).count();
    int required = (int) params.stream().filter(p -> !p.hasDefault() && !p.isVarargs()).count();
    boolean hasNamed = args.stream().anyMatch(x -> x.name != null);
    for (ArgInfo x : args) {
      if (x.name != null && params.stream().noneMatch(p -> p.name().equals(x.name))) {
        a.report(
            a.err(Code.INVALID_NAMED_ARGUMENT, x.span, m.signature() + " has no parameter named '" + x.name + "'")
                .help("parameters: " + String.join(", ", params.subList(offset, params.size()).stream().map(MethodSymbol.Param::name).toList())));
        return;
      }
    }
    if (!hasNamed && (positional > params.size() && !m.isVarargs() || positional < required)) {
      a.report(
          a.err(
                  Code.ARGUMENT_COUNT,
                  span,
                  (m.isConstructor() ? "constructor " : "") + m.signature() + " takes " + (params.size() - offset) + " argument" + (params.size() - offset == 1 ? "" : "s") + ", but " + (positional - offset) + " " + (positional - offset == 1 ? "was" : "were") + " given"));
      return;
    }
    if (explicit != null && explicit.size() != m.typeParams().size()) {
      a.error(Code.WRONG_TYPE_ARG_COUNT, span, m.signature() + " has " + m.typeParams().size() + " type parameter(s), but " + explicit.size() + " type argument(s) were given");
      return;
    }
    // Find the first mismatching argument (after a best-effort inference).
    Map<TypeVarSymbol, Type> classSubst = siteSubst(site, m);
    Map<TypeVarSymbol, Type> sol = new IdentityHashMap<>();
    if (explicit != null) {
      sol.putAll(Types.zip(m.typeParams(), explicit));
    } else if (!m.typeParams().isEmpty()) {
      Infer inf = new Infer(types(), m.typeParams());
      for (int i = 0; i < Math.min(args.size(), params.size()); i++) {
        ArgInfo x = args.get(i);
        if (!x.deferred && x.name == null) {
          inf.subtype(types().boxIfPrimitive(x.type), Types.subst(params.get(i).type(), classSubst));
        }
      }
      sol.putAll(inf.solve(true));
    }
    for (int i = 0; i < args.size(); i++) {
      ArgInfo x = args.get(i);
      int pi = -1;
      if (x.name != null) {
        for (int p = 0; p < params.size(); p++) {
          if (params.get(p).name().equals(x.name)) {
            pi = p;
          }
        }
      } else {
        pi = Math.min(i, params.size() - 1);
      }
      if (pi < 0) {
        continue;
      }
      Type pt = Types.subst(Types.subst(params.get(pi).type(), classSubst), sol);
      if (m.isVarargs() && pi == params.size() - 1 && pt instanceof Type.ArrayType at
          && (x.deferred || !(x.type instanceof Type.ArrayType))) {
        pt = at.elem();
      }
      if (x.deferred) {
        if (!potentiallyCompatible(x.expr, pt, null)) {
          a.report(a.err(Code.LAMBDA_MISMATCH, x.span, describeDeferred(x.expr) + " is not compatible with parameter type " + pt.display()));
          return;
        }
        // Report the lambda's own errors against the expected type.
        a.coerce(a.expr(x.expr, pt), pt, x.span);
        return;
      }
      if (types().assignConversion(x.type, pt, Attr.intConstant(x.bound)) == Types.Conv.NONE) {
        String which = i - offset < 0 ? "receiver" : "argument " + (i - offset + 1);
        Diagnostic.Builder d = a.err(Code.TYPE_MISMATCH, x.span, which + " of " + m.signature() + ": expected " + pt.display() + ", found " + x.type.display());
        if (isRetargetable(x.expr)) {
          final Type target = pt;
          Attr.Speculation<BExpr> s = a.speculate(() -> a.exprCoerced(x.expr, target));
          if (!s.hasErrors()) {
            continue;
          }
        }
        a.report(d);
        return;
      }
    }
    a.report(a.err(Code.CANNOT_INFER, span, "cannot infer type arguments for " + m.signature() + " from these arguments"));
  }

  // ------------------------------------------------------------------ finishing a call

  BExpr finish(Selected sel, BExpr recv, Type site, List<ArgInfo> args, Span span, boolean isSpecial) {
    MethodSymbol m = sel.method;
    a.checkDeprecated(m, span);
    List<BExpr> finalArgs = finalArgs(sel, site, args, span);
    Type ret;
    if (m.has(Flags.INFERRED_TYPE) && m.returnType() == null) {
      ret = a.ensureReturnType(m);
    } else {
      ret = m.returnType();
    }
    if (ret == null) {
      ret = Type.ErrorType.INSTANCE;
    }
    Type t = Types.subst(Types.subst(ret, siteSubst(site, m)), sel.solution);
    t = types().uncapture(t);
    if (m.name().equals("getClass") && m.params().isEmpty() && recv != null && m.owner() == a.syms.objectSym()) {
      t = a.syms.classType(new Type.WildcardType(Type.WildcardType.Kind.EXTENDS, recv.type().erasure()));
    }
    if (m.isAbstract() && isSpecial && !m.isConstructor()) {
      a.error(Code.INVALID_THIS, span, "cannot call abstract method " + m.signature() + " directly");
    }
    BExpr.CallKind kind = isSpecial ? BExpr.CallKind.SPECIAL : Attr.callKind(m, recv, false);
    if (m.isStatic()) {
      kind = BExpr.CallKind.STATIC;
      recv = null;
    }
    return new BExpr.Call(recv, m, finalArgs, kind, t, span);
  }

  /** Converts arguments to the instantiated parameter types, packing varargs and filling defaults. */
  private List<BExpr> finalArgs(Selected sel, Type site, List<ArgInfo> args, Span span) {
    MethodSymbol m = sel.method;
    List<MethodSymbol.Param> params = m.params();
    List<BExpr> out = new ArrayList<>();
    for (int p = 0; p < params.size(); p++) {
      Type pt = sel.paramTypes.get(p);
      List<Integer> mapped = sel.mapping.get(p);
      boolean varargsSlot = sel.phase == Phase.VARARGS && p == params.size() - 1;
      if (varargsSlot) {
        Type.ArrayType at = (Type.ArrayType) (pt instanceof Type.ArrayType x ? x : Type.ArrayType.of(Type.ErrorType.INSTANCE));
        List<BExpr> elems = new ArrayList<>();
        for (int idx : mapped) {
          elems.add(finalArg(args.get(idx), at.elem()));
        }
        Type elemErased = at.elem() instanceof Type.TypeVar ? at.elem().erasure() : at.elem();
        out.add(new BExpr.NewArray(Type.ArrayType.of(elemErased.withNullness(at.elem().nullness())), List.of(), elems, span));
        continue;
      }
      if (mapped.isEmpty()) {
        out.add(defaultArg(m, p, pt, span));
        continue;
      }
      out.add(finalArg(args.get(mapped.getFirst()), pt));
    }
    return out;
  }

  private BExpr finalArg(ArgInfo arg, Type pt) {
    if (arg.deferred) {
      return a.coerce(a.expr(arg.expr, pt), pt, arg.span);
    }
    if (arg.expr != null && isRetargetable(arg.expr) && !arg.type.isError()
        && types().assignConversion(arg.type, pt, Attr.intConstant(arg.bound)) == Types.Conv.NONE) {
      return a.exprCoerced(arg.expr, pt, arg.span);
    }
    return a.coerce(arg.bound, pt, arg.span);
  }

  /** The constant value of a defaulted parameter as a bound constant. */
  private BExpr defaultArg(MethodSymbol m, int p, Type pt, Span span) {
    Object[] cache = defaultsCache.computeIfAbsent(m, k -> new Object[k.params().size()]);
    MethodSymbol.Param param = m.params().get(p);
    Object v;
    if (param.defaultExpr() == null) {
      v = param.defaultValue();
    } else if (cache[p] != null) {
      v = cache[p] == NULL ? null : cache[p];
    } else {
      v = a.withRealDiagnostics(() -> evaluateDefault(m, param));
      cache[p] = v == null ? NULL : v;
    }
    if (v == INVALID) {
      return new BExpr.Error(pt, span);
    }
    if (v == null) {
      return a.coerce(new BExpr.Const(null, Type.NullType.INSTANCE, span), pt, span);
    }
    Type ct = constantType(v);
    return a.coerce(new BExpr.Const(v, ct, span), pt, span);
  }

  private static final Object NULL = new Object();
  static final Object INVALID = new Object();

  Type constantType(Object v) {
    return switch (v) {
      case Integer i -> PrimType.INT;
      case Long l -> PrimType.LONG;
      case Float f -> PrimType.FLOAT;
      case Double d -> PrimType.DOUBLE;
      case Boolean b -> PrimType.BOOLEAN;
      case Character c -> PrimType.CHAR;
      case Byte b -> PrimType.BYTE;
      case Short s -> PrimType.SHORT;
      default -> a.syms.stringType();
    };
  }

  /** Evaluates a source default argument in the declaring class's static context. */
  Object evaluateDefault(MethodSymbol m, MethodSymbol.Param param) {
    Env saved = a.env;
    a.env = ClassChecker.staticEnv(a, m.owner());
    try {
      BExpr e = a.exprCoerced(param.defaultExpr(), param.type());
      if (e instanceof BExpr.Conv c && c.kind() == BExpr.ConvKind.BOX) {
        e = c.expr();
      }
      if (e instanceof BExpr.Const c) {
        return c.value();
      }
      if (!e.type().isError()) {
        a.report(
            a.err(Code.INVALID_DEFAULT_ARGUMENT, param.defaultExpr().span(), "default value of '" + param.name() + "' must be a compile-time constant")
                .help("use a literal, a constant, or null; compute other values inside the method"));
      }
      return INVALID;
    } finally {
      a.env = saved;
    }
  }

  // ------------------------------------------------------------------ extensions

  /** Extension methods named {@code name} visible in the current file. */
  List<MethodSymbol> findExtensions(String name) {
    Set<MethodSymbol> out = new LinkedHashSet<>();
    Env env = a.env;
    for (ClassSymbol c = env.cls; c != null; c = c.outer()) {
      addExtensions(c, name, out);
    }
    ClassSymbol module = a.moduleOf(env.cls);
    if (module != null) {
      addExtensions(module, name, out);
    }
    for (ClassSymbol m : a.packageModules.getOrDefault(env.cls.packageName(), List.of())) {
      addExtensions(m, name, out);
    }
    for (ClassSymbol c : a.syms.sourceClasses()) {
      if (c.packageName().equals(env.cls.packageName()) && c.outer() == null) {
        addExtensions(c, name, out);
      }
    }
    FileScope fs = a.fileScope();
    for (FileScope.StaticImport si : fs.staticSingleImports()) {
      String visible = si.alias() != null ? si.alias() : si.member();
      if (visible.equals(name)) {
        for (MethodSymbol ms : si.owner().methods(si.member())) {
          if (ms.isExtension()) {
            out.add(ms);
          }
        }
      }
    }
    for (ClassSymbol c : fs.staticOnDemandImports()) {
      addExtensions(c, name, out);
    }
    for (String pkg : fs.onDemandPackages()) {
      out.addAll(packageExtensions(pkg).getOrDefault(name, List.of()));
    }
    out.removeIf(m -> !a.lookup.isAccessible(m, m.owner(), null, env.cls));
    return new ArrayList<>(out);
  }

  private static void addExtensions(ClassSymbol c, String name, Set<MethodSymbol> out) {
    for (MethodSymbol m : c.methods(name)) {
      if (m.isExtension() && m.isStatic()) {
        out.add(m);
      }
    }
  }

  /** Extension methods of all (non-JDK) classes of a package, by name. */
  private Map<String, List<MethodSymbol>> packageExtensions(String pkg) {
    return packageExtensions.computeIfAbsent(
        pkg,
        p -> {
          Map<String, List<MethodSymbol>> byName = new HashMap<>();
          for (String simple : a.syms.classPath().list(p, false)) {
            if (simple.contains("$")) {
              continue;
            }
            ClassSymbol c = a.syms.lookup(p.replace('.', '/') + "/" + simple);
            if (c == null || !c.has(Flags.JSHARP)) {
              continue;
            }
            for (MethodSymbol m : c.allMethods()) {
              if (m.isExtension()) {
                byName.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m);
              }
            }
          }
          return byName;
        });
  }

  // ------------------------------------------------------------------ method references

  BExpr methodRef(Expr.MethodRef mr, ClassType fi) {
    Types.MethodType ft = types().functionType(fi);
    MethodSymbol sam = types().findSam(fi.sym());
    Span span = mr.span();
    List<ArgInfo> argTypes = new ArrayList<>();
    for (Type p : ft.params()) {
      argTypes.add(ArgInfo.ofType(p, span));
    }
    if (mr.typeTarget() != null || mr.name().equals("new")) {
      Type t = mr.typeTarget() != null ? a.resolveType(mr.typeTarget()) : typeOfTarget(mr.target());
      if (t.isError()) {
        return new BExpr.Error(fi, span);
      }
      if (t instanceof Type.ArrayType at) {
        if (!mr.name().equals("new") || ft.params().size() != 1 || types().primitiveView(ft.params().getFirst()) != PrimType.INT) {
          a.error(Code.LAMBDA_MISMATCH, span, "array constructor reference " + at.display() + "::new needs a function from int to an array");
          return new BExpr.Error(fi, span);
        }
        checkRefReturn(at, ft, span);
        return new BExpr.MethodRef(fi, sam, null, BExpr.RefKind.ARRAY_CONSTRUCTOR, null, at, span);
      }
      if (!(t instanceof ClassType ct)) {
        a.error(Code.LAMBDA_MISMATCH, span, "cannot create a reference to a constructor of " + t.display());
        return new BExpr.Error(fi, span);
      }
      if (ct.sym().isAbstract() || ct.sym().isInterface()) {
        a.error(Code.ABSTRACT_INSTANTIATION, span, "cannot reference the constructor of abstract type " + ct.display());
        return new BExpr.Error(fi, span);
      }
      boolean infer = !ct.sym().typeParams().isEmpty() && ct.args().isEmpty();
      Selected sel = selectCtor(ct.sym().methods(MethodSymbol.CONSTRUCTOR), infer ? ct.sym().thisType() : ct, argTypes, infer ? ct.sym().typeParams() : List.of(), null, span, ct.sym().name());
      if (sel == null) {
        return new BExpr.Error(fi, span);
      }
      ClassType created = infer ? (ClassType) Types.subst(ct.sym().thisType(), sel.solution) : ct;
      checkRefReturn(created, ft, span);
      return new BExpr.MethodRef(fi, sam, sel.method, BExpr.RefKind.CONSTRUCTOR, null, created, span);
    }
    Attr.Target target = a.target(mr.target(), true);
    switch (target) {
      case Attr.TypeTarget tt -> {
        Type site = tt.type();
        if (site.isError()) {
          return new BExpr.Error(fi, span);
        }
        List<MethodSymbol> all = a.lookup.findMethods(site, mr.name());
        List<MethodSymbol> statics = all.stream().filter(MethodSymbol::isStatic).toList();
        List<MethodSymbol> instances = all.stream().filter(x -> !x.isStatic()).toList();
        Selected st = statics.isEmpty() ? null : select(statics, site, argTypes, null, null, null, span, mr.name(), false);
        Selected un = null;
        if (!instances.isEmpty() && !ft.params().isEmpty() && types().isSubtype(types().boxIfPrimitive(ft.params().getFirst()), site)) {
          Type recvType = ft.params().getFirst();
          un = select(instances, a.captureSite(recvType instanceof PrimType p ? a.syms.boxed(p) : recvType), argTypes.subList(1, argTypes.size()), null, null, null, span, mr.name(), false);
        }
        if (st != null && un != null) {
          a.error(Code.AMBIGUOUS_CALL, span, "method reference " + site.display() + "::" + mr.name() + " is ambiguous (both a static and an instance method apply)");
          return new BExpr.Error(fi, span);
        }
        if (st != null) {
          Type ret = Types.subst(st.method.returnType(), st.solution);
          checkRefReturn(ret, ft, span);
          return new BExpr.MethodRef(fi, sam, st.method, BExpr.RefKind.STATIC, null, ret, span);
        }
        if (un != null) {
          Type recvType = ft.params().getFirst();
          Type ret = Types.subst(Types.subst(un.method.returnType(), siteSubst(a.captureSite(recvType), un.method)), un.solution);
          checkRefReturn(types().uncapture(ret), ft, span);
          return new BExpr.MethodRef(fi, sam, un.method, BExpr.RefKind.UNBOUND, null, ret, span);
        }
        reportRefMismatch(site, mr.name(), all, ft, span);
        return new BExpr.Error(fi, span);
      }
      case Attr.ValueTarget v -> {
        BExpr recv = v.expr();
        if (recv.type().isError()) {
          return new BExpr.Error(fi, span);
        }
        if (recv.type() instanceof PrimType p) {
          recv = a.coerce(recv, a.syms.boxed(p), recv.span());
        }
        a.checkReceiverNullness(recv, mr.target().span());
        Type site = a.captureSite(recv.type());
        List<MethodSymbol> all = a.lookup.findMethods(site, mr.name());
        Selected sel = all.isEmpty() ? null : select(all, site, argTypes, null, null, null, span, mr.name(), false);
        if (sel == null) {
          reportRefMismatch(recv.type(), mr.name(), all, ft, span);
          return new BExpr.Error(fi, span);
        }
        Type ret = types().uncapture(Types.subst(Types.subst(sel.method.returnType(), siteSubst(site, sel.method)), sel.solution));
        checkRefReturn(ret, ft, span);
        if (sel.method.isStatic()) {
          return new BExpr.MethodRef(fi, sam, sel.method, BExpr.RefKind.STATIC, null, ret, span);
        }
        return new BExpr.MethodRef(fi, sam, sel.method, BExpr.RefKind.BOUND, recv, ret, span);
      }
      case Attr.SuperTarget s -> {
        a.error(Code.UNSUPPORTED_FEATURE, span, "'super::method' references are not supported; use a lambda");
        return new BExpr.Error(fi, span);
      }
      case Attr.PackageTarget p -> {
        a.error(Code.UNRESOLVED_NAME, span, "'" + p.name() + "' is a package");
        return new BExpr.Error(fi, span);
      }
    }
  }

  private Type typeOfTarget(Expr e) {
    Attr.Target t = a.target(e, true);
    if (t instanceof Attr.TypeTarget tt) {
      return tt.type();
    }
    a.error(Code.LAMBDA_MISMATCH, e.span(), "'::new' needs a type on the left");
    return Type.ErrorType.INSTANCE;
  }

  private void checkRefReturn(Type ret, Types.MethodType ft, Span span) {
    if (ft.ret() == PrimType.VOID || ret == null || ret.isError()) {
      return;
    }
    if (ret == PrimType.VOID) {
      a.error(Code.LAMBDA_MISMATCH, span, "the referenced method returns void, but a value of type " + ft.ret().display() + " is expected");
      return;
    }
    if (types().assignConversion(ret, ft.ret(), null) == Types.Conv.NONE) {
      a.error(Code.LAMBDA_MISMATCH, span, "the referenced method returns " + ret.display() + ", but " + ft.ret().display() + " is expected");
    }
  }

  private void reportRefMismatch(Type site, String name, List<MethodSymbol> all, Types.MethodType ft, Span span) {
    if (all.isEmpty()) {
      a.reportNoMember(site, name, span);
      return;
    }
    StringBuilder sb = new StringBuilder("(");
    for (int i = 0; i < ft.params().size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(ft.params().get(i).display());
    }
    sb.append(')');
    a.error(Code.LAMBDA_MISMATCH, span, "no method " + site.display() + "::" + name + " is compatible with " + sb + " -> " + ft.ret().display());
  }

  /** Result type of a method reference given parameter types (for inference), or null. */
  private Type methodRefResultType(Expr.MethodRef mr, List<Type> ps) {
    Attr.Speculation<Type> s =
        a.speculate(
            () -> {
              List<ArgInfo> argTypes = new ArrayList<>();
              for (Type p : ps) {
                argTypes.add(ArgInfo.ofType(p, mr.span()));
              }
              if (mr.name().equals("new")) {
                Type t = mr.typeTarget() != null ? a.resolveType(mr.typeTarget()) : typeOfTarget(mr.target());
                if (t instanceof ClassType ct && !ct.sym().typeParams().isEmpty() && ct.args().isEmpty()) {
                  Selected sel = selectCtor(ct.sym().methods(MethodSymbol.CONSTRUCTOR), ct.sym().thisType(), argTypes, ct.sym().typeParams(), null, mr.span(), ct.sym().name());
                  return sel == null ? null : Types.subst(ct.sym().thisType(), sel.solution);
                }
                return t;
              }
              Attr.Target target = a.target(mr.target(), true);
              if (target instanceof Attr.TypeTarget tt) {
                for (MethodSymbol m : a.lookup.findMethods(tt.type(), mr.name())) {
                  if (m.isStatic() && m.params().size() == ps.size()) {
                    Selected sel = select(List.of(m), tt.type(), argTypes, null, null, null, mr.span(), mr.name(), false);
                    if (sel != null) {
                      return Types.subst(m.returnType(), sel.solution);
                    }
                  } else if (!m.isStatic() && m.params().size() == ps.size() - 1 && !ps.isEmpty()) {
                    Type recvType = types().boxIfPrimitive(ps.getFirst());
                    Selected sel = select(List.of(m), a.captureSite(recvType), argTypes.subList(1, argTypes.size()), null, null, null, mr.span(), mr.name(), false);
                    if (sel != null) {
                      return types().uncapture(Types.subst(Types.subst(m.returnType(), siteSubst(a.captureSite(recvType), m)), sel.solution));
                    }
                  }
                }
                return null;
              }
              if (target instanceof Attr.ValueTarget v) {
                Type site = a.captureSite(types().boxIfPrimitive(v.expr().type()));
                List<MethodSymbol> all = a.lookup.findMethods(site, mr.name());
                Selected sel = all.isEmpty() ? null : select(all, site, argTypes, null, null, null, mr.span(), mr.name(), false);
                return sel == null ? null : types().uncapture(Types.subst(Types.subst(sel.method.returnType(), siteSubst(site, sel.method)), sel.solution));
              }
              return null;
            });
    return s.result();
  }

  /** For tests/debugging: a fresh variable for synthetic use. */
  VarSymbol temp(String name, Type t, Span span) {
    return a.env.newVar(name, t, Flags.FINAL | Flags.SYNTHETIC, VarSymbol.Kind.LOCAL, span);
  }
}

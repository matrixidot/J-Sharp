package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.ast.Arg;
import io.github.matrixidot.jsharp.compiler.ast.AstWalk;
import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.ast.TypeNode;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.resolve.FileScope;
import io.github.matrixidot.jsharp.compiler.resolve.Suggestions;
import io.github.matrixidot.jsharp.compiler.resolve.TypeScope;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import io.github.matrixidot.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.Comparator;
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

  Types types() {
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

    /** A generic call/creation whose type depends on the target (attributed per candidate). */
    final boolean polyCall;

    private Type captured;

    /** The argument type with wildcards captured (once, so all candidates share the variables). */
    Type captured(Types types) {
      if (captured == null) {
        captured = type instanceof ClassType ct ? types.capture(ct) : type;
      }
      return captured;
    }

    ArgInfo(String name, Expr expr, BExpr bound, Type type, boolean deferred, Span span) {
      this(name, expr, bound, type, deferred, span, false);
    }

    ArgInfo(
        String name,
        Expr expr,
        BExpr bound,
        Type type,
        boolean deferred,
        Span span,
        boolean polyCall) {
      this.name = name;
      this.expr = expr;
      this.bound = bound;
      this.type = type;
      this.deferred = deferred;
      this.span = span;
      this.polyCall = polyCall;
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
      case Expr.CollectionLiteral cl -> true;
      case Expr.MapLiteral ml -> true;
      case Expr.Paren p -> isDeferred(p.expr());
      case Expr.Conditional c -> isDeferred(c.then()) || isDeferred(c.otherwise());
      case Expr.Switch s -> s.arms().stream().anyMatch(arm -> isDeferred(arm.body()));
      case Expr.Tuple t -> t.elements().stream().anyMatch(x -> isDeferred(x.value()));
      default -> false;
    };
  }

  /** Expressions whose type benefits from a target (generic calls, diamond {@code new}). */
  static boolean isRetargetable(Expr e) {
    return switch (e) {
      case Expr.Call c -> true;
      case Expr.New n -> n.type() != null;
      case Expr.Paren p -> isRetargetable(p.expr());
      case Expr.Conditional c -> isRetargetable(c.then()) || isRetargetable(c.otherwise());
      // Switch expressions are poly expressions: arms are typed against the parameter type.
      case Expr.Switch s -> true;
      case Expr.Binary b -> b.op() == Expr.BinaryOp.COALESCE;
      default -> false;
    };
  }

  /** When set, the class from which accessibility is judged instead of the current class. */
  ClassSymbol accessFrom;

  List<ArgInfo> prepare(List<Arg> args) {
    List<ArgInfo> out = new ArrayList<>();
    for (Arg arg : args) {
      Expr fv = a.asFunctionValue(arg.value());
      Expr e = fv != null ? fv : arg.value(); // a function passed by name (D083)
      if (isDeferred(e)) {
        out.add(new ArgInfo(arg.name(), e, null, null, true, arg.span()));
      } else if (isPolyCandidate(e)) {
        // Generic calls are tried standalone first; if their type arguments could not be fully
        // inferred (or they only check against a target), they become poly arguments typed
        // from the selected parameter type, as in Java.
        Attr.Speculation<BExpr> s = a.speculate(() -> a.value(e, null));
        boolean flexible = s.result() != null && a.flexibleResults.contains(s.result());
        if (s.hasErrors() || flexible) {
          Type provisional = s.result() == null ? Type.ErrorType.INSTANCE : s.result().type();
          out.add(
              new ArgInfo(
                  arg.name(), e, null, s.hasErrors() ? null : provisional, true, arg.span(), true));
        } else if (!s.hasDiagnostics() && sideEffectFree(e)) {
          out.add(new ArgInfo(arg.name(), e, s.result(), s.result().type(), false, arg.span()));
        } else {
          BExpr b = a.value(e, null);
          out.add(new ArgInfo(arg.name(), e, b, b.type(), false, arg.span()));
        }
      } else {
        BExpr b = a.value(e, null);
        out.add(new ArgInfo(arg.name(), e, b, b.type(), false, arg.span()));
      }
    }
    return out;
  }

  static boolean isPolyCandidate(Expr e) {
    return switch (e) {
      case Expr.Call c -> true;
      case Expr.New n -> n.type() != null && n.anonBody() == null;
      case Expr.Paren p -> isPolyCandidate(p.expr());
      default -> false;
    };
  }

  /** True if re-using a speculative attribution of {@code e} loses nothing (no lambdas/classes). */
  private static boolean sideEffectFree(Expr e) {
    boolean[] ok = {true};
    AstWalk.walk(
        e,
        n -> {
          if (n instanceof Expr.Lambda
              || n instanceof Expr.MethodRef
              || n instanceof Expr.New nw && nw.anonBody() != null) {
            ok[0] = false;
          }
          return ok[0];
        });
    return ok[0];
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
          return a.members.safeAccess(
              m.target(),
              span,
              recv ->
                  instanceCall(
                      recv, m.name(), m.typeArgs(), m.nameSpan(), prepare(c.args()), pt, span));
        }
        Attr.Target t = a.target(m.target(), true);
        return switch (t) {
          case Attr.ValueTarget v ->
              instanceCall(
                  v.expr(), m.name(), m.typeArgs(), m.nameSpan(), prepare(c.args()), pt, span);
          case Attr.TypeTarget tt ->
              staticCall(tt.type(), m.name(), m.typeArgs(), m.nameSpan(), c.args(), pt, span);
          case Attr.SuperTarget s ->
              superCall(s, m.name(), m.typeArgs(), m.nameSpan(), c.args(), pt, span);
          case Attr.PackageTarget p -> {
            a.error(
                Code.UNRESOLVED_NAME,
                m.nameSpan(),
                "cannot find function '" + m.name() + "' in package " + p.name());
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

  /** Top-level functions an unqualified name can call, by priority. */
  record Tiers(
      List<MethodSymbol> module, List<MethodSymbol> imported, List<MethodSymbol> onDemand) {}

  /**
   * Top-level functions named {@code name}: this file's module, then the package's other modules;
   * static single imports; static on-demand imports (including the Prelude).
   */
  Tiers functionTiers(String name) {
    Env env = a.env;
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
    return new Tiers(moduleCands, imported, onDemand);
  }

  /**
   * The methods an unqualified name denotes when used as a function value (D083), found as for an
   * unqualified call: the first enclosing class declaring a method of that name, else the first
   * non-empty tier of top-level functions. Null if there is none.
   *
   * @param owner the enclosing class whose methods these are, or null for top-level functions
   * @param staticOnly instance methods of {@code owner} cannot be used from here
   * @param current {@code owner} is the innermost class (receiver {@code this}, not an outer one)
   */
  record FunctionGroup(
      List<MethodSymbol> methods, ClassSymbol owner, boolean staticOnly, boolean current) {}

  FunctionGroup functionGroup(String name) {
    Env env = a.env;
    boolean staticOnly = env.isStatic;
    boolean first = true;
    for (ClassSymbol c = env.cls; c != null; c = c.outer()) {
      if (a.lookup.hasMethodNamed(c.thisType(), name)) {
        return new FunctionGroup(a.lookup.findMethods(c.thisType(), name), c, staticOnly, first);
      }
      if (!c.has(Flags.LOCAL) && !c.has(Flags.ANONYMOUS) || c.has(Flags.STATIC)) {
        staticOnly = true;
      }
      first = false;
    }
    Tiers t = functionTiers(name);
    for (List<MethodSymbol> tier : List.of(t.module(), t.imported(), t.onDemand())) {
      if (!tier.isEmpty()) {
        return new FunctionGroup(tier, null, true, false);
      }
    }
    return null;
  }

  /** A call of a local function: its captured locals, then the arguments (D083). */
  private BExpr localFunctionCall(
      LocalFunctions.Fn fn,
      List<TypeNode> typeArgs,
      Span nameSpan,
      List<Arg> args,
      Type pt,
      Span span) {
    if (!typeArgs.isEmpty()) {
      a.error(
          Code.WRONG_TYPE_ARG_COUNT,
          nameSpan,
          "local function '" + fn.sym.name() + "' is not generic");
    }
    List<ArgInfo> infos = prepare(args);
    Overloads.Selected sel =
        a.overloads.select(List.of(fn.sym), null, infos, null, pt, null, span, fn.sym.name(), true);
    if (sel == null) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    BExpr recv = fn.capturesThis ? a.thisValue(span) : null;
    BExpr r = finish(sel, recv, fn.capturesThis ? a.env.cls.thisType() : null, infos, span, false);
    if (!(r instanceof BExpr.Call c)) {
      return r;
    }
    List<BExpr> all = new ArrayList<>(a.localFunctions.captureArgs(fn, span));
    all.addAll(c.args());
    return new BExpr.Call(c.receiver(), fn.impl, all, c.kind(), c.type(), c.span());
  }

  /** {@code name(args)}: local functional values, enclosing classes, module functions, imports. */
  private BExpr simpleCall(
      String name, List<TypeNode> typeArgs, Span nameSpan, List<Arg> args, Type pt, Span span) {
    Env env = a.env;
    Scope.Found local = env.scope.lookup(name);
    if (local != null) {
      BExpr f = a.asValue(a.target(new Expr.Name(name, List.of(), nameSpan), true), nameSpan);
      return invokeFunctional(f, args, span);
    }
    LocalFunctions.Fn localFn = env.scope.lookupFunction(name);
    if (localFn != null) {
      return localFunctionCall(localFn, typeArgs, nameSpan, args, pt, span);
    }
    // Enclosing classes, innermost first: the first class with a method of this name wins.
    boolean staticOnly = env.isStatic;
    boolean first = true;
    for (ClassSymbol c = env.cls; c != null; c = c.outer()) {
      if (a.lookup.hasMethodNamed(c.thisType(), name)) {
        List<MethodSymbol> cands = a.lookup.findMethods(c.thisType(), name);
        List<ArgInfo> infos = prepare(args);
        Overloads.Selected sel =
            a.overloads.select(
                cands, c.thisType(), infos, explicitTypeArgs(typeArgs), pt, null, span, name, true);
        if (sel == null) {
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        BExpr recv = null;
        if (!sel.method.isStatic()) {
          if (staticOnly) {
            a.report(
                a.err(
                        Code.STATIC_CONTEXT,
                        nameSpan,
                        "instance method '" + name + "' cannot be called from a static context")
                    .note(
                        first
                            ? "this code is static"
                            : "nested classes in J# are static and have no outer instance"));
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
      if (!c.has(Flags.LOCAL) && !c.has(Flags.ANONYMOUS) || c.has(Flags.STATIC)) {
        staticOnly = true;
      }
      first = false;
    }
    Tiers tiers = functionTiers(name);
    ClassSymbol module = a.moduleOf(env.cls);
    List<MethodSymbol> moduleCands = tiers.module();
    List<MethodSymbol> imported = tiers.imported();
    List<MethodSymbol> onDemand = tiers.onDemand();
    for (List<MethodSymbol> tier : List.of(moduleCands, imported, onDemand)) {
      if (!tier.isEmpty()) {
        List<ArgInfo> infos = prepare(args);
        Overloads.Selected sel =
            a.overloads.select(
                tier,
                null,
                infos,
                explicitTypeArgs(typeArgs),
                pt,
                null,
                span,
                name,
                tier == onDemand || tier == imported);
        if (sel == null && tier == moduleCands && (!imported.isEmpty() || !onDemand.isEmpty())) {
          // A module function with the same name but different parameters: fall back to imports.
          Overloads.Selected again =
              a.overloads.select(
                  imported.isEmpty() ? onDemand : imported,
                  null,
                  infos,
                  explicitTypeArgs(typeArgs),
                  pt,
                  null,
                  span,
                  name,
                  false);
          if (again != null) {
            return finish(again, null, null, infos, span, false);
          }
          a.overloads.select(
              tier, null, infos, explicitTypeArgs(typeArgs), pt, null, span, name, true);
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        if (sel == null) {
          if (tier == moduleCands) {
            a.overloads.select(
                tier, null, infos, explicitTypeArgs(typeArgs), pt, null, span, name, true);
          }
          return new BExpr.Error(Type.ErrorType.INSTANCE, span);
        }
        return finish(sel, null, null, infos, span, false);
      }
    }
    // A type name used like a call: `Point(1, 2)` -> hint at `new`.
    if (a.typeScope().find(name) instanceof TypeScope.FoundClass) {
      a.report(
          a.err(
              Code.NOT_CALLABLE,
              nameSpan,
              "'" + name + "' is a type; use 'new " + name + "(...)' to create an instance"));
      prepare(args);
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    Diagnostic.Builder d =
        a.err(Code.UNRESOLVED_NAME, nameSpan, "cannot find function '" + name + "'");
    Set<String> names = new LinkedHashSet<>();
    for (ClassSymbol c = env.cls; c != null; c = c.outer()) {
      for (MethodSymbol m : c.allMethods()) {
        names.add(m.name());
      }
    }
    if (module != null) {
      module.allMethods().forEach(m -> names.add(m.name()));
    }
    for (ClassSymbol c : a.fileScope().staticOnDemandImports()) {
      c.allMethods().forEach(m -> names.add(m.name()));
    }
    String hidden = env.scope.hiddenFunctionNote(name);
    String guess = Suggestions.closest(name, names);
    if (hidden != null) {
      d.help(hidden);
    } else if (guess != null) {
      d.help("did you mean '" + guess + "'?");
    }
    a.report(d.label("not found"));
    prepare(args);
    return new BExpr.Error(Type.ErrorType.INSTANCE, span);
  }

  /** {@code recv.name(args)}: members first, then extension methods. */
  BExpr instanceCall(
      BExpr recv,
      String name,
      List<TypeNode> typeArgs,
      Span nameSpan,
      List<ArgInfo> infos,
      Type pt,
      Span span) {
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
      Overloads.Selected sel =
          a.overloads.select(
              extensions, null, withReceiver(recv, infos), explicit, pt, recv, span, name, false);
      if (sel != null
          && (site instanceof PrimType
              || sel.method.params().getFirst().type().nullness() != Nullness.NON_NULL)) {
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
      Type captured = a.members.captureSite(memberSite);
      Overloads.Selected sel =
          a.overloads.select(
              members, captured, infos, explicit, pt, null, span, name, extensions.isEmpty());
      if (sel != null) {
        a.members.checkReceiverNullness(memberRecv, nameSpan);
        if (sel.method.isStatic()) {
          memberRecv = null;
        }
        return finish(sel, memberRecv, captured, infos, span, false);
      }
      if (extensions.isEmpty()) {
        return new BExpr.Error(Type.ErrorType.INSTANCE, span);
      }
    }
    // Only extensions whose receiver parameter accepts this receiver are relevant.
    List<MethodSymbol> applicableExt = new ArrayList<>();
    for (MethodSymbol ext : extensions) {
      Type rp = ext.params().isEmpty() ? null : ext.params().getFirst().type();
      boolean ok;
      if (rp == null) {
        ok = false;
      } else if (rp instanceof Type.TypeVar) {
        ok = true;
      } else if (!ext.typeParams().isEmpty()) {
        ok =
            rp instanceof PrimType
                ? types().primitiveView(site) == rp
                : types().isSubtype(types().boxIfPrimitive(site).erasure(), rp.erasure());
      } else {
        ok = types().assignConversion(site, rp, null) != Types.Conv.NONE;
      }
      if (ok) {
        applicableExt.add(ext);
      }
    }
    extensions = applicableExt;
    if (!extensions.isEmpty()) {
      List<ArgInfo> withRecv = withReceiver(recv, infos);
      Overloads.Selected sel =
          a.overloads.select(extensions, null, withRecv, explicit, pt, recv, span, name, false);
      if (sel != null) {
        if (site.isReference()
            && site.nullness() == Nullness.NULLABLE
            && sel.method.params().getFirst().type().nullness() == Nullness.NON_NULL) {
          a.members.checkReceiverNullness(recv, nameSpan);
          List<ArgInfo> retyped =
              withReceiver(
                  new BExpr.Conv(
                      recv,
                      BExpr.ConvKind.RETYPE,
                      site.withNullness(Nullness.NON_NULL),
                      recv.span()),
                  infos);
          return finish(sel, null, null, retyped, span, false);
        }
        return finish(sel, null, null, withRecv, span, false);
      }
      if (!members.isEmpty()) {
        a.overloads.select(
            members,
            a.members.captureSite(memberSite),
            infos,
            explicit,
            pt,
            null,
            span,
            name,
            true);
      } else {
        a.overloads.select(extensions, null, withRecv, explicit, pt, recv, span, name, true);
      }
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    // No method: maybe a property holding a function value, e.g. `obj.handler(x)`.
    if (a.lookup.findProperty(memberSite, name) != null
        || a.lookup.findField(memberSite, name) != null) {
      BExpr f = a.members.member(recv, name, nameSpan, span);
      if (f.type() instanceof ClassType ct && types().findSam(ct.sym()) != null) {
        return invokeFunctionalPrepared(f, infos, span);
      }
      a.error(
          Code.NOT_CALLABLE,
          nameSpan,
          "'"
              + name
              + "' is a "
              + (a.lookup.findProperty(memberSite, name) != null ? "property" : "field")
              + " of type "
              + f.type().display()
              + ", not a method");
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    a.members.checkReceiverNullness(recv, nameSpan);
    Diagnostic.Builder d =
        a.err(
            Code.UNRESOLVED_MEMBER,
            nameSpan,
            memberSite.withNullness(Nullness.NON_NULL).display() + " has no method '" + name + "'");
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

  private BExpr staticCall(
      Type site,
      String name,
      List<TypeNode> typeArgs,
      Span nameSpan,
      List<Arg> args,
      Type pt,
      Span span) {
    if (site.isError()) {
      prepare(args);
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    List<MethodSymbol> cands = new ArrayList<>(a.lookup.findMethods(site, name));
    if (cands.isEmpty()) {
      a.members.reportNoMember(site, name, nameSpan);
      prepare(args);
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    List<ArgInfo> infos = prepare(args);
    Overloads.Selected sel =
        a.overloads.select(
            cands, site, infos, explicitTypeArgs(typeArgs), pt, null, span, name, true);
    if (sel == null) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    if (!sel.method.isStatic()) {
      // `Base.method()` from a subclass is not valid; instance methods need an instance.
      a.report(
          a.err(
              Code.STATIC_CONTEXT,
              nameSpan,
              "'"
                  + name
                  + "' is an instance method of "
                  + site.display()
                  + "; call it on an instance"));
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    return finish(sel, null, site, infos, span, false);
  }

  private BExpr superCall(
      Attr.SuperTarget s,
      String name,
      List<TypeNode> typeArgs,
      Span nameSpan,
      List<Arg> args,
      Type pt,
      Span span) {
    List<MethodSymbol> cands = new ArrayList<>(a.lookup.findMethods(s.superType(), name));
    boolean qualified = s.superType().sym().isInterface();
    // Default methods of directly implemented interfaces are callable through super as well.
    for (ClassType it : qualified ? List.<ClassType>of() : a.env.cls.interfaces()) {
      for (MethodSymbol m : a.lookup.findMethods(it, name)) {
        if (m.has(Flags.DEFAULT) && !cands.contains(m)) {
          cands.add(m);
        }
      }
    }
    if (cands.isEmpty()) {
      a.members.reportNoMember(s.superType(), name, nameSpan);
      prepare(args);
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    List<ArgInfo> infos = prepare(args);
    Overloads.Selected sel =
        a.overloads.select(
            cands, s.superType(), infos, explicitTypeArgs(typeArgs), pt, null, span, name, true);
    if (sel == null) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    // The receiver is typed as the class or interface the call goes to: codegen names it as the
    // invokespecial owner (the direct superclass, or the interface of I.super / a default).
    ClassType via = s.superType();
    if (sel.method.owner().isInterface() && !via.sym().isInterface()) {
      for (ClassType it : a.env.cls.interfaces()) {
        if (a.lookup.isSubclass(it.sym(), sel.method.owner())) {
          via = it;
          break;
        }
      }
    }
    BExpr recv = new BExpr.This(via, s.span());
    return finish(sel, recv, s.superType(), infos, span, true);
  }

  /** {@code this(...)} / {@code super(...)} inside a constructor. */
  private BExpr constructorChain(boolean isSuper, List<Arg> args, Span span) {
    Env env = a.env;
    if (!env.inConstructor || env.method == null || !env.method.isConstructor()) {
      a.error(
          Code.INVALID_CONSTRUCTOR_CALL,
          span,
          "'"
              + (isSuper ? "super" : "this")
              + "(...)' can only be called at the start of a constructor");
      prepare(args);
      return new BExpr.Error(PrimType.VOID, span);
    }
    if (!env.beforeSuperCall) {
      a.error(
          Code.INVALID_CONSTRUCTOR_CALL,
          span,
          "'"
              + (isSuper ? "super" : "this")
              + "(...)' must be the first statement of the constructor");
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
    Overloads.Selected sel =
        a.overloads.select(
            ctors,
            target,
            infos,
            null,
            null,
            null,
            span,
            (isSuper ? "super" : "this") + " constructor of " + target.sym().name(),
            true);
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
      a.error(
          Code.NOT_CALLABLE,
          f.span(),
          "a value of type " + f.type().display() + " cannot be called");
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    a.members.checkReceiverNullness(f, f.span());
    MethodSymbol sam = types().findSam(ct.sym());
    Overloads.Selected sel =
        a.overloads.select(
            List.of(sam),
            a.members.captureSite(ct),
            infos,
            null,
            null,
            null,
            span,
            sam.name(),
            true);
    if (sel == null) {
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    return finish(sel, f, a.members.captureSite(ct), infos, span, false);
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
    boolean infer =
        inferArgs
            && !c.typeParams().isEmpty()
            && (ct.args().isEmpty()
                || pt != null && ct.sym() == (pt instanceof ClassType pc ? pc.sym() : null));
    ClassType site = infer ? c.thisType() : ct;
    Type expected = infer && pt != null ? pt.withNullness(Nullness.NON_NULL) : null;
    if (infer
        && pt instanceof ClassType pc
        && pc.sym() == c
        && !pc.args().isEmpty()
        && pc.args().stream().noneMatch(x -> x instanceof Type.WildcardType)) {
      // new() with a fully known target: no inference needed.
      site = pc.withNullness(Nullness.NON_NULL) instanceof ClassType x ? x : site;
      infer = false;
    }
    Overloads.Selected sel =
        selectCtor(
            ctors, site, infos, infer ? c.typeParams() : List.of(), expected, span, c.name());
    if (sel == null) {
      return new BExpr.Error(ct, span);
    }
    ClassType created = infer ? (ClassType) Types.subst(c.thisType(), sel.solution) : site;
    if (infer) {
      created = (ClassType) types().uncapture(created);
    }
    List<BExpr> finalArgs = finalArgs(sel, created, infos, span);
    BExpr result = new BExpr.New(created, sel.method, finalArgs, span);
    a.forgetScriptVarNarrowings();
    if (infer && mentionsAny(c.thisType(), sel.defaultedVars)) {
      a.flexibleResults.add(result);
    }
    return result;
  }

  private Overloads.Selected selectCtor(
      List<MethodSymbol> ctors,
      ClassType site,
      List<ArgInfo> infos,
      List<TypeVarSymbol> classVars,
      Type expected,
      Span span,
      String name) {
    extraInferenceVars = classVars;
    extraExpected = expected;
    try {
      return a.overloads.select(
          ctors, site, infos, null, null, null, span, "constructor of " + name, true);
    } finally {
      extraInferenceVars = List.of();
      extraExpected = null;
    }
  }

  List<TypeVarSymbol> extraInferenceVars = List.of();
  Type extraExpected;

  // ------------------------------------------------------------------ finishing a call

  BExpr finish(
      Overloads.Selected sel,
      BExpr recv,
      Type site,
      List<ArgInfo> args,
      Span span,
      boolean isSpecial) {
    MethodSymbol m = sel.method;
    a.members.checkDeprecated(m, span);
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
    Type t = Types.subst(Types.subst(ret, a.overloads.siteSubst(site, m)), sel.solution);
    t = types().uncapture(t);
    if (m.name().equals("getClass")
        && m.params().isEmpty()
        && recv != null
        && m.owner() == a.syms.objectSym()) {
      t =
          a.syms.classType(
              new Type.WildcardType(Type.WildcardType.Kind.EXTENDS, recv.type().erasure()));
    }
    if (m.name().equals("clone")
        && m.params().isEmpty()
        && recv != null
        && recv.type() instanceof Type.ArrayType at) {
      t = at.withNullness(Nullness.NON_NULL); // T[].clone() returns T[] (JLS 10.7)
    }
    if (m.isAbstract() && isSpecial && !m.isConstructor()) {
      a.error(
          Code.INVALID_THIS, span, "cannot call abstract method " + m.signature() + " directly");
    }
    BExpr.CallKind kind = isSpecial ? BExpr.CallKind.SPECIAL : Members.callKind(m, recv, false);
    if (m.isStatic()) {
      kind = BExpr.CallKind.STATIC;
      recv = null;
    }
    BExpr result = new BExpr.Call(recv, m, finalArgs, kind, t, span);
    a.forgetScriptVarNarrowings();
    if (mentionsAny(ret, sel.defaultedVars)) {
      a.flexibleResults.add(result);
    }
    return result;
  }

  private static boolean mentionsAny(Type t, Set<TypeVarSymbol> vars) {
    if (vars.isEmpty() || t == null) {
      return false;
    }
    return switch (t) {
      case Type.TypeVar v -> vars.contains(v.sym());
      case ClassType c -> c.args().stream().anyMatch(x -> mentionsAny(x, vars));
      case Type.ArrayType arr -> mentionsAny(arr.elem(), vars);
      case Type.WildcardType w -> w.bound() != null && mentionsAny(w.bound(), vars);
      default -> false;
    };
  }

  /**
   * Converts arguments to the instantiated parameter types, packing varargs and filling defaults.
   */
  private List<BExpr> finalArgs(Overloads.Selected sel, Type site, List<ArgInfo> args, Span span) {
    MethodSymbol m = sel.method;
    List<MethodSymbol.Param> params = m.params();
    List<BExpr> out = new ArrayList<>();
    List<Integer> sourceIndex = new ArrayList<>();
    for (int p = 0; p < params.size(); p++) {
      Type pt = sel.paramTypes.get(p);
      List<Integer> mapped = sel.mapping.get(p);
      sourceIndex.add(mapped.size() == 1 ? mapped.getFirst() : -1);
      boolean varargsSlot = sel.phase == Overloads.Phase.VARARGS && p == params.size() - 1;
      if (varargsSlot) {
        Type.ArrayType at =
            pt instanceof Type.ArrayType x ? x : Type.ArrayType.of(Type.ErrorType.INSTANCE);
        List<BExpr> elems = new ArrayList<>();
        for (int idx : mapped) {
          elems.add(finalArg(args.get(idx), at.elem()));
        }
        Type elemErased = at.elem() instanceof Type.TypeVar ? at.elem().erasure() : at.elem();
        out.add(
            new BExpr.NewArray(
                Type.ArrayType.of(elemErased.withNullness(at.elem().nullness())),
                List.of(),
                elems,
                span));
        continue;
      }
      if (mapped.isEmpty()) {
        out.add(defaultArg(m, p, pt, span));
        continue;
      }
      out.add(finalArg(args.get(mapped.getFirst()), pt));
    }
    return inSourceOrder(out, sourceIndex, span);
  }

  /**
   * Named arguments may list arguments in a different order than the parameters. Arguments are
   * still evaluated in source order (as in C# and Kotlin): impure ones are spilled into temps, in
   * source order, inside the first argument, so they run after the receiver and before the call.
   */
  private List<BExpr> inSourceOrder(List<BExpr> out, List<Integer> sourceIndex, Span span) {
    int last = -1;
    boolean reordered = false;
    for (int src : sourceIndex) {
      if (src >= 0) {
        reordered |= src < last;
        last = Math.max(last, src);
      }
    }
    if (!reordered || out.isEmpty()) {
      return out;
    }
    List<Integer> positions = new ArrayList<>();
    for (int i = 0; i < out.size(); i++) {
      if (sourceIndex.get(i) >= 0 && !isPure(out.get(i))) {
        positions.add(i);
      }
    }
    positions.sort(Comparator.comparingInt(sourceIndex::get));
    List<BExpr> result = new ArrayList<>(out);
    List<VarSymbol> temps = new ArrayList<>();
    for (int pos : positions) {
      BExpr e = out.get(pos);
      VarSymbol tmp =
          a.env.newVar("$arg", e.type(), Flags.FINAL | Flags.SYNTHETIC, VarSymbol.Kind.LOCAL, span);
      temps.add(tmp);
      result.set(pos, new BExpr.Local(tmp, e.span()));
    }
    BExpr first = result.getFirst();
    for (int i = positions.size() - 1; i >= 0; i--) {
      first = new BExpr.Let(temps.get(i), out.get(positions.get(i)), first, span);
    }
    result.set(0, first);
    return result;
  }

  private static boolean isPure(BExpr e) {
    return e instanceof BExpr.Const
        || e instanceof BExpr.Local
        || e instanceof BExpr.This
        || e instanceof BExpr.Lambda;
  }

  private BExpr finalArg(ArgInfo arg, Type pt) {
    if (arg.deferred) {
      Type target = arg.polyCall ? types().uncapture(pt) : pt;
      return a.coerce(a.expr(arg.expr, target), target, arg.span);
    }
    Type cap = arg.captured(types());
    if (arg.expr != null
        && isRetargetable(arg.expr)
        && !arg.type.isError()
        && types().assignConversion(cap, pt, Attr.intConstant(arg.bound)) == Types.Conv.NONE) {
      return a.exprCoerced(arg.expr, pt, arg.span);
    }
    BExpr b = arg.bound;
    if (cap != arg.type) {
      b = new BExpr.Conv(b, BExpr.ConvKind.RETYPE, cap, b.span());
    }
    return a.coerce(b, pt, arg.span);
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
            a.err(
                    Code.INVALID_DEFAULT_ARGUMENT,
                    param.defaultExpr().span(),
                    "default value of '" + param.name() + "' must be a compile-time constant")
                .help(
                    "use a literal, a constant, or null; compute other values inside the method"));
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
    for (ClassSymbol c : a.syms.sourceClassesIn(env.cls.packageName())) {
      if (c.outer() == null) {
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
    if (mr.target() == null && mr.typeTarget() == null) {
      return functionRef(mr, fi, ft, sam, argTypes);
    }
    if (mr.typeTarget() != null || mr.name().equals("new")) {
      Type t = mr.typeTarget() != null ? a.resolveType(mr.typeTarget()) : typeOfTarget(mr.target());
      if (t.isError()) {
        return new BExpr.Error(fi, span);
      }
      if (t instanceof Type.ArrayType at) {
        if (!mr.name().equals("new")
            || ft.params().size() != 1
            || types().primitiveView(ft.params().getFirst()) != PrimType.INT) {
          a.error(
              Code.LAMBDA_MISMATCH,
              span,
              "array constructor reference "
                  + at.display()
                  + "::new needs a function from int to an array");
          return new BExpr.Error(fi, span);
        }
        checkRefReturn(at, ft, span);
        return new BExpr.MethodRef(fi, sam, null, BExpr.RefKind.ARRAY_CONSTRUCTOR, null, at, span);
      }
      if (!(t instanceof ClassType ct)) {
        a.error(
            Code.LAMBDA_MISMATCH,
            span,
            "cannot create a reference to a constructor of " + t.display());
        return new BExpr.Error(fi, span);
      }
      if (ct.sym().isAbstract() || ct.sym().isInterface()) {
        a.error(
            Code.ABSTRACT_INSTANTIATION,
            span,
            "cannot reference the constructor of abstract type " + ct.display());
        return new BExpr.Error(fi, span);
      }
      boolean infer = !ct.sym().typeParams().isEmpty() && ct.args().isEmpty();
      Type expectedRet = ft.ret() == PrimType.VOID || !ft.ret().isReference() ? null : ft.ret();
      Overloads.Selected sel =
          selectCtor(
              ct.sym().methods(MethodSymbol.CONSTRUCTOR),
              infer ? ct.sym().thisType() : ct,
              argTypes,
              infer ? ct.sym().typeParams() : List.of(),
              expectedRet,
              span,
              ct.sym().name());
      if (sel == null) {
        return new BExpr.Error(fi, span);
      }
      ClassType created = infer ? (ClassType) Types.subst(ct.sym().thisType(), sel.solution) : ct;
      checkRefReturn(created, ft, span);
      return new BExpr.MethodRef(
          fi, sam, sel.method, BExpr.RefKind.CONSTRUCTOR, null, created, span);
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
        Overloads.Selected st =
            statics.isEmpty()
                ? null
                : a.overloads.select(
                    statics, site, argTypes, null, null, null, span, mr.name(), false);
        Overloads.Selected un = null;
        if (!instances.isEmpty()
            && !ft.params().isEmpty()
            && types().isSubtype(types().boxIfPrimitive(ft.params().getFirst()), site)) {
          Type recvType = ft.params().getFirst();
          un =
              a.overloads.select(
                  instances,
                  a.members.captureSite(
                      recvType instanceof PrimType p ? a.syms.boxed(p) : recvType),
                  argTypes.subList(1, argTypes.size()),
                  null,
                  null,
                  null,
                  span,
                  mr.name(),
                  false);
        }
        if (st != null && un != null) {
          a.error(
              Code.AMBIGUOUS_CALL,
              span,
              "method reference "
                  + site.display()
                  + "::"
                  + mr.name()
                  + " is ambiguous (both a static and an instance method apply)");
          return new BExpr.Error(fi, span);
        }
        if (st != null) {
          Type ret = Types.subst(st.method.returnType(), st.solution);
          checkRefReturn(ret, ft, span);
          return new BExpr.MethodRef(fi, sam, st.method, BExpr.RefKind.STATIC, null, ret, span);
        }
        if (un != null) {
          Type recvType = ft.params().getFirst();
          Type ret =
              Types.subst(
                  Types.subst(
                      un.method.returnType(),
                      a.overloads.siteSubst(a.members.captureSite(recvType), un.method)),
                  un.solution);
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
        a.members.checkReceiverNullness(recv, mr.target().span());
        Type site = a.members.captureSite(recv.type());
        List<MethodSymbol> all = a.lookup.findMethods(site, mr.name());
        Overloads.Selected sel =
            all.isEmpty()
                ? null
                : a.overloads.select(all, site, argTypes, null, null, null, span, mr.name(), false);
        if (sel == null) {
          reportRefMismatch(recv.type(), mr.name(), all, ft, span);
          return new BExpr.Error(fi, span);
        }
        Type ret =
            types()
                .uncapture(
                    Types.subst(
                        Types.subst(
                            sel.method.returnType(), a.overloads.siteSubst(site, sel.method)),
                        sel.solution));
        checkRefReturn(ret, ft, span);
        if (sel.method.isStatic()) {
          return new BExpr.MethodRef(fi, sam, sel.method, BExpr.RefKind.STATIC, null, ret, span);
        }
        return new BExpr.MethodRef(fi, sam, sel.method, BExpr.RefKind.BOUND, recv, ret, span);
      }
      case Attr.SuperTarget s -> {
        a.error(
            Code.UNSUPPORTED_FEATURE,
            span,
            "'super::method' references are not supported; use a lambda");
        return new BExpr.Error(fi, span);
      }
      case Attr.PackageTarget p -> {
        a.error(Code.UNRESOLVED_NAME, span, "'" + p.name() + "' is a package");
        return new BExpr.Error(fi, span);
      }
    }
  }

  /**
   * A function used by its bare name as a value ({@code xs.select(twice)}, D083): a method of an
   * enclosing class (bound to {@code this} when it is an instance method) or a top-level function.
   */
  private BExpr functionRef(
      Expr.MethodRef mr,
      ClassType fi,
      Types.MethodType ft,
      MethodSymbol sam,
      List<ArgInfo> argTypes) {
    Span span = mr.span();
    FunctionGroup g = functionGroup(mr.name());
    if (g == null) {
      a.reportUnresolvedName(mr.name(), mr.nameSpan());
      return new BExpr.Error(fi, span);
    }
    Type site = g.owner() == null ? null : g.owner().thisType();
    Overloads.Selected sel =
        a.overloads.select(g.methods(), site, argTypes, null, null, null, span, mr.name(), false);
    if (sel == null) {
      reportFunctionMismatch(mr.name(), ft, span);
      return new BExpr.Error(fi, span);
    }
    Type ret = sel.method.returnType();
    if (site != null) {
      ret = Types.subst(ret, a.overloads.siteSubst(site, sel.method));
    }
    ret = types().uncapture(Types.subst(ret, sel.solution));
    checkRefReturn(ret, ft, span);
    if (sel.method.isStatic()) {
      return new BExpr.MethodRef(fi, sam, sel.method, BExpr.RefKind.STATIC, null, ret, span);
    }
    if (g.staticOnly()) {
      a.error(
          Code.STATIC_CONTEXT,
          mr.nameSpan(),
          "instance method '" + mr.name() + "' cannot be used from a static context");
      return new BExpr.Error(fi, span);
    }
    BExpr recv;
    if (g.current()) {
      recv = a.thisValue(span);
    } else {
      a.noteThisUse();
      recv = new BExpr.OuterThis(g.owner(), g.owner().thisType(), span);
    }
    return new BExpr.MethodRef(fi, sam, sel.method, BExpr.RefKind.BOUND, recv, ret, span);
  }

  private void reportFunctionMismatch(String name, Types.MethodType ft, Span span) {
    StringBuilder sb = new StringBuilder("(");
    for (int i = 0; i < ft.params().size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(ft.params().get(i).display());
    }
    sb.append(')');
    a.error(
        Code.LAMBDA_MISMATCH,
        span,
        "no function '" + name + "' is compatible with " + sb + " -> " + ft.ret().display());
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
      a.error(
          Code.LAMBDA_MISMATCH,
          span,
          "the referenced method returns void, but a value of type "
              + ft.ret().display()
              + " is expected");
      return;
    }
    if (types().assignConversion(ret, ft.ret(), null) == Types.Conv.NONE) {
      a.error(
          Code.LAMBDA_MISMATCH,
          span,
          "the referenced method returns "
              + ret.display()
              + ", but "
              + ft.ret().display()
              + " is expected");
    }
  }

  private void reportRefMismatch(
      Type site, String name, List<MethodSymbol> all, Types.MethodType ft, Span span) {
    if (all.isEmpty()) {
      a.members.reportNoMember(site, name, span);
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
    a.error(
        Code.LAMBDA_MISMATCH,
        span,
        "no method "
            + site.display()
            + "::"
            + name
            + " is compatible with "
            + sb
            + " -> "
            + ft.ret().display());
  }

  /** Result type of a method reference given parameter types (for inference), or null. */
  Type methodRefResultType(Expr.MethodRef mr, List<Type> ps) {
    Attr.Speculation<Type> s =
        a.speculate(
            () -> {
              List<ArgInfo> argTypes = new ArrayList<>();
              for (Type p : ps) {
                argTypes.add(ArgInfo.ofType(p, mr.span()));
              }
              if (mr.target() == null && mr.typeTarget() == null) {
                FunctionGroup g = functionGroup(mr.name());
                if (g == null) {
                  return null;
                }
                Type site = g.owner() == null ? null : g.owner().thisType();
                Overloads.Selected sel =
                    a.overloads.select(
                        g.methods(), site, argTypes, null, null, null, mr.span(), mr.name(), false);
                if (sel == null) {
                  return null;
                }
                Type ret = sel.method.returnType();
                if (site != null) {
                  ret = Types.subst(ret, a.overloads.siteSubst(site, sel.method));
                }
                return Types.subst(ret, sel.solution);
              }
              if (mr.name().equals("new")) {
                Type t =
                    mr.typeTarget() != null
                        ? a.resolveType(mr.typeTarget())
                        : typeOfTarget(mr.target());
                if (t instanceof ClassType ct
                    && !ct.sym().typeParams().isEmpty()
                    && ct.args().isEmpty()) {
                  Overloads.Selected sel =
                      selectCtor(
                          ct.sym().methods(MethodSymbol.CONSTRUCTOR),
                          ct.sym().thisType(),
                          argTypes,
                          ct.sym().typeParams(),
                          null,
                          mr.span(),
                          ct.sym().name());
                  return sel == null ? null : Types.subst(ct.sym().thisType(), sel.solution);
                }
                return t;
              }
              Attr.Target target = a.target(mr.target(), true);
              if (target instanceof Attr.TypeTarget tt) {
                for (MethodSymbol m : a.lookup.findMethods(tt.type(), mr.name())) {
                  if (m.isStatic() && m.params().size() == ps.size()) {
                    Overloads.Selected sel =
                        a.overloads.select(
                            List.of(m),
                            tt.type(),
                            argTypes,
                            null,
                            null,
                            null,
                            mr.span(),
                            mr.name(),
                            false);
                    if (sel != null) {
                      return Types.subst(m.returnType(), sel.solution);
                    }
                  } else if (!m.isStatic() && m.params().size() == ps.size() - 1 && !ps.isEmpty()) {
                    Type recvType = types().boxIfPrimitive(ps.getFirst());
                    Overloads.Selected sel =
                        a.overloads.select(
                            List.of(m),
                            a.members.captureSite(recvType),
                            argTypes.subList(1, argTypes.size()),
                            null,
                            null,
                            null,
                            mr.span(),
                            mr.name(),
                            false);
                    if (sel != null) {
                      return types()
                          .uncapture(
                              Types.subst(
                                  Types.subst(
                                      m.returnType(),
                                      a.overloads.siteSubst(a.members.captureSite(recvType), m)),
                                  sel.solution));
                    }
                  }
                }
                return null;
              }
              if (target instanceof Attr.ValueTarget v) {
                Type site = a.members.captureSite(types().boxIfPrimitive(v.expr().type()));
                List<MethodSymbol> all = a.lookup.findMethods(site, mr.name());
                Overloads.Selected sel =
                    all.isEmpty()
                        ? null
                        : a.overloads.select(
                            all, site, argTypes, null, null, null, mr.span(), mr.name(), false);
                return sel == null
                    ? null
                    : types()
                        .uncapture(
                            Types.subst(
                                Types.subst(
                                    sel.method.returnType(),
                                    a.overloads.siteSubst(site, sel.method)),
                                sel.solution));
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

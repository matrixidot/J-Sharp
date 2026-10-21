package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.bound.BStmt;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.resolve.Suggestions;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.PropertySymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symbol;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import io.github.matrixidot.jsharp.compiler.types.Types;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Member access: fields, properties (and JavaBeans getters), static members of types, {@code super}
 * members, safe access ({@code ?.}) chains, access control and deprecation checks, and the "no such
 * member" diagnostics.
 */
final class Members {
  private final Attr a;

  Members(Attr a) {
    this.a = a;
  }

  /** The type of a member declared in {@code owner}, as seen from {@code site}. */
  Type memberType(Type site, ClassSymbol owner, Type declared) {
    if (site == null || declared == null) {
      return declared;
    }
    ClassType as = a.types.asSuper(site, owner);
    if (as == null) {
      return declared;
    }
    if (as.isRaw()) {
      return declared.erasure().withNullness(Nullness.PLATFORM);
    }
    return Types.subst(declared, a.types.typeArgMap(as));
  }

  /** Captures wildcard type arguments of a receiver type. */
  Type captureSite(Type t) {
    return t instanceof ClassType ct ? a.types.capture(ct) : t;
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
    Attr.Speculation<BExpr> s =
        a.speculate(
            () -> {
              Env saved = a.env;
              a.env = ClassChecker.fieldEnv(a, f);
              try {
                return f.type() == null
                    ? a.value(f.declarator().init(), null)
                    : a.exprCoerced(f.declarator().init(), f.type());
              } finally {
                a.env = saved;
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
    Type t = a.types.uncapture(memberType(captureSite(site), f.owner(), a.ensureFieldType(f)));
    if (f.constantValue() != null && f.isStatic() && f.has(Flags.FINAL)) {
      Object v = f.constantValue();
      Type ct = t instanceof PrimType ? t : a.syms.stringType();
      if (v instanceof Integer i && t instanceof PrimType p && p == PrimType.CHAR) {
        v = (char) i.intValue();
      }
      return new BExpr.Const(v, ct, span);
    }
    return new BExpr.Field(f.isStatic() ? null : recv, f, t, span);
  }

  BExpr propertyGet(BExpr recv, Type site, PropertySymbol p, Span span) {
    if (p.owner().isRecord()
        && p.owner() == a.env.cls
        && !p.isStatic()
        && recv instanceof BExpr.This
        && p.backingField() != null) {
      // Inside a record, a component reads its field (Java rule); this is what lets an explicit
      // accessor `double c() => round(c)` use the stored value instead of recursing.
      return fieldGet(recv, site, p.backingField(), span);
    }
    MethodSymbol getter = p.getter();
    if (getter == null) {
      a.error(Code.UNRESOLVED_MEMBER, span, "property '" + p.name() + "' has no getter");
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    checkAccess(getter, p.owner(), recv == null ? null : site, span);
    Type t =
        a.types.uncapture(memberType(captureSite(site), p.owner(), ensureReturnTypeOf(getter, p)));
    return new BExpr.Call(
        p.isStatic() ? null : recv, getter, List.of(), callKind(getter, recv, false), t, span);
  }

  BExpr beanGet(BExpr recv, Type site, MethodSymbol getter, Span span) {
    checkAccess(getter, getter.owner(), recv == null ? null : site, span);
    checkDeprecated(getter, span);
    Type t = a.types.uncapture(memberType(captureSite(site), getter.owner(), getter.returnType()));
    return new BExpr.Call(
        getter.isStatic() ? null : recv, getter, List.of(), callKind(getter, recv, false), t, span);
  }

  Type ensureReturnTypeOf(MethodSymbol getter, PropertySymbol p) {
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
        && !a.lookup.isAccessible(f, f.owner(), site, a.env.cls)
        && a.lookup.isAccessible(accessor, accessor.owner(), site, a.env.cls);
  }

  void checkAccess(Symbol member, ClassSymbol owner, Type site, Span span) {
    if (!a.lookup.isAccessible(member, owner, site, a.env.cls)) {
      a.error(
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
    if (s.has(Flags.DEPRECATED) && !a.isSpeculative()) {
      a.warn(Code.DEPRECATED, span, s.kindName() + " '" + s.name() + "' is deprecated");
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
        a.error(Code.VOID_VALUE, recv.span(), "a void expression has no members");
        return new BExpr.Error(Type.ErrorType.INSTANCE, span);
      }
      recv = a.coerce(recv, a.syms.boxed(p), recv.span());
      site = recv.type();
    }
    checkReceiverNullness(recv, nameSpan);
    if (site instanceof Type.ArrayType && name.equals("length")) {
      return new BExpr.ArrayLength(recv, PrimType.INT, span);
    }
    if (site instanceof Type.TupleType tt) {
      BExpr t = a.patterns.tupleElement(recv, tt, name, nameSpan, span);
      if (t != null) {
        return t;
      }
    }
    PropertySymbol p = a.lookup.findProperty(site, name);
    if (p != null) {
      return propertyGet(recv, site, p, span);
    }
    FieldSymbol f = a.lookup.findField(site, name);
    if (f != null && !hiddenByAccessor(f, site, a.lookup.findGetter(site, name))) {
      return fieldGet(recv, site, f, span);
    }
    MethodSymbol acc = a.lookup.findRecordAccessor(site, name);
    if (acc != null) {
      return beanGet(recv, site, acc, span);
    }
    MethodSymbol getter = a.lookup.findGetter(site, name);
    if (getter != null) {
      return beanGet(recv, site, getter, span);
    }
    reportNoMember(site, name, nameSpan);
    return new BExpr.Error(Type.ErrorType.INSTANCE, span);
  }

  void reportNoMember(Type site, String name, Span span) {
    Diagnostic.Builder d =
        a.err(
            Code.UNRESOLVED_MEMBER,
            span,
            site.withNullness(Nullness.NON_NULL).display() + " has no member '" + name + "'");
    String guess = Suggestions.closest(name, a.lookup.memberNames(site));
    if (guess != null) {
      d.help("did you mean '" + guess + "'?");
    }
    a.report(d);
  }

  /** Member access on a nullable receiver requires {@code ?.} or a check. */
  void checkReceiverNullness(BExpr recv, Span at) {
    Type t = recv.type();
    if (t instanceof Type.NullType) {
      a.error(Code.NULLABLE_RECEIVER, at, "member access on 'null'");
      return;
    }
    if (t.isReference() && t.nullness() == Nullness.NULLABLE) {
      if (recv instanceof BExpr.Local l && l.var().isAtomic()) {
        String n = l.var().name();
        a.report(
            a.err(Code.NULLABLE_RECEIVER, at, "value of type " + t.display() + " may be null")
                .note("'" + n + "' is atomic, so another thread may change it after a null check")
                .help("check a copy: 'val current = " + n + "; if (current != null) ...'"));
        return;
      }
      a.report(
          a.err(Code.NULLABLE_RECEIVER, at, "value of type " + t.display() + " may be null")
              .help(
                  "use '?.' for a null-safe access, '!' to assert non-null, or check for null first"));
    } else if (t.isReference()
        && t.nullness() == Nullness.PLATFORM
        && a.ctx.options.strictPlatformNullness()
        && !a.isSpeculative()) {
      a.warn(
          Code.PLATFORM_NULLNESS,
          at,
          "value of Java type " + t.display() + " has unknown nullness");
    }
  }

  /** Static member {@code Type.name}: field, enum constant, static property. */
  BExpr staticMember(Attr.TypeTarget tt, Expr.Member m) {
    BExpr v = staticMemberValue(tt, m);
    if (v != null) {
      return v;
    }
    Type site = tt.type();
    if (a.lookup.findProperty(site, m.name()) != null
        || a.lookup.findField(site, m.name()) != null) {
      a.error(
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

  BExpr staticMemberValue(Attr.TypeTarget tt, Expr.Member m) {
    Type site = tt.type();
    if (!(site instanceof ClassType)) {
      return null;
    }
    PropertySymbol p = a.lookup.findProperty(site, m.name());
    if (p != null && p.isStatic()) {
      return propertyGet(null, site, p, m.span());
    }
    FieldSymbol f = a.lookup.findField(site, m.name());
    if (f != null && f.isStatic()) {
      return fieldGet(null, site, f, m.span());
    }
    return null;
  }

  BExpr superMember(Attr.SuperTarget s, Expr.Member m) {
    BExpr recv = new BExpr.This(s.superType(), s.span());
    PropertySymbol p = a.lookup.findProperty(s.superType(), m.name());
    if (p != null && p.getter() != null) {
      Type t = memberType(s.superType(), p.owner(), p.type());
      if (p.getter().isAbstract()) {
        a.error(
            Code.INVALID_THIS,
            m.span(),
            "cannot access abstract property '" + m.name() + "' through super");
      }
      return new BExpr.Call(recv, p.getter(), List.of(), BExpr.CallKind.SPECIAL, t, m.span());
    }
    FieldSymbol f = a.lookup.findField(s.superType(), m.name());
    if (f != null) {
      return fieldGet(recv, s.superType(), f, m.span());
    }
    MethodSymbol g = a.lookup.findGetter(s.superType(), m.name());
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

  BExpr memberExpr(Expr.Member m) {
    if (m.nullSafe()) {
      return safeAccess(m.target(), m.span(), tmp -> member(tmp, m.name(), m.nameSpan(), m.span()));
    }
    return a.asValue(a.target(m, false), m.span());
  }

  /**
   * A switch arm block {@code { ...; yield v; }} (D092): checked like a nested body with its own
   * jump targets (no break/continue out of it), whose value is the common type of its yields (or
   * the expected type).
   */
  BExpr blockValue(Expr.BlockExpr b, Type pt) {
    Env outer = a.env;
    Env e = outer.nested();
    e.lambda = outer.lambda;
    e.jumps = new ArrayDeque<>();
    e.yieldTo = new Env.YieldContext(pt == null || pt.isError() || pt == PrimType.VOID ? null : pt);
    a.env = e;
    BStmt body;
    try {
      body = a.stmts.block(b.block());
      if (a.env.flow.alive) {
        a.report(
            a.err(Code.MISSING_RETURN, b.span(), "this switch arm block can end without a value")
                .help("end every path with 'yield value;' (or 'throw')"));
      }
    } finally {
      a.env = outer;
    }
    Env.YieldContext y = e.yieldTo;
    Type type =
        y.expected != null
            ? y.expected
            : y.yielded.isEmpty() ? Type.NeverType.INSTANCE : a.types.lub(y.yielded);
    return new BExpr.BlockValue(y.target, body, type, b.span());
  }

  private int nullConditionals;

  /**
   * C# null-conditional chains (D091): in {@code a?.b.c()} a null {@code a} makes the whole chain
   * null, and {@code .b} and {@code .c()} apply to the non-null value. The chain is rewritten as
   * {@code a?.<tmp.b.c()>} with {@code tmp} bound to {@code a}. Null if {@code e}'s chain has no
   * inner {@code ?.}/{@code ?[}.
   */
  BExpr nullConditional(Expr e) {
    Expr inner = innerNullSafe(e);
    if (inner == null) {
      return null;
    }
    String name = "$nc" + nullConditionals++;
    Expr receiver;
    Expr replacement;
    switch (inner) {
      case Expr.Member m -> {
        receiver = m.target();
        replacement =
            new Expr.Member(
                new Expr.Name(name, List.of(), m.target().span()),
                m.name(),
                m.nameSpan(),
                m.typeArgs(),
                false,
                m.span());
      }
      case Expr.Index i -> {
        receiver = i.target();
        replacement =
            new Expr.Index(
                new Expr.Name(name, List.of(), i.target().span()), i.index(), false, i.span());
      }
      default -> {
        return null;
      }
    }
    Expr rest = replaceInChain(e, inner, replacement);
    return safeAccess(
        receiver,
        e.span(),
        tmp -> {
          Scope saved = a.env.scope;
          a.env.scope = saved.child();
          a.env.scope.vars.put(name, Attr.localOf(tmp));
          try {
            return a.value(rest, null);
          } finally {
            a.env.scope = saved;
          }
        });
  }

  /** The leftmost {@code ?.}/{@code ?[} below {@code e} in its member/call/index chain. */
  static Expr innerNullSafe(Expr e) {
    Expr found = null;
    for (Expr cur = chainReceiver(e); cur != null; cur = chainReceiver(cur)) {
      if (cur instanceof Expr.Member m && m.nullSafe()
          || cur instanceof Expr.Index i && i.nullSafe()) {
        found = cur; // keep going: the leftmost one is lifted first
      }
    }
    return found;
  }

  /** The next link of a postfix chain (parentheses end it). */
  static Expr chainReceiver(Expr e) {
    return switch (e) {
      case Expr.Member m -> m.target();
      case Expr.Call c -> c.callee() instanceof Expr.Member ? c.callee() : null;
      case Expr.Index i -> i.target();
      default -> null;
    };
  }

  /** {@code e} with the chain link {@code old} replaced by {@code replacement}. */
  static Expr replaceInChain(Expr e, Expr old, Expr replacement) {
    if (e == old) {
      return replacement;
    }
    return switch (e) {
      case Expr.Member m ->
          new Expr.Member(
              replaceInChain(m.target(), old, replacement),
              m.name(),
              m.nameSpan(),
              m.typeArgs(),
              m.nullSafe(),
              m.span());
      case Expr.Call c ->
          new Expr.Call(replaceInChain(c.callee(), old, replacement), c.args(), c.span());
      case Expr.Index i ->
          new Expr.Index(
              replaceInChain(i.target(), old, replacement), i.index(), i.nullSafe(), i.span());
      default -> e;
    };
  }

  /**
   * {@code recv?.rest}: evaluates the receiver once; null if it is null, else {@code rest} applied
   * to the non-null receiver.
   */
  BExpr safeAccess(Expr receiverExpr, Span span, Function<BExpr, BExpr> rest) {
    BExpr recv = a.value(receiverExpr, null);
    if (recv.type().isError()) {
      return recv;
    }
    if (recv.type() instanceof PrimType) {
      a.error(
          Code.BAD_OPERANDS,
          span,
          "'?.' cannot be applied to primitive type " + recv.type().display());
      return rest.apply(recv);
    }
    if (recv.type().nullness() == Nullness.NON_NULL
        && !(recv.type() instanceof Type.NullType)
        && !a.isSpeculative()) {
      // Allowed but pointless; behaves like '.'.
      a.warn(
          Code.REDUNDANT_NON_NULL_ASSERTION,
          span,
          "'?.' on a non-null value of type "
              + recv.type().display()
              + " always takes the non-null path");
    }
    VarSymbol tmp =
        a.env.newVar(
            "$safe",
            recv.type().withNullness(Nullness.NON_NULL),
            Flags.FINAL | Flags.SYNTHETIC,
            VarSymbol.Kind.LOCAL,
            span);
    a.env.flow.assigned.set(tmp.id());
    BExpr whenPresent = rest.apply(new BExpr.Local(tmp, span));
    Type t = whenPresent.type();
    Type result;
    if (t == PrimType.VOID) {
      result = PrimType.VOID;
    } else if (t instanceof PrimType p) {
      whenPresent = a.coerce(whenPresent, a.syms.boxed(p), span);
      result = a.syms.boxed(p).withNullness(Nullness.NULLABLE);
    } else {
      result = t.withNullness(Nullness.NULLABLE);
    }
    return new BExpr.SafeAccess(recv, tmp, whenPresent, result, span);
  }
}

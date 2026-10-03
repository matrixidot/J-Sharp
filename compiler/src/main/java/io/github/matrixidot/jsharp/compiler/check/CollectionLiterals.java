package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.ast.Expr;
import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ArrayType;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import io.github.matrixidot.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collection literals ({@code [a, ..xs]}) and map literals ({@code {k: v}}), typed by their target
 * (D080): arrays, immutable lists/sets/maps (the default; insertion-ordered), or a concrete mutable
 * collection class that is created and filled. Without a usable target, a list (or map) of the
 * elements' common type.
 */
final class CollectionLiterals {
  private final Attr a;

  CollectionLiterals(Attr a) {
    this.a = a;
  }

  private Types types() {
    return a.types;
  }

  private enum Kind {
    LIST,
    SET,
    ARRAY,
    FILL,
    MAP,
    FILL_MAP
  }

  private ClassSymbol cls(String binaryName) {
    return a.syms.lookup(binaryName);
  }

  private boolean isClass(ClassType t, String binaryName) {
    return t.sym().binaryName().equals(binaryName);
  }

  /** The element type argument of {@code t} viewed as {@code superName<E>}, or null. */
  private Type typeArgOf(Type t, String superName, int index) {
    ClassSymbol sup = cls(superName);
    if (sup == null || !(types().boxIfPrimitive(t) instanceof ClassType ct)) {
      return null;
    }
    ClassType as = types().asSuper(ct, sup);
    if (as == null || as.args().size() <= index) {
      return null;
    }
    Type arg = as.args().get(index);
    if (arg instanceof Type.WildcardType w) {
      return w.kind() == Type.WildcardType.Kind.EXTENDS ? w.bound() : null;
    }
    return arg instanceof Type.TypeVar tv && tv.sym().owner() == null ? null : arg;
  }

  private boolean hasNoArgConstructor(ClassType ct) {
    if (ct.sym().isAbstract() || ct.sym().isInterface()) {
      return false;
    }
    for (MethodSymbol m : ct.sym().methods(MethodSymbol.CONSTRUCTOR)) {
      if (m.params().isEmpty() && m.has(Flags.PUBLIC)) {
        return true;
      }
    }
    return false;
  }

  private MethodSymbol noArgConstructor(ClassType ct) {
    for (MethodSymbol m : ct.sym().methods(MethodSymbol.CONSTRUCTOR)) {
      if (m.params().isEmpty()) {
        return m;
      }
    }
    throw new IllegalStateException("no-arg constructor of " + ct.display());
  }

  // ------------------------------------------------------------------ [ ... ]

  BExpr collection(Expr.CollectionLiteral lit, Type pt) {
    Span span = lit.span();
    Type target = pt == null || pt.isError() || pt == PrimType.VOID ? null : pt;
    Kind kind;
    Type elem = null;
    if (target instanceof ArrayType at) {
      kind = Kind.ARRAY;
      elem = at.elem();
    } else if (target == null
        || target instanceof Type.TypeVar
        || target instanceof ClassType ct && isClass(ct, "java/lang/Object")) {
      kind = Kind.LIST;
    } else if (target instanceof ClassType ct) {
      String n = ct.sym().binaryName();
      if (n.equals("java/util/Set") || n.equals("java/util/SequencedSet")) {
        kind = Kind.SET;
        elem = typeArgOf(ct, n, 0);
      } else if (n.equals("java/util/List")
          || n.equals("java/util/Collection")
          || n.equals("java/lang/Iterable")
          || n.equals("java/util/SequencedCollection")) {
        kind = Kind.LIST;
        elem = typeArgOf(ct, n, 0);
      } else if (cls("java/util/Collection") != null
          && types().isSubtype(ct.erasure(), cls("java/util/Collection").thisType().erasure())
          && hasNoArgConstructor(ct)) {
        kind = Kind.FILL;
        elem = typeArgOf(ct, "java/util/Collection", 0);
      } else if (cls("java/util/Map") != null
          && types().isSubtype(ct.erasure(), cls("java/util/Map").thisType().erasure())) {
        a.report(
            a.err(Code.INVALID_LITERAL, span, "a map is written {key: value, ...}")
                .help("use '{k1: v1, k2: v2}' instead of '[...]'"));
        return error(lit);
      } else {
        a.error(
            Code.INVALID_LITERAL,
            span,
            "a collection literal cannot be a "
                + ct.display()
                + " (it can be an array, a List, a Set, or a collection class with a public no-argument constructor)");
        return error(lit);
      }
    } else {
      a.error(Code.INVALID_LITERAL, span, "a collection literal cannot be a " + target.display());
      return error(lit);
    }
    if (kind == Kind.ARRAY) {
      return array(lit, (ArrayType) target);
    }
    List<BExpr> parts = new ArrayList<>();
    if (elem == null) {
      // Infer the element type from the elements.
      List<BExpr> raw = new ArrayList<>();
      List<Type> elemTypes = new ArrayList<>();
      boolean failed = false;
      for (Expr e : lit.elements()) {
        if (e instanceof Expr.Spread sp) {
          BExpr src = a.value(sp.expr(), null);
          raw.add(src);
          Type st = spreadElementType(src, sp.span());
          if (st != null) {
            elemTypes.add(st);
          } else {
            failed = true;
          }
        } else {
          BExpr b = a.value(e, null);
          raw.add(b);
          elemTypes.add(b.type());
        }
      }
      if (elemTypes.isEmpty() && failed) {
        return new BExpr.Error(Type.ErrorType.INSTANCE, span); // already reported
      }
      if (elemTypes.isEmpty()) {
        a.report(
            a.err(
                    Code.INVALID_LITERAL,
                    span,
                    "cannot infer the element type of an empty collection")
                .help("give the target a type, e.g. 'List<String> xs = [];'"));
        return error(lit);
      }
      Type common = types().lub(elemTypes);
      elem = types().boxIfPrimitive(common);
      if (elem.isError()) {
        return error(lit);
      }
      for (int i = 0; i < raw.size(); i++) {
        Expr e = lit.elements().get(i);
        BExpr b = raw.get(i);
        parts.add(
            e instanceof Expr.Spread sp
                ? spread(b, sp.span())
                : toElement(b, common, elem, e.span()));
      }
    } else {
      elem = types().boxIfPrimitive(elem);
      for (Expr e : lit.elements()) {
        if (e instanceof Expr.Spread sp) {
          BExpr src = a.value(sp.expr(), null);
          Type st = spreadElementType(src, sp.span());
          if (st != null
              && !st.isError()
              && !types()
                  .isSubtype(
                      types().boxIfPrimitive(st).withNullness(Nullness.NON_NULL),
                      elem.withNullness(Nullness.NON_NULL))) {
            a.error(
                Code.TYPE_MISMATCH,
                sp.span(),
                "cannot spread elements of type "
                    + st.display()
                    + " into a collection of "
                    + elem.display());
          }
          parts.add(spread(src, sp.span()));
        } else {
          parts.add(a.exprCoerced(e, elem, e.span()));
        }
      }
    }
    BExpr items = objectArray(parts, span);
    ClassType collType;
    return switch (kind) {
      case LIST -> {
        collType = new ClassType(cls("java/util/List"), List.of(elem), Nullness.NON_NULL);
        yield helper("list", collType, span, items);
      }
      case SET -> {
        collType = new ClassType(cls("java/util/Set"), List.of(elem), Nullness.NON_NULL);
        yield helper("set", collType, span, items);
      }
      case FILL -> {
        ClassType ct = (ClassType) target;
        BExpr created =
            new BExpr.New(
                (ClassType) ct.withNullness(Nullness.NON_NULL),
                noArgConstructor(ct),
                List.of(),
                span);
        yield helper("fill", ct.withNullness(Nullness.NON_NULL), span, created, items);
      }
      default -> throw new IllegalStateException(kind.toString());
    };
  }

  private BExpr array(Expr.CollectionLiteral lit, ArrayType at) {
    List<BExpr> elems = new ArrayList<>();
    for (Expr e : lit.elements()) {
      if (e instanceof Expr.Spread sp) {
        a.report(
            a.err(Code.INVALID_LITERAL, sp.span(), "'..' spreads cannot build an array")
                .help("build a list instead, e.g. 'List<T> xs = [..a, ..b];'"));
        a.value(sp.expr(), null);
        continue;
      }
      elems.add(a.exprCoerced(e, at.elem(), e.span()));
    }
    return new BExpr.NewArray(
        new ArrayType(at.elem(), Nullness.NON_NULL), List.of(), elems, lit.span());
  }

  /** Element type of a spread source: an {@code Iterable<E>} or an array. */
  private Type spreadElementType(BExpr src, Span span) {
    Type t = src.type();
    if (t.isError()) {
      return null;
    }
    if (t instanceof ArrayType at) {
      return types().boxIfPrimitive(at.elem());
    }
    Type e = typeArgOf(t, "java/lang/Iterable", 0);
    if (e == null
        && types().boxIfPrimitive(t) instanceof ClassType ct
        && cls("java/lang/Iterable") != null
        && types().isSubtype(ct.erasure(), cls("java/lang/Iterable").thisType().erasure())) {
      return a.syms.objectType();
    }
    if (e == null) {
      a.error(
          Code.INVALID_LITERAL, span, "'..' needs an Iterable or an array, found " + t.display());
    }
    return e;
  }

  /** {@code Literals.spread(src)}: the overload for a primitive array, Object[] or Iterable. */
  private BExpr spread(BExpr src, Span span) {
    if (src.type().isError()) {
      return src;
    }
    ClassSymbol lits = cls("jsharp/core/Literals");
    Type st = src.type();
    for (MethodSymbol m : lits.methods("spread")) {
      Type p = m.params().getFirst().type();
      boolean fits =
          st instanceof ArrayType sa
              ? p instanceof ArrayType pa
                  && (sa.elem() instanceof PrimType sp
                      ? pa.elem() == sp
                      : !(pa.elem() instanceof PrimType))
              : p instanceof ClassType pc && isClass(pc, "java/lang/Iterable");
      if (fits) {
        return new BExpr.Call(
            null, m, List.of(src), BExpr.CallKind.STATIC, a.syms.objectType(), span);
      }
    }
    return error(src.span()); // spreadElementType already reported it
  }

  // ------------------------------------------------------------------ { k: v }

  BExpr map(Expr.MapLiteral lit, Type pt) {
    Span span = lit.span();
    Type target = pt == null || pt.isError() || pt == PrimType.VOID ? null : pt;
    Kind kind;
    Type keyType = null;
    Type valueType = null;
    ClassSymbol mapSym = cls("java/util/Map");
    if (target == null
        || target instanceof Type.TypeVar
        || target instanceof ClassType o && isClass(o, "java/lang/Object")) {
      kind = Kind.MAP;
    } else if (target instanceof ClassType ct
        && (isClass(ct, "java/util/Map") || isClass(ct, "java/util/SequencedMap"))) {
      kind = Kind.MAP;
      keyType = typeArgOf(ct, "java/util/Map", 0);
      valueType = typeArgOf(ct, "java/util/Map", 1);
    } else if (target instanceof ClassType ct
        && types().isSubtype(ct.erasure(), mapSym.thisType().erasure())
        && hasNoArgConstructor(ct)) {
      kind = Kind.FILL_MAP;
      keyType = typeArgOf(ct, "java/util/Map", 0);
      valueType = typeArgOf(ct, "java/util/Map", 1);
    } else {
      a.error(Code.INVALID_LITERAL, span, "a map literal cannot be a " + target.display());
      return error(lit);
    }
    List<BExpr> keys = new ArrayList<>();
    List<BExpr> values = new ArrayList<>();
    if (keyType == null || valueType == null) {
      List<Type> kts = new ArrayList<>();
      List<Type> vts = new ArrayList<>();
      for (Expr.MapEntry en : lit.entries()) {
        BExpr k =
            keyType == null
                ? a.value(en.key(), null)
                : a.exprCoerced(en.key(), types().boxIfPrimitive(keyType), en.key().span());
        BExpr v =
            valueType == null
                ? a.value(en.value(), null)
                : a.exprCoerced(en.value(), types().boxIfPrimitive(valueType), en.value().span());
        keys.add(k);
        values.add(v);
        kts.add(k.type());
        vts.add(v.type());
      }
      if (lit.entries().isEmpty()) {
        a.report(
            a.err(Code.INVALID_LITERAL, span, "cannot infer the types of an empty map")
                .help("give the target a type, e.g. 'Map<String, int> m = {};'"));
        return error(lit);
      }
      Type kc = keyType == null ? types().lub(kts) : keyType;
      Type vc = valueType == null ? types().lub(vts) : valueType;
      keyType = types().boxIfPrimitive(kc);
      valueType = types().boxIfPrimitive(vc);
      for (int i = 0; i < keys.size(); i++) {
        keys.set(i, toElement(keys.get(i), kc, keyType, lit.entries().get(i).key().span()));
        values.set(i, toElement(values.get(i), vc, valueType, lit.entries().get(i).value().span()));
      }
    } else {
      keyType = types().boxIfPrimitive(keyType);
      valueType = types().boxIfPrimitive(valueType);
      for (Expr.MapEntry en : lit.entries()) {
        keys.add(a.exprCoerced(en.key(), keyType, en.key().span()));
        values.add(a.exprCoerced(en.value(), valueType, en.value().span()));
      }
    }
    // Repeated constant keys are certainly a mistake.
    Set<Object> seen = new HashSet<>();
    for (int i = 0; i < keys.size(); i++) {
      Object k = ConstFold.valueOf(keys.get(i));
      if (k != null && !seen.add(k)) {
        a.error(
            Code.INVALID_LITERAL,
            lit.entries().get(i).key().span(),
            "duplicate key " + k + " in map literal");
      }
    }
    List<BExpr> kv = new ArrayList<>();
    for (int i = 0; i < keys.size(); i++) {
      kv.add(keys.get(i));
      kv.add(values.get(i));
    }
    BExpr items = objectArray(kv, span);
    if (kind == Kind.FILL_MAP) {
      ClassType ct = (ClassType) target.withNullness(Nullness.NON_NULL);
      BExpr created = new BExpr.New(ct, noArgConstructor(ct), List.of(), span);
      return helper("fillMap", ct, span, created, items);
    }
    ClassType mt = new ClassType(mapSym, List.of(keyType, valueType), Nullness.NON_NULL);
    return helper("map", mt, span, items);
  }

  // ------------------------------------------------------------------ helpers

  /** Converts an element to the common type: numeric promotion first, then boxing ([1.5, 2]). */
  private BExpr toElement(BExpr b, Type common, Type boxed, Span span) {
    if (common instanceof PrimType p && b.type() instanceof PrimType) {
      b = a.coerce(b, p, span);
    }
    return a.coerce(b, boxed, span);
  }

  private BExpr objectArray(List<BExpr> elems, Span span) {
    return new BExpr.NewArray(
        new ArrayType(a.syms.objectType().withNullness(Nullness.NULLABLE), Nullness.NON_NULL),
        List.of(),
        elems,
        span);
  }

  /** A call of {@code jsharp.core.Literals.name(args)} typed as {@code result}. */
  private BExpr helper(String name, Type result, Span span, BExpr... args) {
    ClassSymbol lits = cls("jsharp/core/Literals");
    if (lits == null) {
      a.error(
          Code.INVALID_LITERAL, span, "collection literals need the J# runtime on the class path");
      return new BExpr.Error(Type.ErrorType.INSTANCE, span);
    }
    for (MethodSymbol m : lits.methods(name)) {
      if (m.params().size() == args.length) {
        return new BExpr.Call(
            null,
            m,
            List.of(args),
            BExpr.CallKind.STATIC,
            result.withNullness(Nullness.NON_NULL),
            span);
      }
    }
    throw new IllegalStateException("jsharp.core.Literals." + name);
  }

  private BExpr error(Expr e) {
    for (Expr x : e instanceof Expr.CollectionLiteral c ? c.elements() : List.<Expr>of()) {
      if (x instanceof Expr.Spread sp) {
        a.value(sp.expr(), null);
      } else {
        a.value(x, null);
      }
    }
    return error(e.span());
  }

  private static BExpr error(Span span) {
    return new BExpr.Error(Type.ErrorType.INSTANCE, span);
  }
}

package dev.jsharp.compiler.types;

import dev.jsharp.compiler.ast.TypeParam;
import dev.jsharp.compiler.symbols.ClassSymbol;
import dev.jsharp.compiler.symbols.Flags;
import dev.jsharp.compiler.symbols.MethodSymbol;
import dev.jsharp.compiler.symbols.Symtab;
import dev.jsharp.compiler.symbols.TypeVarSymbol;
import dev.jsharp.compiler.types.Type.ArrayType;
import dev.jsharp.compiler.types.Type.ClassType;
import dev.jsharp.compiler.types.Type.PrimType;
import dev.jsharp.compiler.types.Type.TupleType;
import dev.jsharp.compiler.types.Type.TypeVar;
import dev.jsharp.compiler.types.Type.WildcardType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Type algebra: substitution, supertypes, subtyping (with Java wildcards, J# declaration-site
 * variance and capture), conversions, numeric promotion, least upper bounds and functional
 * interface (SAM) discovery.
 *
 * <p>Subtyping here ignores nullness; nullness compatibility is checked separately by {@link
 * #nullnessCompatible}. Nullness of type <em>arguments</em> is not enforced (see DECISIONS D027).
 */
public final class Types {
  private final Symtab syms;
  private final Map<ClassSymbol, MethodSymbol> samCache = new IdentityHashMap<>();
  private final Set<ClassSymbol> notFunctional = new HashSet<>();
  private int captureCounter;

  public Types(Symtab syms) {
    this.syms = syms;
  }

  public Symtab syms() {
    return syms;
  }

  // ------------------------------------------------------------------ substitution

  /** Maps a class's type parameters to the arguments of {@code ct} (erased bounds if raw). */
  public Map<TypeVarSymbol, Type> typeArgMap(ClassType ct) {
    List<TypeVarSymbol> tps = ct.sym().typeParams();
    if (tps.isEmpty()) {
      return Map.of();
    }
    Map<TypeVarSymbol, Type> m = new IdentityHashMap<>();
    boolean raw = ct.args().size() != tps.size();
    for (int i = 0; i < tps.size(); i++) {
      m.put(tps.get(i), raw ? tps.get(i).erasedBound().withNullness(Nullness.PLATFORM) : ct.args().get(i));
    }
    return m;
  }

  /** Substitutes type variables; {@code T?} with {@code T := X} yields {@code X?}. */
  public static Type subst(Type t, Map<TypeVarSymbol, Type> map) {
    if (map.isEmpty()) {
      return t;
    }
    return switch (t) {
      case TypeVar v -> {
        Type r = map.get(v.sym());
        if (r == null) {
          yield v;
        }
        if (r instanceof WildcardType) {
          yield r;
        }
        if (v.nullness() == Nullness.NULLABLE && r.isReference()) {
          yield r.withNullness(Nullness.NULLABLE);
        }
        if (v.nullness() == Nullness.PLATFORM && r.isReference() && r.nullness() == Nullness.NON_NULL) {
          yield r.withNullness(Nullness.PLATFORM);
        }
        yield r;
      }
      case ClassType c -> {
        if (c.args().isEmpty()) {
          yield c;
        }
        List<Type> args = new ArrayList<>(c.args().size());
        boolean changed = false;
        for (Type a : c.args()) {
          Type s = subst(a, map);
          changed |= s != a;
          args.add(s);
        }
        yield changed ? new ClassType(c.sym(), args, c.nullness()) : c;
      }
      case ArrayType a -> {
        Type e = subst(a.elem(), map);
        yield e == a.elem() ? a : new ArrayType(e, a.nullness());
      }
      case WildcardType w -> w.bound() == null ? w : new WildcardType(w.kind(), subst(w.bound(), map));
      case TupleType tt -> {
        List<Type> es = new ArrayList<>();
        for (Type e : tt.elems()) {
          es.add(subst(e, map));
        }
        yield new TupleType(es, tt.names(), tt.erasedSym(), tt.nullness());
      }
      default -> t;
    };
  }

  public static Map<TypeVarSymbol, Type> zip(List<TypeVarSymbol> vars, List<Type> args) {
    Map<TypeVarSymbol, Type> m = new IdentityHashMap<>();
    for (int i = 0; i < vars.size() && i < args.size(); i++) {
      m.put(vars.get(i), args.get(i));
    }
    return m;
  }

  // ------------------------------------------------------------------ supertypes

  /** The (substituted) superclass of a class type, or null. */
  public ClassType supertype(ClassType t) {
    ClassType sup = t.sym().superclass();
    if (sup == null) {
      return t.sym().isInterface() ? syms.objectType() : null;
    }
    if (t.isRaw()) {
      return (ClassType) sup.erasure();
    }
    return (ClassType) subst(sup, typeArgMap(t));
  }

  /** The (substituted) direct superinterfaces of a class type. */
  public List<ClassType> interfaces(ClassType t) {
    List<ClassType> out = new ArrayList<>();
    Map<TypeVarSymbol, Type> m = t.isRaw() ? null : typeArgMap(t);
    for (ClassType i : t.sym().interfaces()) {
      out.add(m == null ? (ClassType) i.erasure() : (ClassType) subst(i, m));
    }
    return out;
  }

  /** The supertype of {@code t} whose class is {@code sym}, with type arguments, or null. */
  public ClassType asSuper(Type t, ClassSymbol sym) {
    return asSuper(t, sym, new HashSet<>());
  }

  private ClassType asSuper(Type t, ClassSymbol sym, Set<ClassSymbol> seen) {
    switch (t) {
      case ClassType c -> {
        if (c.sym() == sym) {
          return c;
        }
        if (!seen.add(c.sym())) {
          return null;
        }
        if (sym == syms.objectSym()) {
          return syms.objectType();
        }
        ClassType sup = supertype(c);
        if (sup != null) {
          ClassType r = asSuper(sup, sym, seen);
          if (r != null) {
            return r;
          }
        }
        for (ClassType i : interfaces(c)) {
          ClassType r = asSuper(i, sym, seen);
          if (r != null) {
            return r;
          }
        }
        return null;
      }
      case TypeVar v -> {
        for (Type b : v.sym().bounds()) {
          ClassType r = asSuper(b, sym, seen);
          if (r != null) {
            return r;
          }
        }
        return null;
      }
      case ArrayType a -> {
        String bn = sym.binaryName();
        if (bn.equals("java/lang/Object") || bn.equals("java/lang/Cloneable") || bn.equals("java/io/Serializable")) {
          return ClassType.of(sym);
        }
        return null;
      }
      case TupleType tt -> {
        return asSuper(tupleClassType(tt), sym, seen);
      }
      default -> {
        return null;
      }
    }
  }

  /** {@code (int, String)} as {@code Tuple2<Integer, String>}. */
  public ClassType tupleClassType(TupleType tt) {
    List<Type> args = new ArrayList<>();
    for (Type e : tt.elems()) {
      args.add(boxIfPrimitive(e));
    }
    return new ClassType(tt.erasedSym(), args, tt.nullness());
  }

  public Type boxIfPrimitive(Type t) {
    return t instanceof PrimType p && p != PrimType.VOID ? syms.boxed(p) : t;
  }

  // ------------------------------------------------------------------ subtyping

  /** Structural subtyping, ignoring nullness. */
  public boolean isSubtype(Type s, Type t) {
    if (s == t || s instanceof Type.ErrorType || t instanceof Type.ErrorType || s instanceof Type.NeverType) {
      return true;
    }
    if (t instanceof Type.NeverType) {
      return false;
    }
    if (s instanceof PrimType || t instanceof PrimType) {
      return s == t;
    }
    if (s instanceof Type.NullType) {
      return t.isReference() || t instanceof WildcardType;
    }
    if (t instanceof TypeVar tv) {
      if (s instanceof TypeVar sv && sv.sym() == tv.sym()) {
        return true;
      }
      if (tv.sym().lowerBound() != null && isSubtype(s, tv.sym().lowerBound())) {
        return true;
      }
      if (s instanceof TypeVar sv) {
        for (Type b : sv.sym().bounds()) {
          if (isSubtype(b, t)) {
            return true;
          }
        }
      }
      return false;
    }
    if (s instanceof TypeVar sv) {
      for (Type b : sv.sym().bounds()) {
        if (isSubtype(b, t)) {
          return true;
        }
      }
      return false;
    }
    if (t instanceof TupleType tt) {
      if (s instanceof TupleType st && st.elems().size() == tt.elems().size()) {
        for (int i = 0; i < st.elems().size(); i++) {
          Type a = st.elems().get(i);
          Type b = tt.elems().get(i);
          if (a instanceof PrimType || b instanceof PrimType ? a != b : !isSubtype(a, b)) {
            return false;
          }
        }
        return true;
      }
      return false;
    }
    if (t instanceof ArrayType ta) {
      if (!(s instanceof ArrayType sa)) {
        return false;
      }
      if (sa.elem() instanceof PrimType || ta.elem() instanceof PrimType) {
        return sa.elem() == ta.elem();
      }
      return isSubtype(sa.elem(), ta.elem());
    }
    if (t instanceof ClassType tc) {
      ClassType sup = asSuper(s, tc.sym());
      if (sup == null) {
        return false;
      }
      if (tc.args().isEmpty() || sup.args().isEmpty()) {
        return true; // raw types: unchecked
      }
      List<TypeVarSymbol> tps = tc.sym().typeParams();
      for (int i = 0; i < tc.args().size(); i++) {
        TypeParam.Variance v = i < tps.size() ? tps.get(i).variance() : TypeParam.Variance.INVARIANT;
        if (!containsArg(sup.args().get(i), tc.args().get(i), v)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /** Type-argument containment: is {@code s} (an argument of the subtype) within {@code t}? */
  private boolean containsArg(Type s, Type t, TypeParam.Variance declared) {
    if (t instanceof WildcardType w) {
      return switch (w.kind()) {
        case UNBOUNDED -> true;
        case EXTENDS -> isSubtype(upperOf(s), w.bound());
        case SUPER -> s instanceof WildcardType sw ? sw.kind() == WildcardType.Kind.SUPER && isSubtype(w.bound(), sw.bound()) : isSubtype(w.bound(), s);
      };
    }
    if (s instanceof WildcardType) {
      return false;
    }
    return switch (declared) {
      case OUT -> isSubtype(s, t);
      case IN -> isSubtype(t, s);
      case INVARIANT -> isSameType(s, t);
    };
  }

  private Type upperOf(Type t) {
    if (t instanceof WildcardType w) {
      return w.kind() == WildcardType.Kind.EXTENDS ? w.bound() : syms.objectType();
    }
    return t;
  }

  /** Type identity ignoring nullness. */
  public boolean isSameType(Type a, Type b) {
    if (a == b || a instanceof Type.ErrorType || b instanceof Type.ErrorType) {
      return true;
    }
    return switch (a) {
      case PrimType p -> p == b;
      case ClassType c -> {
        if (!(b instanceof ClassType d) || c.sym() != d.sym() || c.args().size() != d.args().size()) {
          yield b instanceof ClassType d2 && c.sym() == d2.sym() && (c.args().isEmpty() || d2.args().isEmpty());
        }
        for (int i = 0; i < c.args().size(); i++) {
          if (!isSameType(c.args().get(i), d.args().get(i))) {
            yield false;
          }
        }
        yield true;
      }
      case ArrayType x -> b instanceof ArrayType y && isSameType(x.elem(), y.elem());
      case TypeVar x -> b instanceof TypeVar y && x.sym() == y.sym();
      case WildcardType x ->
          b instanceof WildcardType y
              && x.kind() == y.kind()
              && (x.bound() == null ? y.bound() == null : y.bound() != null && isSameType(x.bound(), y.bound()));
      case TupleType x -> {
        if (!(b instanceof TupleType y) || x.elems().size() != y.elems().size()) {
          yield false;
        }
        for (int i = 0; i < x.elems().size(); i++) {
          if (!isSameType(x.elems().get(i), y.elems().get(i))) {
            yield false;
          }
        }
        yield true;
      }
      default -> a.getClass() == b.getClass();
    };
  }

  /** Can a value of {@code from} flow into {@code to} without violating nullness? */
  public boolean nullnessCompatible(Type from, Type to) {
    if (!to.isReference() || from instanceof Type.ErrorType || from instanceof Type.NeverType) {
      return true;
    }
    if (to.nullness() != Nullness.NON_NULL) {
      return true;
    }
    if (from instanceof Type.NullType) {
      return false;
    }
    return from.nullness() != Nullness.NULLABLE;
  }

  // ------------------------------------------------------------------ boxing & numerics

  /** The primitive a wrapper type unboxes to, or null. */
  public PrimType unboxedType(Type t) {
    if (t instanceof ClassType c) {
      return syms.unboxedOf(c.sym());
    }
    if (t instanceof TypeVar v) {
      for (Type b : v.sym().bounds()) {
        PrimType p = unboxedType(b);
        if (p != null) {
          return p;
        }
      }
    }
    return null;
  }

  /** Primitive view of {@code t}: itself if primitive, its unboxed type if a wrapper, else null. */
  public PrimType primitiveView(Type t) {
    return t instanceof PrimType p ? p : unboxedType(t);
  }

  public boolean isNumeric(Type t) {
    PrimType p = primitiveView(t);
    return p != null && p.isNumeric();
  }

  public boolean isIntegral(Type t) {
    PrimType p = primitiveView(t);
    return p != null && p.isIntegral();
  }

  public boolean isBoolean(Type t) {
    return primitiveView(t) == PrimType.BOOLEAN;
  }

  /** Binary numeric promotion. */
  public static PrimType promote(PrimType a, PrimType b) {
    if (a == PrimType.DOUBLE || b == PrimType.DOUBLE) {
      return PrimType.DOUBLE;
    }
    if (a == PrimType.FLOAT || b == PrimType.FLOAT) {
      return PrimType.FLOAT;
    }
    if (a == PrimType.LONG || b == PrimType.LONG) {
      return PrimType.LONG;
    }
    return PrimType.INT;
  }

  /** Unary numeric promotion. */
  public static PrimType promote(PrimType a) {
    return switch (a) {
      case BYTE, SHORT, CHAR -> PrimType.INT;
      default -> a;
    };
  }

  /** Widening primitive conversion (JLS 5.1.2). */
  public static boolean isWidening(PrimType from, PrimType to) {
    if (from == to) {
      return true;
    }
    return switch (from) {
      case BYTE -> to == PrimType.SHORT || to == PrimType.INT || to == PrimType.LONG || to == PrimType.FLOAT || to == PrimType.DOUBLE;
      case SHORT, CHAR -> to == PrimType.INT || to == PrimType.LONG || to == PrimType.FLOAT || to == PrimType.DOUBLE;
      case INT -> to == PrimType.LONG || to == PrimType.FLOAT || to == PrimType.DOUBLE;
      case LONG -> to == PrimType.FLOAT || to == PrimType.DOUBLE;
      case FLOAT -> to == PrimType.DOUBLE;
      default -> false;
    };
  }

  // ------------------------------------------------------------------ assignment conversion

  /** Kinds of implicit conversion in assignment/argument contexts. */
  public enum Conv {
    /** Not convertible. */
    NONE,
    IDENTITY,
    /** Reference widening (subtype). */
    REFERENCE,
    /** Widening primitive conversion. */
    WIDEN,
    /** Narrowing of an int constant that fits (byte b = 5). */
    NARROW_CONSTANT,
    /** Boxing, possibly followed by reference widening (int -> Integer / Object / Number). */
    BOX,
    /** Unboxing, possibly followed by primitive widening (Integer -> int / long). */
    UNBOX
  }

  /**
   * Classifies the implicit conversion from {@code from} to {@code to} (ignoring nullness).
   *
   * @param intConstant value of {@code from} if it is an int constant expression, else null
   */
  public Conv assignConversion(Type from, Type to, Integer intConstant) {
    if (from instanceof Type.ErrorType || to instanceof Type.ErrorType) {
      return Conv.IDENTITY;
    }
    if (from instanceof Type.NeverType) {
      return Conv.IDENTITY;
    }
    if (from instanceof PrimType fp && to instanceof PrimType tp) {
      if (fp == tp) {
        return Conv.IDENTITY;
      }
      if (fp == PrimType.VOID || tp == PrimType.VOID) {
        return Conv.NONE;
      }
      if (isWidening(fp, tp)) {
        return Conv.WIDEN;
      }
      if (intConstant != null && fitsConstant(intConstant, fp, tp)) {
        return Conv.NARROW_CONSTANT;
      }
      return Conv.NONE;
    }
    if (from instanceof PrimType fp) {
      if (fp == PrimType.VOID) {
        return Conv.NONE;
      }
      // Boxing then widening reference conversion (int -> Integer -> Number/Object/Comparable).
      if (isSubtype(syms.boxed(fp), to)) {
        return Conv.BOX;
      }
      // Constant narrowing into a wrapper: Byte b = 5.
      if (intConstant != null && to instanceof ClassType tc) {
        PrimType u = syms.unboxedOf(tc.sym());
        if (u != null && fitsConstant(intConstant, fp, u)) {
          return Conv.BOX;
        }
      }
      return Conv.NONE;
    }
    if (to instanceof PrimType tp) {
      PrimType u = unboxedType(from);
      if (u != null && tp != PrimType.VOID && isWidening(u, tp)) {
        return Conv.UNBOX;
      }
      return Conv.NONE;
    }
    if (isSubtype(from, to)) {
      return isSameType(from, to) ? Conv.IDENTITY : Conv.REFERENCE;
    }
    return Conv.NONE;
  }

  private static boolean fitsConstant(int v, PrimType from, PrimType to) {
    if (from != PrimType.INT && from != PrimType.SHORT && from != PrimType.CHAR && from != PrimType.BYTE) {
      return false;
    }
    return switch (to) {
      case BYTE -> v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE;
      case SHORT -> v >= Short.MIN_VALUE && v <= Short.MAX_VALUE;
      case CHAR -> v >= Character.MIN_VALUE && v <= Character.MAX_VALUE;
      default -> false;
    };
  }

  /** Is an explicit cast from {@code from} to {@code to} legal? */
  public boolean isCastable(Type from, Type to) {
    if (from instanceof Type.ErrorType || to instanceof Type.ErrorType || from instanceof Type.NeverType) {
      return true;
    }
    PrimType fp = primitiveView(from);
    if (from instanceof PrimType && to instanceof PrimType tp) {
      return fp == tp || (fp.isNumeric() && tp.isNumeric());
    }
    if (from instanceof PrimType p) {
      return assignConversion(p, to, null) != Conv.NONE;
    }
    if (to instanceof PrimType tp) {
      // Object -> int (checkcast Integer + unbox) and Integer -> long are allowed.
      PrimType u = unboxedType(from);
      if (u != null) {
        return u == tp || isWidening(u, tp);
      }
      return isSubtype(syms.boxed(tp), from);
    }
    if (from instanceof Type.NullType) {
      return to.isReference();
    }
    if (isSubtype(from, to) || isSubtype(to, from)) {
      return true;
    }
    // Interfaces can be cast to/from non-final classes; reject provably disjoint final classes.
    ClassSymbol a = classOf(from);
    ClassSymbol b = classOf(to);
    if (a == null || b == null) {
      return true;
    }
    if (a.isInterface() && b.isInterface()) {
      return true;
    }
    if (a.isInterface()) {
      return !b.isFinal() || isSubtype(to.erasure(), from.erasure());
    }
    if (b.isInterface()) {
      return !a.isFinal() || isSubtype(from.erasure(), to.erasure());
    }
    return isSubtype(from.erasure(), to.erasure()) || isSubtype(to.erasure(), from.erasure());
  }

  private static ClassSymbol classOf(Type t) {
    return t instanceof ClassType c ? c.sym() : null;
  }

  // ------------------------------------------------------------------ least upper bound

  /**
   * A practical least upper bound: identical types, numeric promotion, subtype relation, the
   * nearest common superclass, or a single common interface. Nullness is the join.
   */
  public Type lub(List<Type> types) {
    List<Type> real = new ArrayList<>();
    boolean nullable = false;
    boolean platform = false;
    for (Type t : types) {
      if (t instanceof Type.NeverType) {
        continue;
      }
      if (t instanceof Type.ErrorType) {
        return t;
      }
      if (t instanceof Type.NullType) {
        nullable = true;
        continue;
      }
      nullable |= t.nullness() == Nullness.NULLABLE;
      platform |= t.nullness() == Nullness.PLATFORM;
      real.add(t);
    }
    if (real.isEmpty()) {
      return nullable ? Type.NullType.INSTANCE : Type.NeverType.INSTANCE;
    }
    Type result = real.getFirst();
    for (int i = 1; i < real.size(); i++) {
      result = lub2(result, real.get(i));
    }
    if (nullable) {
      if (result instanceof PrimType p) {
        return syms.boxed(p).withNullness(Nullness.NULLABLE);
      }
      return result.withNullness(Nullness.NULLABLE);
    }
    if (platform && result.isReference()) {
      return result.withNullness(Nullness.PLATFORM);
    }
    return result.isReference() ? result.withNullness(Nullness.NON_NULL) : result;
  }

  private Type lub2(Type a, Type b) {
    if (isSameType(a, b)) {
      return a;
    }
    PrimType pa = primitiveView(a);
    PrimType pb = primitiveView(b);
    if (pa != null && pb != null && pa.isNumeric() && pb.isNumeric() && (a instanceof PrimType || b instanceof PrimType)) {
      return promote(pa, pb);
    }
    if (pa == PrimType.BOOLEAN && pb == PrimType.BOOLEAN) {
      return PrimType.BOOLEAN;
    }
    Type ra = boxIfPrimitive(a);
    Type rb = boxIfPrimitive(b);
    if (isSubtype(ra, rb)) {
      return rb;
    }
    if (isSubtype(rb, ra)) {
      return ra;
    }
    // Walk a's superclass chain for a common superclass (other than Object).
    if (ra instanceof ClassType ca) {
      for (ClassType s = supertype(ca); s != null; s = supertype(s)) {
        if (s.sym() != syms.objectSym() && isSubtype(rb, s)) {
          return s;
        }
      }
      // A single shared interface (e.g. sealed interface of records).
      List<ClassType> common = new ArrayList<>();
      collectInterfaces(ca, new LinkedHashMap<>()).values().forEach(i -> {
        if (isSubtype(rb, i)) {
          common.add(i);
        }
      });
      List<ClassType> minimal = new ArrayList<>();
      for (ClassType c : common) {
        boolean dominated = false;
        for (ClassType d : common) {
          if (d != c && d.sym() != c.sym() && isSubtype(d, c)) {
            dominated = true;
          }
        }
        if (!dominated && minimal.stream().noneMatch(m -> m.sym() == c.sym())) {
          minimal.add(c);
        }
      }
      if (minimal.size() == 1) {
        return minimal.getFirst();
      }
    }
    return syms.objectType();
  }

  private Map<ClassSymbol, ClassType> collectInterfaces(ClassType t, Map<ClassSymbol, ClassType> out) {
    for (ClassType i : interfaces(t)) {
      if (out.putIfAbsent(i.sym(), i) == null) {
        collectInterfaces(i, out);
      }
    }
    ClassType sup = supertype(t);
    if (sup != null && sup.sym() != syms.objectSym()) {
      collectInterfaces(sup, out);
    }
    return out;
  }

  // ------------------------------------------------------------------ functional interfaces

  /** The single abstract method of a functional interface, or null. */
  public MethodSymbol findSam(ClassSymbol c) {
    if (!c.isInterface() || notFunctional.contains(c)) {
      return null;
    }
    MethodSymbol cached = samCache.get(c);
    if (cached != null) {
      return cached;
    }
    Map<String, MethodSymbol> abstracts = new LinkedHashMap<>();
    Set<String> implemented = new HashSet<>();
    collectAbstract(ClassType.of(c), abstracts, implemented, new HashSet<>());
    abstracts.keySet().removeAll(implemented);
    if (abstracts.size() != 1) {
      notFunctional.add(c);
      return null;
    }
    MethodSymbol sam = abstracts.values().iterator().next();
    samCache.put(c, sam);
    return sam;
  }

  private void collectAbstract(ClassType t, Map<String, MethodSymbol> abstracts, Set<String> implemented, Set<ClassSymbol> seen) {
    if (!seen.add(t.sym())) {
      return;
    }
    for (MethodSymbol m : t.sym().allMethods()) {
      if (m.isStatic() || m.has(Flags.PRIVATE)) {
        continue;
      }
      String key = m.name() + "/" + m.params().size();
      if (m.isAbstract()) {
        if (isObjectMethod(m) || implemented.contains(key)) {
          continue;
        }
        abstracts.putIfAbsent(key, m);
      } else {
        implemented.add(key);
      }
    }
    for (ClassType i : t.sym().interfaces()) {
      collectAbstract(i, abstracts, implemented, seen);
    }
  }

  private static boolean isObjectMethod(MethodSymbol m) {
    return switch (m.name()) {
      case "equals" -> m.params().size() == 1;
      case "hashCode", "toString" -> m.params().isEmpty();
      default -> false;
    };
  }

  /** A method's parameter and return types as a member of {@code site}. */
  public record MethodType(List<Type> params, Type ret) {}

  /**
   * The function type of a functional interface type, using the non-wildcard parameterization
   * (JLS 9.9): {@code Function<? super T, ? extends R>} behaves as {@code Function<T, R>}.
   */
  public MethodType functionType(ClassType fi) {
    MethodSymbol sam = findSam(fi.sym());
    if (sam == null) {
      return null;
    }
    ClassType plain = nonWildcard(fi);
    ClassType owner = asSuper(plain, sam.owner());
    Map<TypeVarSymbol, Type> m = owner == null ? Map.of() : typeArgMap(owner);
    List<Type> ps = new ArrayList<>();
    for (MethodSymbol.Param p : sam.params()) {
      ps.add(subst(p.type(), m));
    }
    return new MethodType(ps, subst(sam.returnType(), m));
  }

  public ClassType nonWildcard(ClassType t) {
    if (t.args().stream().noneMatch(a -> a instanceof WildcardType)) {
      return t;
    }
    List<Type> args = new ArrayList<>();
    List<TypeVarSymbol> tps = t.sym().typeParams();
    for (int i = 0; i < t.args().size(); i++) {
      Type a = t.args().get(i);
      if (a instanceof WildcardType w) {
        args.add(w.bound() != null ? w.bound() : (i < tps.size() ? tps.get(i).bounds().getFirst() : syms.objectType()));
      } else {
        args.add(a);
      }
    }
    return new ClassType(t.sym(), args, t.nullness());
  }

  // ------------------------------------------------------------------ capture conversion

  /** Replaces wildcard arguments with fresh captured type variables. */
  public ClassType capture(ClassType t) {
    if (t.args().stream().noneMatch(a -> a instanceof WildcardType)) {
      return t;
    }
    List<TypeVarSymbol> tps = t.sym().typeParams();
    List<Type> args = new ArrayList<>();
    for (int i = 0; i < t.args().size(); i++) {
      Type a = t.args().get(i);
      if (a instanceof WildcardType w) {
        Type declared = i < tps.size() ? tps.get(i).bounds().getFirst() : syms.objectType();
        Type upper = w.kind() == WildcardType.Kind.EXTENDS ? w.bound() : declared;
        Type lower = w.kind() == WildcardType.Kind.SUPER ? w.bound() : null;
        TypeVarSymbol cap = TypeVarSymbol.capture("capture#" + (++captureCounter) + " of " + w.display(), List.of(upper), upper.erasure(), lower);
        args.add(new TypeVar(cap, Nullness.NON_NULL));
      } else {
        args.add(a);
      }
    }
    return new ClassType(t.sym(), args, t.nullness());
  }

  /** Replaces captured variables in a result type by their bounds (for display and storage). */
  public Type uncapture(Type t) {
    return switch (t) {
      case TypeVar v when v.sym().isCaptured() ->
          v.sym().lowerBound() != null ? v.sym().lowerBound() : v.sym().bounds().getFirst().withNullness(v.nullness());
      case ClassType c -> {
        if (c.args().isEmpty()) {
          yield c;
        }
        List<Type> args = new ArrayList<>();
        for (Type a : c.args()) {
          if (a instanceof TypeVar v && v.sym().isCaptured()) {
            args.add(
                v.sym().lowerBound() != null
                    ? new WildcardType(WildcardType.Kind.SUPER, v.sym().lowerBound())
                    : new WildcardType(WildcardType.Kind.EXTENDS, v.sym().bounds().getFirst()));
          } else {
            args.add(uncapture(a));
          }
        }
        yield new ClassType(c.sym(), args, c.nullness());
      }
      case ArrayType a -> new ArrayType(uncapture(a.elem()), a.nullness());
      default -> t;
    };
  }

  // ------------------------------------------------------------------ misc

  /** Is the class (or a supertype) the given binary name? */
  public boolean isSubclassOf(Type t, String binaryName) {
    ClassSymbol s = syms.lookup(binaryName);
    return s != null && asSuper(t, s) != null;
  }

  /** Iterable element type: {@code Iterable<T>} -> T, arrays -> element type, else null. */
  public Type iterableElement(Type t) {
    if (t instanceof ArrayType a) {
      return a.elem();
    }
    ClassSymbol iterable = syms.lookup("java/lang/Iterable");
    ClassType it = asSuper(t, iterable);
    if (it == null) {
      return null;
    }
    if (it.args().isEmpty()) {
      return syms.objectType().withNullness(Nullness.PLATFORM);
    }
    Type e = it.args().getFirst();
    if (e instanceof WildcardType w) {
      return w.kind() == WildcardType.Kind.EXTENDS ? w.bound() : syms.objectType();
    }
    return e;
  }

  /** Fresh map helper. */
  public static <K, V> Map<K, V> newMap() {
    return new HashMap<>();
  }
}

package dev.jsharp.compiler.check;

import dev.jsharp.compiler.ast.TypeParam;
import dev.jsharp.compiler.symbols.TypeVarSymbol;
import dev.jsharp.compiler.types.Type;
import dev.jsharp.compiler.types.Type.ArrayType;
import dev.jsharp.compiler.types.Type.ClassType;
import dev.jsharp.compiler.types.Type.TypeVar;
import dev.jsharp.compiler.types.Type.WildcardType;
import dev.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local type inference for generic method calls: collects subtype/equality constraints between
 * argument (and expected result) types and the method's type variables, then solves each variable
 * from its equality, lower or upper bounds. A pragmatic subset of JLS 18 that handles the common
 * cases (factories, collections, streams, functional composition).
 */
final class Infer {
  /** Bounds collected for one inference variable. */
  static final class Bounds {
    final List<Type> lower = new ArrayList<>();
    final List<Type> upper = new ArrayList<>();
    final List<Type> eq = new ArrayList<>();
  }

  private final Types types;
  final List<TypeVarSymbol> vars;
  final Map<TypeVarSymbol, Bounds> bounds = new LinkedHashMap<>();
  boolean failed;

  Infer(Types types, List<TypeVarSymbol> vars) {
    this.types = types;
    this.vars = vars;
    for (TypeVarSymbol v : vars) {
      bounds.put(v, new Bounds());
    }
  }

  boolean isVar(Type t) {
    return t instanceof TypeVar v && bounds.containsKey(v.sym());
  }

  boolean mentionsVars(Type t) {
    return switch (t) {
      case TypeVar v -> bounds.containsKey(v.sym());
      case ClassType c -> c.args().stream().anyMatch(this::mentionsVars);
      case ArrayType a -> mentionsVars(a.elem());
      case WildcardType w -> w.bound() != null && mentionsVars(w.bound());
      case Type.TupleType tt -> tt.elems().stream().anyMatch(this::mentionsVars);
      default -> false;
    };
  }

  /** Records {@code s <: t}. */
  void subtype(Type s, Type t) {
    if (s == null || t == null || s.isError() || t.isError() || s instanceof Type.NeverType) {
      return;
    }
    if (isVar(t)) {
      if (s instanceof Type.NullType) {
        bounds.get(((TypeVar) t).sym()).lower.add(s);
        return;
      }
      bounds.get(((TypeVar) t).sym()).lower.add(types.boxIfPrimitive(s));
      return;
    }
    if (isVar(s)) {
      bounds.get(((TypeVar) s).sym()).upper.add(t);
      return;
    }
    if (!mentionsVars(t) && !mentionsVars(s)) {
      return; // checked later by the caller with plain subtyping
    }
    switch (t) {
      case ClassType tc -> {
        ClassType sup = types.asSuper(types.boxIfPrimitive(s), tc.sym());
        if (sup == null) {
          failed = true;
          return;
        }
        if (sup.args().isEmpty() || tc.args().isEmpty()) {
          return;
        }
        List<TypeVarSymbol> tps = tc.sym().typeParams();
        for (int i = 0; i < tc.args().size() && i < sup.args().size(); i++) {
          Type ta = tc.args().get(i);
          Type sa = sup.args().get(i);
          TypeParam.Variance v =
              i < tps.size() ? tps.get(i).variance() : TypeParam.Variance.INVARIANT;
          if (ta instanceof WildcardType w) {
            switch (w.kind()) {
              case EXTENDS -> subtype(upper(sa), w.bound());
              case SUPER -> {
                if (sa instanceof WildcardType sw && sw.kind() == WildcardType.Kind.SUPER) {
                  subtype(w.bound(), sw.bound());
                } else if (!(sa instanceof WildcardType)) {
                  subtype(w.bound(), sa);
                }
              }
              case UNBOUNDED -> {}
            }
          } else if (sa instanceof WildcardType sw) {
            // A wildcard argument can only match a variable through its bound.
            if (sw.kind() == WildcardType.Kind.EXTENDS && isVar(ta)) {
              subtype(sw.bound(), ta);
            }
          } else {
            switch (v) {
              case OUT -> subtype(sa, ta);
              case IN -> subtype(ta, sa);
              case INVARIANT -> eq(sa, ta);
            }
          }
        }
      }
      case ArrayType ta -> {
        if (s instanceof ArrayType sa) {
          if (ta.elem() instanceof Type.PrimType || sa.elem() instanceof Type.PrimType) {
            eq(sa.elem(), ta.elem());
          } else {
            subtype(sa.elem(), ta.elem());
          }
        } else {
          failed = true;
        }
      }
      case Type.TupleType tt -> {
        if (s instanceof Type.TupleType st && st.elems().size() == tt.elems().size()) {
          for (int i = 0; i < st.elems().size(); i++) {
            subtype(st.elems().get(i), tt.elems().get(i));
          }
        }
      }
      default -> {}
    }
  }

  private Type upper(Type t) {
    if (t instanceof WildcardType w) {
      return w.kind() == WildcardType.Kind.EXTENDS ? w.bound() : types.syms().objectType();
    }
    return t;
  }

  /** Records {@code a = b}. */
  void eq(Type a, Type b) {
    if (a == null || b == null || a.isError() || b.isError()) {
      return;
    }
    if (isVar(b)) {
      bounds.get(((TypeVar) b).sym()).eq.add(types.boxIfPrimitive(a));
      return;
    }
    if (isVar(a)) {
      bounds.get(((TypeVar) a).sym()).eq.add(types.boxIfPrimitive(b));
      return;
    }
    if (a instanceof ClassType ac
        && b instanceof ClassType bc
        && ac.sym() == bc.sym()
        && ac.args().size() == bc.args().size()) {
      for (int i = 0; i < ac.args().size(); i++) {
        Type x = ac.args().get(i);
        Type y = bc.args().get(i);
        if (x instanceof WildcardType wx && y instanceof WildcardType wy) {
          if (wx.bound() != null && wy.bound() != null) {
            eq(wx.bound(), wy.bound());
          }
        } else {
          eq(x, y);
        }
      }
    } else if (a instanceof ArrayType aa && b instanceof ArrayType ba) {
      eq(aa.elem(), ba.elem());
    }
  }

  /**
   * Solves the constraints. Unconstrained variables default to their (substituted) first bound when
   * {@code defaultUnconstrained}, else stay unsolved (absent from the map).
   */
  Map<TypeVarSymbol, Type> solve(boolean defaultUnconstrained) {
    Map<TypeVarSymbol, Type> sol = new IdentityHashMap<>();
    boolean progress = true;
    while (progress) {
      progress = false;
      for (TypeVarSymbol v : vars) {
        if (sol.containsKey(v)) {
          continue;
        }
        Bounds b = bounds.get(v);
        Type t = pick(b, sol);
        if (t != null) {
          sol.put(v, t);
          progress = true;
        }
      }
    }
    if (defaultUnconstrained) {
      for (TypeVarSymbol v : vars) {
        if (!sol.containsKey(v)) {
          Type bound = Types.subst(v.bounds().getFirst(), sol);
          sol.put(v, mentionsVars(bound) ? types.syms().objectType() : bound);
        }
      }
    }
    return sol;
  }

  private Type pick(Bounds b, Map<TypeVarSymbol, Type> sol) {
    List<Type> eqs = resolved(b.eq, sol);
    if (!eqs.isEmpty()) {
      return eqs.getFirst();
    }
    List<Type> lows = resolved(b.lower, sol);
    if (!lows.isEmpty() && lows.size() == b.lower.size()) {
      Type lub = types.lub(lows);
      if (lub instanceof Type.NullType) {
        return null; // only null: let upper bounds/defaults decide
      }
      return lub;
    }
    List<Type> ups = resolved(b.upper, sol);
    if (!ups.isEmpty() && lows.isEmpty() && b.lower.isEmpty()) {
      for (Type u : ups) {
        boolean lowest = true;
        for (Type o : ups) {
          if (o != u && !types.isSubtype(u, o)) {
            lowest = false;
          }
        }
        if (lowest) {
          return u;
        }
      }
      return ups.getFirst();
    }
    return null;
  }

  private List<Type> resolved(List<Type> ts, Map<TypeVarSymbol, Type> sol) {
    List<Type> out = new ArrayList<>();
    for (Type t : ts) {
      Type s = Types.subst(t, sol);
      if (!mentionsVars(s)) {
        out.add(s);
      }
    }
    return out;
  }

  /** Checks that a full solution satisfies all recorded bounds and declared bounds. */
  boolean check(Map<TypeVarSymbol, Type> sol) {
    if (failed) {
      return false;
    }
    for (TypeVarSymbol v : vars) {
      Type t = sol.get(v);
      if (t == null) {
        return false;
      }
      Bounds b = bounds.get(v);
      for (Type l : b.lower) {
        if (!types.isSubtype(Types.subst(l, sol), t)) {
          return false;
        }
      }
      for (Type u : b.upper) {
        if (!types.isSubtype(t, Types.subst(u, sol))) {
          return false;
        }
      }
      for (Type e : b.eq) {
        if (!types.isSameType(t, Types.subst(e, sol))) {
          return false;
        }
      }
      for (Type declared : v.bounds()) {
        if (!types.isSubtype(t, Types.subst(declared, sol))) {
          return false;
        }
      }
    }
    return true;
  }
}

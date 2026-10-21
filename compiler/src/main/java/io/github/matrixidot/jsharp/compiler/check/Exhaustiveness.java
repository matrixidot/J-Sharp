package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.bound.BExpr;
import io.github.matrixidot.jsharp.compiler.bound.BPattern;
import io.github.matrixidot.jsharp.compiler.bound.BSwitch;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.source.Span;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import io.github.matrixidot.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Exhaustiveness and dominance of switch cases (spec section 6): whether the patterns cover every
 * value of the selector type (sealed hierarchies, enums, booleans, records, tuples, array lengths),
 * which values are missing, and which cases can never match because earlier ones cover them.
 */
final class Exhaustiveness {
  private final Attr a;

  Exhaustiveness(Attr a) {
    this.a = a;
  }

  private Types types() {
    return a.types;
  }

  /** Does pattern {@code p} match every non-null value of {@code t}? */
  boolean isTotal(BPattern p, Type t) {
    return switch (p) {
      case null -> true;
      case BPattern.Any any -> true;
      case BPattern.TypeTest tt ->
          types().isSubtype(types().boxIfPrimitive(t).withNullness(Nullness.NON_NULL), tt.type())
              || t.isError();
      case BPattern.Recursive r -> {
        if (r.type() != null && !types().isSubtype(types().boxIfPrimitive(t), r.type())) {
          yield false;
        }
        for (int i = 0; i < r.subpatterns().size(); i++) {
          if (!isTotal(r.subpatterns().get(i), r.accessorTypes().get(i))
              && !coversAll(List.of(r.subpatterns().get(i)), r.accessorTypes().get(i))) {
            yield false;
          }
        }
        yield true;
      }
      case BPattern.ListPat lp ->
          lp.prefix().isEmpty()
              && lp.suffix().isEmpty()
              && lp.hasSlice()
              && (lp.slice() == null || isTotal(lp.slice(), lp.sliceType())); // [..] matches all
      case BPattern.And and -> isTotal(and.left(), t) && isTotal(and.right(), t);
      case BPattern.Or or ->
          isTotal(or.left(), t)
              || isTotal(or.right(), t)
              || coversAll(List.of(or.left(), or.right()), t);
      default -> false;
    };
  }

  /** Do the patterns together cover every non-null value of {@code t}? */
  private boolean coversAll(List<BPattern> pats, Type t) {
    List<BPattern> flat = new ArrayList<>();
    for (BPattern p : pats) {
      flatten(p, flat);
    }
    for (BPattern p : flat) {
      if (isTotal(p, t)) {
        return true;
      }
    }
    return missing(flat, t).isEmpty();
  }

  /**
   * Coverage by length for list patterns whose element (and slice) patterns all match anything:
   * {@code []}, {@code [_]}, {@code [_, .., _]} cover every list. Returns the uncovered lengths
   * (described as patterns), or null if no such list patterns are present.
   */
  private List<String> missingLengths(List<BPattern> pats) {
    Set<Integer> exact = new HashSet<>();
    int minimum = Integer.MAX_VALUE;
    boolean any = false;
    for (BPattern p : pats) {
      if (!(p instanceof BPattern.ListPat lp)) {
        continue;
      }
      boolean total =
          Stream.concat(lp.prefix().stream(), lp.suffix().stream())
                  .allMatch(x -> isTotal(x, lp.elementType()))
              && (lp.slice() == null || isTotal(lp.slice(), lp.sliceType()));
      if (!total) {
        continue;
      }
      any = true;
      int n = lp.prefix().size() + lp.suffix().size();
      if (lp.hasSlice()) {
        minimum = Math.min(minimum, n);
      } else {
        exact.add(n);
      }
    }
    if (!any) {
      return null;
    }
    List<String> out = new ArrayList<>();
    int limit =
        minimum == Integer.MAX_VALUE
            ? exact.stream().max(Integer::compare).orElse(-1) + 1
            : minimum;
    for (int len = 0; len < limit; len++) {
      if (!exact.contains(len)) {
        out.add(describeLength(len, false));
      }
    }
    if (minimum == Integer.MAX_VALUE) {
      out.add(describeLength(limit, true));
    }
    return out;
  }

  private static String describeLength(int n, boolean orMore) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < n; i++) {
      sb.append(i > 0 ? ", _" : "_");
    }
    if (orMore) {
      sb.append(n > 0 ? ", .." : "..");
    }
    return sb.append(']').toString();
  }

  private static void flatten(BPattern p, List<BPattern> out) {
    if (p instanceof BPattern.Or or) {
      flatten(or.left(), out);
      flatten(or.right(), out);
    } else {
      out.add(p);
    }
  }

  /** Values (described) of {@code t} not covered by the patterns; empty if covered. */
  private List<String> missing(List<BPattern> pats, Type t) {
    for (BPattern p : pats) {
      if (isTotal(p, t)) {
        return List.of();
      }
    }
    List<String> lengths = missingLengths(pats);
    if (lengths != null) {
      return lengths;
    }
    PrimType prim = types().primitiveView(t);
    if (prim == PrimType.BOOLEAN) {
      boolean hasTrue = false;
      boolean hasFalse = false;
      for (BPattern p : pats) {
        if (p instanceof BPattern.Constant c && ConstFold.valueOf(c.value()) instanceof Boolean b) {
          hasTrue |= b;
          hasFalse |= !b;
        }
      }
      List<String> out = new ArrayList<>();
      if (!hasTrue) {
        out.add("true");
      }
      if (!hasFalse) {
        out.add("false");
      }
      return out;
    }
    if (!(t instanceof ClassType ct)) {
      return List.of("_");
    }
    ClassSymbol c = ct.sym();
    if (c.isEnum()) {
      Set<String> covered = new HashSet<>();
      for (BPattern p : pats) {
        if (p instanceof BPattern.Constant k && k.value() instanceof BExpr.Field f) {
          covered.add(f.field().name());
        }
      }
      List<String> out = new ArrayList<>();
      for (FieldSymbol f : c.enumConstants()) {
        if (!covered.contains(f.name())) {
          out.add(c.name() + "." + f.name());
        }
      }
      return out;
    }
    if (c.has(Flags.SEALED) && !c.permitted().isEmpty()) {
      List<String> out = new ArrayList<>();
      if (!c.isAbstract() && !c.isInterface()) {
        out.add(c.name());
      }
      for (ClassSymbol sub : c.permitted()) {
        ClassType subType = subtypeOf(ct, sub);
        List<BPattern> applicable = new ArrayList<>();
        for (BPattern p : pats) {
          if (appliesTo(p, subType)) {
            applicable.add(p);
          }
        }
        List<String> m = missing(applicable, subType);
        if (!m.isEmpty()) {
          out.add(m.equals(List.of("_")) || m.size() > 3 ? sub.name() : String.join(", ", m));
        }
      }
      return out;
    }
    return List.of("_");
  }

  /** Can pattern {@code p} match some value of {@code t}? (Used to restrict to a subtype.) */
  private boolean appliesTo(BPattern p, Type t) {
    return switch (p) {
      case BPattern.TypeTest tt -> types().isCastable(t, tt.type());
      case BPattern.Recursive r -> r.type() == null || types().isCastable(t, r.type());
      default -> true;
    };
  }

  /** {@code Sub} instantiated as a subtype of {@code sup} (type arguments where derivable). */
  private ClassType subtypeOf(ClassType sup, ClassSymbol sub) {
    if (sub.typeParams().isEmpty()) {
      return ClassType.of(sub);
    }
    ClassType generic = sub.thisType();
    ClassType asSup = types().asSuper(generic, sup.sym());
    if (asSup != null && asSup.args().size() == sup.args().size()) {
      Map<TypeVarSymbol, Type> m = new IdentityHashMap<>();
      for (int i = 0; i < asSup.args().size(); i++) {
        if (asSup.args().get(i) instanceof Type.TypeVar tv
            && !(sup.args().get(i) instanceof Type.WildcardType)) {
          m.put(tv.sym(), sup.args().get(i));
        }
      }
      return (ClassType) Types.subst(generic, m);
    }
    return generic;
  }

  /** Reports a non-exhaustive switch expression; returns whether it is exhaustive. */
  boolean checkExhaustive(List<BSwitch.Case> cases, Type input, Span span, boolean required) {
    if (input.isError()) {
      return true;
    }
    List<BPattern> unguarded = new ArrayList<>();
    boolean handlesNull = false;
    boolean hasDefault = false;
    for (BSwitch.Case c : cases) {
      if (c.pattern() == null) {
        hasDefault = true;
        continue;
      }
      List<BPattern> flat = new ArrayList<>();
      flatten(c.pattern(), flat);
      for (BPattern p : flat) {
        if (p instanceof BPattern.Constant k && k.value().type() instanceof Type.NullType) {
          handlesNull = true;
        }
        if (p instanceof BPattern.Any && c.guard() == null) {
          handlesNull = true;
        }
      }
      if (c.guard() == null) {
        unguarded.add(c.pattern());
      }
    }
    if (hasDefault) {
      return true;
    }
    Type nonNull = input.withNullness(Nullness.NON_NULL);
    List<String> miss = missing(flatAll(unguarded), nonNull);
    boolean nullOk = input.nullness() != Nullness.NULLABLE || handlesNull;
    if (miss.isEmpty() && nullOk) {
      return true;
    }
    if (required) {
      Diagnostic.Builder d =
          a.err(
              Code.NOT_EXHAUSTIVE,
              span,
              "switch expression does not handle all values of " + input.display());
      if (!miss.isEmpty()) {
        d.note(
            "not handled: "
                + String.join(", ", miss.subList(0, Math.min(miss.size(), 6)))
                + (miss.size() > 6 ? ", ..." : ""));
      }
      if (!nullOk) {
        d.note("not handled: null");
      }
      d.help(
          miss.equals(List.of("_"))
              ? "add a default arm: '_ => ...'"
              : "add the missing cases, or a default arm '_ => ...'");
      a.report(d);
    }
    return false;
  }

  private static List<BPattern> flatAll(List<BPattern> pats) {
    List<BPattern> out = new ArrayList<>();
    for (BPattern p : pats) {
      flatten(p, out);
    }
    return out;
  }

  /** Reports cases that can never be reached because an earlier unguarded case covers them. */
  void checkDominance(List<BSwitch.Case> cases, List<Span> spans, Type input) {
    List<BPattern> earlier = new ArrayList<>();
    Set<Object> constants = new HashSet<>();
    boolean catchAll = false;
    for (int i = 0; i < cases.size(); i++) {
      BSwitch.Case c = cases.get(i);
      if (catchAll) {
        a.error(
            Code.DUPLICATE_CASE,
            spans.get(i),
            "this case can never match: an earlier case matches everything");
        continue;
      }
      if (c.pattern() == null && c.guard() == null && c.value() != null) {
        catchAll = true; // `_ =>` arm of a switch expression
      }
      if (c.pattern() != null) {
        List<BPattern> flat = new ArrayList<>();
        flatten(c.pattern(), flat);
        for (BPattern p : flat) {
          Object key = constantKey(p);
          if (key != null && !constants.add(key)) {
            a.error(Code.DUPLICATE_CASE, spans.get(i), "duplicate case " + key);
          }
        }
        boolean dominated = false;
        for (BPattern e : earlier) {
          if (dominates(e, c.pattern(), input)) {
            dominated = true;
          }
        }
        if (dominated) {
          a.error(
              Code.DUPLICATE_CASE,
              spans.get(i),
              "this case can never match: an earlier case already covers it");
        }
      }
      if (c.guard() == null && c.pattern() != null) {
        earlier.add(c.pattern());
      }
    }
  }

  private static Object constantKey(BPattern p) {
    if (p instanceof BPattern.Constant c) {
      if (c.value() instanceof BExpr.Field f) {
        return f.field().name();
      }
      Object v = ConstFold.valueOf(c.value());
      if (v != null) {
        return v instanceof String s ? "\"" + s + "\"" : v;
      }
      if (c.value().type() instanceof Type.NullType) {
        return "null";
      }
    }
    return null;
  }

  private boolean dominates(BPattern earlier, BPattern later, Type input) {
    if (earlier instanceof BPattern.Any) {
      return true;
    }
    Object k = later instanceof BPattern.Constant lc ? ConstFold.valueOf(lc.value()) : null;
    if (k != null && earlier instanceof BPattern.TypeTest et) {
      BExpr kv = ((BPattern.Constant) later).value();
      Type kt = kv.type() instanceof PrimType p ? a.syms.boxed(p) : kv.type();
      return types().isSubtype(kt, et.type());
    }
    if (k != null
        && earlier instanceof BPattern.Relational rel
        && rel.operandType() instanceof PrimType pt) {
      Object bound = ConstFold.valueOf(rel.value());
      Object v = k instanceof Character ch ? (Object) (int) ch.charValue() : k;
      if (bound != null && (v instanceof Number)) {
        return Boolean.TRUE.equals(ConstFold.binary(rel.op(), ConstFold.convert(v, pt), bound, pt));
      }
    }
    if (earlier instanceof BPattern.TypeTest e) {
      Type lt =
          switch (later) {
            case BPattern.TypeTest l -> l.type();
            case BPattern.Recursive r -> r.type();
            default -> null;
          };
      return lt != null && types().isSubtype(lt, e.type());
    }
    return false;
  }
}

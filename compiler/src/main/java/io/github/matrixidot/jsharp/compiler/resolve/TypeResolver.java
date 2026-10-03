package io.github.matrixidot.jsharp.compiler.resolve;

import io.github.matrixidot.jsharp.compiler.Context;
import io.github.matrixidot.jsharp.compiler.ast.TypeNode;
import io.github.matrixidot.jsharp.compiler.ast.TypeParam;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import io.github.matrixidot.jsharp.compiler.types.Type.PrimType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves syntactic {@link TypeNode}s to semantic {@link Type}s in a {@link TypeScope}, reporting
 * unresolved names with "did you mean" and missing-import suggestions.
 */
public final class TypeResolver {

  /** How a generic class used without type arguments is treated. */
  public enum RawMode {
    /** Missing type arguments are an error (declarations). */
    ERROR,
    /** Missing type arguments mean wildcards: {@code is List} = {@code is List<?>}. */
    WILDCARD,
    /** Missing type arguments are inferred ({@code new ArrayList()} / diamond). */
    INFER
  }

  /** Packages searched for "add an import" hints, in order. */
  private static final List<String> COMMON_PACKAGES =
      List.of(
          "java.util",
          "java.util.function",
          "java.util.stream",
          "java.io",
          "java.nio.file",
          "java.nio.charset",
          "java.time",
          "java.math",
          "java.util.concurrent",
          "java.util.concurrent.atomic",
          "java.util.regex",
          "java.net",
          "java.net.http",
          "jsharp.collections",
          "jsharp.text");

  private final Context ctx;
  private io.github.matrixidot.jsharp.compiler.types.Types types;
  private boolean deferBounds = true;
  private final List<Runnable> pendingBounds = new ArrayList<>();

  public TypeResolver(Context ctx) {
    this.ctx = ctx;
  }

  private io.github.matrixidot.jsharp.compiler.types.Types types() {
    if (types == null) {
      types = new io.github.matrixidot.jsharp.compiler.types.Types(ctx.syms);
    }
    return types;
  }

  /** Runs bound checks deferred while class headers were incomplete; later checks run eagerly. */
  public void flushBoundChecks() {
    deferBounds = false;
    List<Runnable> todo = new ArrayList<>(pendingBounds);
    pendingBounds.clear();
    todo.forEach(Runnable::run);
  }

  /** Reports type arguments that violate their parameter's declared bounds. */
  private void checkBounds(
      ClassType t, SourceFile file, io.github.matrixidot.jsharp.compiler.source.Span span) {
    Runnable check =
        () -> {
          List<TypeVarSymbol> tps = t.sym().typeParams();
          if (tps.size() != t.args().size()) {
            return;
          }
          Map<TypeVarSymbol, Type> m =
              io.github.matrixidot.jsharp.compiler.types.Types.zip(tps, t.args());
          for (int i = 0; i < tps.size(); i++) {
            Type arg = t.args().get(i);
            if (arg instanceof Type.WildcardType || arg.isError()) {
              continue;
            }
            for (Type b : tps.get(i).bounds()) {
              Type bound = io.github.matrixidot.jsharp.compiler.types.Types.subst(b, m);
              if (!types().isSubtype(arg, bound)) {
                ctx.report(
                    Code.TYPE_ARGUMENT_BOUND,
                    file,
                    span,
                    "type argument "
                        + arg.display()
                        + " does not satisfy the bound "
                        + bound.display()
                        + " of "
                        + tps.get(i).name());
                return;
              }
            }
          }
        };
    if (deferBounds) {
      pendingBounds.add(check);
    } else {
      check.run();
    }
  }

  public Type resolve(TypeNode node, TypeScope scope) {
    return resolve(node, scope, false, RawMode.ERROR);
  }

  /** Resolves a return type ({@code void} allowed). */
  public Type resolveReturn(TypeNode node, TypeScope scope) {
    return resolve(node, scope, true, RawMode.ERROR);
  }

  public Type resolve(TypeNode node, TypeScope scope, boolean allowVoid, RawMode raw) {
    SourceFile file = scope.file().unit().file();
    return switch (node) {
      case TypeNode.Primitive p -> {
        PrimType t = prim(p.kind());
        if (t == PrimType.VOID && !allowVoid) {
          ctx.report(Code.INVALID_VOID, file, p.span(), "'void' is only allowed as a return type");
          yield Type.ErrorType.INSTANCE;
        }
        yield t;
      }
      case TypeNode.Nullable n -> {
        Type inner = resolve(n.inner(), scope, false, raw);
        if (inner instanceof PrimType p) {
          yield ctx.syms.boxed(p).withNullness(Nullness.NULLABLE);
        }
        yield inner.withNullness(Nullness.NULLABLE);
      }
      case TypeNode.Array a -> {
        Type elem = resolve(a.element(), scope, false, raw);
        yield elem.isError() ? elem : Type.ArrayType.of(elem);
      }
      case TypeNode.Tuple t -> resolveTuple(t, scope, raw);
      case TypeNode.Wildcard w -> {
        ctx.report(
            Code.NOT_A_TYPE, file, w.span(), "a wildcard is only allowed as a type argument");
        yield Type.ErrorType.INSTANCE;
      }
      case TypeNode.Named n -> resolveNamed(n, scope, raw);
    };
  }

  static PrimType prim(TypeNode.Primitive.Kind k) {
    return switch (k) {
      case BOOLEAN -> PrimType.BOOLEAN;
      case BYTE -> PrimType.BYTE;
      case CHAR -> PrimType.CHAR;
      case SHORT -> PrimType.SHORT;
      case INT -> PrimType.INT;
      case LONG -> PrimType.LONG;
      case FLOAT -> PrimType.FLOAT;
      case DOUBLE -> PrimType.DOUBLE;
      case VOID -> PrimType.VOID;
    };
  }

  /**
   * The qualified name of a public class called {@code simpleName} in one of the commonly used
   * packages ({@code java.util} and friends), for "add 'import ...'" hints; null if none.
   */
  public static String importable(Context ctx, String simpleName) {
    if (simpleName.isEmpty() || !Character.isUpperCase(simpleName.charAt(0))) {
      return null;
    }
    for (String p : COMMON_PACKAGES) {
      ClassSymbol c = ctx.syms.lookup(p.replace('.', '/') + "/" + simpleName);
      if (c != null && c.has(Flags.PUBLIC)) {
        return p + "." + simpleName;
      }
    }
    return null;
  }

  private Type resolveTuple(TypeNode.Tuple t, TypeScope scope, RawMode raw) {
    List<Type> elems = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (TypeNode.Tuple.Element e : t.elements()) {
      Type et = resolve(e.type(), scope, false, raw);
      if (et.isError()) {
        return et;
      }
      elems.add(et);
      names.add(e.name());
    }
    ClassSymbol sym = tupleClass(elems.size());
    if (sym == null) {
      ctx.report(
          Code.NOT_A_TYPE,
          scope.file().unit().file(),
          t.span(),
          elems.size() > 8
              ? "tuples have at most 8 elements"
              : "tuple runtime class not found (is the J# runtime on the class path?)");
      return Type.ErrorType.INSTANCE;
    }
    return new Type.TupleType(elems, names, sym, Nullness.NON_NULL);
  }

  /** The runtime record for an n-tuple, or null. */
  public ClassSymbol tupleClass(int n) {
    return n >= 2 && n <= 8 ? ctx.syms.lookup("jsharp/core/Tuple" + n) : null;
  }

  private Type resolveNamed(TypeNode.Named n, TypeScope scope, RawMode raw) {
    SourceFile file = scope.file().unit().file();
    List<TypeNode.Segment> segs = n.segments();
    TypeNode.Segment first = segs.getFirst();
    if (first.name().equals("<error>")) {
      return Type.ErrorType.INSTANCE;
    }
    if (segs.size() == 1
        && (first.name().equals("var") || first.name().equals("val"))
        && scope.find(first.name()) == null) {
      ctx.report(
          Code.NOT_A_TYPE,
          file,
          n.span(),
          "'" + first.name() + "' can only declare local variables and fields with an initializer");
      return Type.ErrorType.INSTANCE;
    }
    TypeScope.Found found = scope.find(first.name());
    ClassSymbol cls;
    int next;
    switch (found) {
      case TypeScope.FoundVar(TypeVarSymbol tv) -> {
        if (segs.size() > 1 || !first.typeArgs().isEmpty()) {
          ctx.report(
              Code.NOT_A_TYPE,
              file,
              n.span(),
              "type parameter '" + tv.name() + "' cannot have type arguments or members");
          return Type.ErrorType.INSTANCE;
        }
        return tv.asType();
      }
      case TypeScope.FoundAmbiguous(List<ClassSymbol> cands) -> {
        var b =
            ctx.error(
                Code.AMBIGUOUS_TYPE,
                file,
                first.span(),
                "'" + first.name() + "' is ambiguous: " + describe(cands));
        b.help(
                "import the one you mean explicitly, e.g. import "
                    + cands.getFirst().qualifiedName()
                    + ";")
            .report(ctx.diags);
        return Type.ErrorType.INSTANCE;
      }
      case TypeScope.FoundClass(ClassSymbol c) -> {
        cls = c;
        next = 1;
      }
      case null -> {
        // Qualified name: find the longest package prefix that names a class.
        cls = null;
        next = -1;
        for (int i = segs.size() - 1; i >= 1 && cls == null; i--) {
          StringBuilder bn = new StringBuilder();
          for (int j = 0; j < i; j++) {
            bn.append(segs.get(j).name()).append('/');
          }
          bn.append(segs.get(i).name());
          ClassSymbol c = ctx.syms.lookup(bn.toString());
          if (c != null) {
            cls = c;
            next = i + 1;
            for (int j = 0; j < i; j++) {
              if (!segs.get(j).typeArgs().isEmpty()) {
                ctx.report(
                    Code.NOT_A_TYPE,
                    file,
                    segs.get(j).span(),
                    "a package name cannot have type arguments");
              }
            }
          }
        }
        if (cls == null) {
          reportUnresolved(n, scope);
          return Type.ErrorType.INSTANCE;
        }
      }
    }
    TypeNode.Segment seg = segs.get(next - 1);
    for (int i = next; i < segs.size(); i++) {
      TypeNode.Segment s = segs.get(i);
      ClassSymbol m = TypeScope.memberTypeInherited(cls, s.name(), new java.util.HashSet<>());
      if (m == null) {
        var b =
            ctx.error(
                Code.UNRESOLVED_TYPE,
                file,
                s.span(),
                "cannot find type '" + s.name() + "' in " + cls.displayName());
        Set<String> names = new LinkedHashSet<>();
        cls.memberTypes().forEach(mt -> names.add(mt.name()));
        String guess = Suggestions.closest(s.name(), names);
        if (guess != null) {
          b.help("did you mean '" + guess + "'?");
        }
        b.report(ctx.diags);
        return Type.ErrorType.INSTANCE;
      }
      cls = m;
      seg = s;
    }
    if (!checkAccessible(cls, scope, file, seg)) {
      return Type.ErrorType.INSTANCE;
    }
    return applyTypeArgs(cls, seg, scope, raw);
  }

  private boolean checkAccessible(
      ClassSymbol c, TypeScope scope, SourceFile file, TypeNode.Segment seg) {
    ClassSymbol from = scope.enclosingClass();
    if (isAccessible(c, from, scope.file().packageName())) {
      return true;
    }
    ctx.report(
        Code.INACCESSIBLE_TYPE,
        file,
        seg.span(),
        c.kindName()
            + " "
            + c.displayName()
            + " is "
            + Flags.access(c.flags())
            + " and cannot be used here");
    return false;
  }

  /** Accessibility of a class from code in {@code from} (or file level in {@code pkg}). */
  public static boolean isAccessible(ClassSymbol c, ClassSymbol from, String pkg) {
    long f = c.flags();
    if (Flags.is(f, Flags.PUBLIC)) {
      return c.outer() == null || isAccessible(c.outer(), from, pkg);
    }
    if (Flags.is(f, Flags.PRIVATE)) {
      return from != null && from.outermost() == c.outermost();
    }
    if (Flags.is(f, Flags.PROTECTED) && from != null && isSubclass(from, c.outer())) {
      return true;
    }
    return c.packageName().equals(pkg);
  }

  private static boolean isSubclass(ClassSymbol c, ClassSymbol base) {
    for (ClassSymbol x = c; x != null; x = x.superclass() == null ? null : x.superclass().sym()) {
      if (x == base) {
        return true;
      }
    }
    return c.outer() != null && isSubclass(c.outer(), base);
  }

  private Type applyTypeArgs(ClassSymbol cls, TypeNode.Segment seg, TypeScope scope, RawMode raw) {
    SourceFile file = scope.file().unit().file();
    int expected = cls.typeParams().size();
    if (seg.diamond()) {
      if (raw != RawMode.INFER) {
        ctx.report(
            Code.MISSING_TYPE_ARGUMENTS,
            file,
            seg.span(),
            "'<>' is only allowed when creating an object with 'new'");
        return Type.ErrorType.INSTANCE;
      }
      return new ClassType(cls, List.of(), Nullness.NON_NULL);
    }
    if (seg.typeArgs().isEmpty()) {
      if (expected == 0 || raw == RawMode.INFER) {
        return new ClassType(cls, List.of(), Nullness.NON_NULL);
      }
      if (raw == RawMode.WILDCARD) {
        List<Type> wild = new ArrayList<>();
        for (int i = 0; i < expected; i++) {
          wild.add(new Type.WildcardType(Type.WildcardType.Kind.UNBOUNDED, null));
        }
        return new ClassType(cls, wild, Nullness.NON_NULL);
      }
      ctx.error(
              Code.MISSING_TYPE_ARGUMENTS,
              file,
              seg.span(),
              cls.kindName()
                  + " "
                  + cls.displayName()
                  + " needs "
                  + expected
                  + " type argument"
                  + (expected == 1 ? "" : "s"))
          .help("write " + cls.name() + "<" + typeParamNames(cls) + ">")
          .report(ctx.diags);
      return Type.ErrorType.INSTANCE;
    }
    if (seg.typeArgs().size() != expected) {
      ctx.report(
          Code.WRONG_TYPE_ARG_COUNT,
          file,
          seg.span(),
          expected == 0
              ? cls.kindName() + " " + cls.displayName() + " is not generic"
              : cls.kindName()
                  + " "
                  + cls.displayName()
                  + " expects "
                  + expected
                  + " type argument"
                  + (expected == 1 ? "" : "s")
                  + " <"
                  + typeParamNames(cls)
                  + ">, found "
                  + seg.typeArgs().size());
      return Type.ErrorType.INSTANCE;
    }
    List<Type> args = new ArrayList<>();
    for (TypeNode a : seg.typeArgs()) {
      Type t = resolveTypeArg(a, scope);
      if (t.isError()) {
        return t;
      }
      args.add(t);
    }
    ClassType result = new ClassType(cls, args, Nullness.NON_NULL);
    checkBounds(result, file, seg.span());
    return result;
  }

  private static String typeParamNames(ClassSymbol c) {
    StringBuilder sb = new StringBuilder();
    for (TypeVarSymbol tv : c.typeParams()) {
      if (!sb.isEmpty()) {
        sb.append(", ");
      }
      sb.append(tv.name());
    }
    return sb.toString();
  }

  /** A type argument: primitives are boxed ({@code List<int>} means {@code List<Integer>}). */
  public Type resolveTypeArg(TypeNode a, TypeScope scope) {
    if (a instanceof TypeNode.Wildcard w) {
      if (w.bound() == null) {
        return new Type.WildcardType(Type.WildcardType.Kind.UNBOUNDED, null);
      }
      Type b = resolveTypeArg(w.bound(), scope);
      if (b.isError()) {
        return b;
      }
      return new Type.WildcardType(
          w.variance() == TypeParam.Variance.IN
              ? Type.WildcardType.Kind.SUPER
              : Type.WildcardType.Kind.EXTENDS,
          b);
    }
    Type t = resolve(a, scope, false, RawMode.ERROR);
    if (t instanceof PrimType p) {
      return ctx.syms.boxed(p);
    }
    return t;
  }

  private static String describe(List<ClassSymbol> cands) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < cands.size(); i++) {
      if (i > 0) {
        sb.append(i == cands.size() - 1 ? " and " : ", ");
      }
      sb.append(cands.get(i).qualifiedName());
    }
    return sb.toString();
  }

  private void reportUnresolved(TypeNode.Named n, TypeScope scope) {
    SourceFile file = scope.file().unit().file();
    String name = n.segments().getFirst().name();
    boolean qualified = n.segments().size() > 1;
    Diagnostic.Builder b;
    if (qualified) {
      String q = n.qualifiedName();
      String prefix = q.substring(0, q.lastIndexOf('.'));
      b =
          ctx.error(
              Code.UNRESOLVED_TYPE,
              file,
              n.span(),
              ctx.syms.packageExists(prefix)
                  ? "cannot find type '" + n.last().name() + "' in package " + prefix
                  : "cannot find type '" + q + "'");
    } else {
      TypeNode.Segment seg0 = n.segments().getFirst();
      io.github.matrixidot.jsharp.compiler.source.Span nameSpan =
          new io.github.matrixidot.jsharp.compiler.source.Span(
              seg0.span().start(), seg0.span().start() + name.length());
      b = ctx.error(Code.UNRESOLVED_TYPE, file, nameSpan, "cannot find type '" + name + "'");
      String importable = importable(ctx, name);
      if (importable != null) {
        b.help("add 'import " + importable + ";'");
      } else {
        Set<String> names = new LinkedHashSet<>();
        scope.collectNames(names);
        String guess = Suggestions.closest(name, names);
        if (guess != null) {
          b.help("did you mean '" + guess + "'?");
        }
      }
    }
    b.label("not found").report(ctx.diags);
  }

  /** Creates type variable symbols for declared type parameters (bounds resolved separately). */
  public static List<TypeVarSymbol> declare(
      List<TypeParam> params, io.github.matrixidot.jsharp.compiler.symbols.Symbol owner) {
    List<TypeVarSymbol> out = new ArrayList<>();
    for (int i = 0; i < params.size(); i++) {
      TypeParam p = params.get(i);
      out.add(new TypeVarSymbol(p.name(), owner, i, p.variance()));
    }
    return out;
  }

  /** Resolves bounds of declared type parameters in {@code scope} (which must see them). */
  public void resolveBounds(List<TypeParam> params, List<TypeVarSymbol> tvs, TypeScope scope) {
    SourceFile file = scope.file().unit().file();
    Map<String, TypeParam> seen = new java.util.HashMap<>();
    for (int i = 0; i < params.size(); i++) {
      TypeParam p = params.get(i);
      if (seen.put(p.name(), p) != null) {
        ctx.report(
            Code.DUPLICATE_PARAMETER,
            file,
            p.span(),
            "duplicate type parameter '" + p.name() + "'");
      }
      List<Type> bounds = new ArrayList<>();
      for (TypeNode bn : p.bounds()) {
        Type b = resolve(bn, scope);
        if (b instanceof PrimType) {
          ctx.report(Code.INVALID_SUPERTYPE, file, bn.span(), "a primitive type cannot be a bound");
        } else if (!b.isError()) {
          bounds.add(b);
        }
      }
      if (bounds.isEmpty()) {
        bounds.add(ctx.syms.objectType());
      }
      tvs.get(i).setBounds(bounds, bounds.getFirst().erasure());
    }
  }
}

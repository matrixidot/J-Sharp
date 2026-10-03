package io.github.matrixidot.jsharp.compiler.resolve;

import io.github.matrixidot.jsharp.compiler.Context;
import io.github.matrixidot.jsharp.compiler.ast.CompilationUnit;
import io.github.matrixidot.jsharp.compiler.ast.ImportDecl;
import io.github.matrixidot.jsharp.compiler.diag.Code;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.FieldSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Names visible at the top level of one file: its package, imports (single, on-demand, static,
 * aliased) and the implicit imports ({@code java.lang.*}, {@code jsharp.core.*}, static {@code
 * jsharp.core.Prelude.*}, and the stdlib extension containers).
 *
 * <p>Type lookup order: single-type imports and aliases, types of this file, types of the same
 * package, explicit on-demand imports, implicit imports, then the {@code string} alias.
 */
public final class FileScope {
  /** Packages imported on demand into every file. */
  public static final List<String> IMPLICIT_PACKAGES = List.of("java.lang", "jsharp.core");

  /** Classes whose static members (and extension methods) every file imports implicitly. */
  public static final List<String> IMPLICIT_STATIC =
      List.of(
          "jsharp/core/Prelude",
          "jsharp/collections/Sequences",
          "jsharp/collections/LongSums",
          "jsharp/collections/DoubleSums",
          "jsharp/text/Strings");

  /** A static import of a single member name from a class. */
  public record StaticImport(ClassSymbol owner, String member, String alias) {}

  /** Result of a type name lookup: a symbol, an ambiguity, or nothing. */
  public record TypeLookup(ClassSymbol sym, List<ClassSymbol> ambiguous) {
    static final TypeLookup NONE = new TypeLookup(null, List.of());

    public boolean found() {
      return sym != null;
    }

    public boolean isAmbiguous() {
      return ambiguous.size() > 1;
    }
  }

  private final Context ctx;
  private final CompilationUnit unit;
  private final String pkg;
  private final Map<String, ClassSymbol> singleTypes = new LinkedHashMap<>();
  private final Map<String, ClassSymbol> unitTypes = new LinkedHashMap<>();
  private final List<String> onDemandPackages = new ArrayList<>();
  private final List<ClassSymbol> onDemandClasses = new ArrayList<>();
  private final List<StaticImport> staticSingle = new ArrayList<>();
  private final List<ClassSymbol> staticOnDemand = new ArrayList<>();
  private boolean packageModulesAdded;
  private final Map<String, TypeLookup> cache = new HashMap<>();
  private ClassSymbol moduleClass;

  public FileScope(Context ctx, CompilationUnit unit) {
    this.ctx = ctx;
    this.unit = unit;
    this.pkg = unit.packageName();
  }

  public CompilationUnit unit() {
    return unit;
  }

  public String packageName() {
    return pkg;
  }

  public ClassSymbol moduleClass() {
    return moduleClass;
  }

  public void setModuleClass(ClassSymbol c) {
    this.moduleClass = c;
  }

  public void addUnitType(ClassSymbol c) {
    unitTypes.put(c.name(), c);
  }

  public List<StaticImport> staticSingleImports() {
    return staticSingle;
  }

  /**
   * Classes imported with {@code import static C.*}, the implicit static containers, and the module
   * classes of packages imported on demand ({@code import p.*} imports p's top-level functions,
   * values and extensions).
   */
  public List<ClassSymbol> staticOnDemandImports() {
    if (!packageModulesAdded) {
      packageModulesAdded = true;
      for (String p : onDemandPackages) {
        for (ClassSymbol m : ctx.syms.moduleClassesIn(p)) {
          if (!staticOnDemand.contains(m)) {
            staticOnDemand.add(m);
          }
        }
      }
    }
    return staticOnDemand;
  }

  /** Explicit on-demand packages (for extension method discovery). */
  public List<String> onDemandPackages() {
    return onDemandPackages;
  }

  // ------------------------------------------------------------------ import resolution

  /** Resolves all imports, reporting problems. Call after all source types are entered. */
  public void resolveImports() {
    for (ImportDecl imp : unit.imports()) {
      resolveImport(imp);
    }
    for (String p : IMPLICIT_STATIC) {
      ClassSymbol c = ctx.syms.lookup(p);
      if (c != null) {
        staticOnDemand.add(c);
      }
    }
  }

  private void resolveImport(ImportDecl imp) {
    String name = imp.name();
    if (imp.isStatic()) {
      if (imp.wildcard()) {
        ClassSymbol c = resolveQualifiedClass(name, imp);
        if (c != null) {
          staticOnDemand.add(c);
        }
        return;
      }
      int dot = name.lastIndexOf('.');
      if (dot < 0) {
        ctx.report(
            Code.UNRESOLVED_IMPORT,
            unit.file(),
            imp.span(),
            "a static import needs a class and a member name");
        return;
      }
      ClassSymbol owner = resolveQualifiedClass(name.substring(0, dot), imp);
      if (owner == null) {
        return;
      }
      String member = name.substring(dot + 1);
      if (!hasStaticMember(owner, member)) {
        ctx.error(
                Code.UNRESOLVED_IMPORT,
                unit.file(),
                imp.span(),
                "cannot find static member '" + member + "' in " + owner.qualifiedName())
            .report(ctx.diags);
        return;
      }
      staticSingle.add(new StaticImport(owner, member, imp.alias()));
      return;
    }
    if (imp.wildcard()) {
      ClassSymbol asClass = lookupQualifiedClassQuietly(name);
      if (asClass != null) {
        onDemandClasses.add(asClass);
      } else if (ctx.syms.packageExists(name)) {
        onDemandPackages.add(name);
      } else {
        ctx.report(
            Code.UNRESOLVED_PACKAGE,
            unit.file(),
            imp.span(),
            "package " + name + " does not exist");
      }
      return;
    }
    ClassSymbol c = resolveQualifiedClass(name, imp);
    if (c == null) {
      return;
    }
    String key = imp.alias() != null ? imp.alias() : c.name();
    ClassSymbol existing = singleTypes.get(key);
    if (existing != null && existing != c) {
      ctx.error(
              Code.AMBIGUOUS_TYPE,
              unit.file(),
              imp.span(),
              "'" + key + "' is already imported as " + existing.qualifiedName())
          .help("use an alias: import " + name + " as Other" + key + ";")
          .report(ctx.diags);
      return;
    }
    singleTypes.put(key, c);
  }

  private static boolean hasStaticMember(ClassSymbol owner, String member) {
    for (var m : owner.methods(member)) {
      if (m.isStatic()) {
        return true;
      }
    }
    FieldSymbol f = owner.field(member);
    if (f != null && f.isStatic()) {
      return true;
    }
    return owner.memberType(member) != null;
  }

  /** Resolves {@code a.b.C.D} to a class, reporting a precise error when it fails. */
  private ClassSymbol resolveQualifiedClass(String dotted, ImportDecl imp) {
    ClassSymbol c = lookupQualifiedClassQuietly(dotted);
    if (c != null) {
      return c;
    }
    int dot = dotted.lastIndexOf('.');
    String prefix = dot < 0 ? "" : dotted.substring(0, dot);
    String simple = dotted.substring(dot + 1);
    if (dot < 0) {
      ctx.report(Code.UNRESOLVED_IMPORT, unit.file(), imp.span(), "cannot find class " + dotted);
    } else if (ctx.syms.packageExists(prefix)) {
      var b =
          ctx.error(
              Code.UNRESOLVED_IMPORT,
              unit.file(),
              imp.span(),
              "cannot find class " + simple + " in package " + prefix);
      String guess = Suggestions.closest(simple, ctx.syms.classPath().list(prefix, true));
      if (guess != null) {
        b.help("did you mean '" + prefix + "." + guess.replace('$', '.') + "'?");
      }
      b.report(ctx.diags);
    } else if (lookupQualifiedClassQuietly(prefix) != null) {
      ctx.report(
          Code.UNRESOLVED_IMPORT,
          unit.file(),
          imp.span(),
          "cannot find member type " + simple + " in " + prefix);
    } else {
      ctx.report(
          Code.UNRESOLVED_PACKAGE,
          unit.file(),
          imp.span(),
          "package " + prefix + " does not exist");
    }
    return null;
  }

  /**
   * Resolves a dotted name to a class without reporting: the longest package prefix wins, the rest
   * are member types ({@code java.util.Map.Entry}).
   */
  public ClassSymbol lookupQualifiedClassQuietly(String dotted) {
    String[] segs = dotted.split("\\.");
    for (int i = segs.length - 1; i >= 0; i--) {
      StringBuilder bn = new StringBuilder();
      for (int j = 0; j < i; j++) {
        bn.append(segs[j]).append('/');
      }
      bn.append(segs[i]);
      ClassSymbol c = ctx.syms.lookup(bn.toString());
      if (c != null) {
        for (int k = i + 1; k < segs.length && c != null; k++) {
          c = c.memberType(segs[k]);
        }
        if (c != null) {
          return c;
        }
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ type lookup

  /** Looks up a simple type name at file level. */
  public TypeLookup lookupType(String name) {
    TypeLookup r = cache.get(name);
    if (r == null) {
      r = computeLookup(name);
      cache.put(name, r);
    }
    return r;
  }

  private TypeLookup computeLookup(String name) {
    ClassSymbol c = singleTypes.get(name);
    if (c != null) {
      return new TypeLookup(c, List.of(c));
    }
    c = unitTypes.get(name);
    if (c != null) {
      return new TypeLookup(c, List.of(c));
    }
    c = ctx.syms.lookup(binary(pkg, name));
    if (c != null && c.outer() == null) {
      return new TypeLookup(c, List.of(c));
    }
    TypeLookup explicit = onDemand(name, onDemandPackages, onDemandClasses);
    if (explicit.found() || explicit.isAmbiguous()) {
      return explicit;
    }
    TypeLookup implicit = onDemand(name, IMPLICIT_PACKAGES, List.of());
    if (implicit.found() || implicit.isAmbiguous()) {
      return implicit;
    }
    if (name.equals("string")) {
      ClassSymbol s = ctx.syms.lookup("java/lang/String");
      return new TypeLookup(s, List.of(s));
    }
    return TypeLookup.NONE;
  }

  private TypeLookup onDemand(String name, List<String> packages, List<ClassSymbol> classes) {
    Set<ClassSymbol> found = new LinkedHashSet<>();
    for (String p : packages) {
      ClassSymbol c = ctx.syms.lookup(binary(p, name));
      if (c != null && c.outer() == null && isAccessibleFromHere(c)) {
        found.add(c);
      }
    }
    for (ClassSymbol owner : classes) {
      ClassSymbol c = owner.memberType(name);
      if (c != null) {
        found.add(c);
      }
    }
    if (found.isEmpty()) {
      return TypeLookup.NONE;
    }
    List<ClassSymbol> list = List.copyOf(found);
    return new TypeLookup(list.size() == 1 ? list.getFirst() : null, list);
  }

  private boolean isAccessibleFromHere(ClassSymbol c) {
    return c.has(Flags.PUBLIC) || c.packageName().equals(pkg);
  }

  private static String binary(String pkg, String name) {
    return pkg.isEmpty() ? name : pkg.replace('.', '/') + "/" + name;
  }

  /** Candidate simple names for "did you mean" suggestions. */
  public Set<String> candidateTypeNames() {
    Set<String> out = new LinkedHashSet<>();
    out.addAll(singleTypes.keySet());
    out.addAll(unitTypes.keySet());
    List<String> pkgs = new ArrayList<>(onDemandPackages);
    pkgs.addAll(IMPLICIT_PACKAGES);
    pkgs.add(pkg);
    for (String p : pkgs) {
      for (String n : ctx.syms.classPath().list(p, true)) {
        if (!n.contains("$")) {
          out.add(n);
        }
      }
    }
    for (ClassSymbol c : ctx.syms.sourceClasses()) {
      if (c.packageName().equals(pkg) && c.outer() == null) {
        out.add(c.name());
      }
    }
    return out;
  }
}

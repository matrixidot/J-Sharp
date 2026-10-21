package io.github.matrixidot.jsharp.compiler.symbols;

import io.github.matrixidot.jsharp.compiler.classpath.ClassFileLoader;
import io.github.matrixidot.jsharp.compiler.classpath.ClassPath;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import io.github.matrixidot.jsharp.compiler.types.Type.ClassType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-compilation symbol table: all class symbols by binary name (source classes shadow class path
 * classes), known packages, and well-known types.
 */
public final class Symtab {
  private final ClassPath classPath;
  private final ClassFileLoader loader;
  private final Map<String, ClassSymbol> classes = new HashMap<>();

  /**
   * Source classes by package, in the order they were entered: the lookups by package that run for
   * every call need not scan {@link #classes}, which also holds every class path class loaded.
   */
  private final Map<String, Map<String, ClassSymbol>> sourceByPackage = new HashMap<>();

  private final Set<String> sourcePackages = new HashSet<>();
  private final Set<String> javaSources = new HashSet<>();
  private final Map<String, ClassType> wellKnown = new HashMap<>();

  /** A table that loads missing classes from {@code classPath}. */
  public Symtab(ClassPath classPath) {
    this.classPath = classPath;
    this.loader = new ClassFileLoader(this);
  }

  /** The class path classes are loaded from. */
  public ClassPath classPath() {
    return classPath;
  }

  /** Looks up a class by JVM internal name ({@code java/util/Map$Entry}); null if absent. */
  public ClassSymbol lookup(String binaryName) {
    if (classes.containsKey(binaryName)) {
      return classes.get(binaryName);
    }
    byte[] bytes;
    try {
      bytes = classPath.read(binaryName);
    } catch (RuntimeException e) {
      bytes = null;
    }
    ClassSymbol c = null;
    if (bytes != null) {
      c = loader.create(binaryName, bytes);
    }
    classes.put(binaryName, c);
    return c;
  }

  /**
   * The J# module classes (holders of top-level functions) of a package, from source and from
   * non-JDK class path entries, in a deterministic order.
   */
  public List<ClassSymbol> moduleClassesIn(String dottedPkg) {
    TreeMap<String, ClassSymbol> out = new TreeMap<>();
    for (ClassSymbol c : sourceClassesIn(dottedPkg)) {
      if (c.has(Flags.MODULE)) {
        out.put(c.binaryName(), c);
      }
    }
    String prefix = dottedPkg.isEmpty() ? "" : dottedPkg.replace('.', '/') + "/";
    for (String simple : classPath.list(dottedPkg, false)) {
      if (simple.contains("$") || out.containsKey(prefix + simple)) {
        continue;
      }
      ClassSymbol c = lookup(prefix + simple);
      if (c != null && c.has(Flags.MODULE)) {
        out.put(c.binaryName(), c);
      }
    }
    return List.copyOf(out.values());
  }

  /** Registers a class declared in source. */
  public void enterSource(ClassSymbol c) {
    classes.put(c.binaryName(), c);
    sourcePackages.add(c.packageName());
    sourceByPackage
        .computeIfAbsent(c.packageName(), k -> new LinkedHashMap<>())
        .put(c.binaryName(), c);
  }

  /**
   * Registers a class declared in a Java source file of the same compilation (D082). It is not a J#
   * source class: javac compiles it.
   */
  public void enterJavaSource(ClassSymbol c) {
    classes.put(c.binaryName(), c);
    javaSources.add(c.binaryName());
    sourcePackages.add(c.packageName());
  }

  /** True if the source set (J# or Java) already declares this binary name. */
  public boolean isDeclaredInSource(String binaryName) {
    ClassSymbol c = classes.get(binaryName);
    return c != null && (c.isSource() || javaSources.contains(binaryName));
  }

  /** True if the package, or a subpackage, exists in source or on the class path. */
  public boolean packageExists(String dotted) {
    if (dotted.isEmpty() || sourcePackages.contains(dotted)) {
      return true;
    }
    for (String p : sourcePackages) {
      if (p.startsWith(dotted + ".")) {
        return true;
      }
    }
    return classPath.hasPackage(dotted);
  }

  /** All J# source classes, package by package. */
  public List<ClassSymbol> sourceClasses() {
    List<ClassSymbol> out = new ArrayList<>();
    for (String p : sourceByPackage.keySet()) {
      out.addAll(sourceClassesIn(p));
    }
    return out;
  }

  /** The J# source classes of a package (nested ones too), in the order they were entered. */
  public List<ClassSymbol> sourceClassesIn(String dottedPkg) {
    List<ClassSymbol> out = new ArrayList<>();
    for (ClassSymbol c : sourceByPackage.getOrDefault(dottedPkg, Map.of()).values()) {
      // A Java source class of the same name replaces it (enterJavaSource).
      if (classes.get(c.binaryName()) == c && c.isSource()) {
        out.add(c);
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ well-known types

  /** Non-null type of a well-known class, e.g. {@code wellKnown("java/lang/String")}. */
  public ClassType wellKnown(String binaryName) {
    ClassType t = wellKnown.get(binaryName);
    if (t == null) {
      ClassSymbol c = lookup(binaryName);
      if (c == null) {
        throw new IllegalStateException(
            "required class " + binaryName.replace('/', '.') + " not found on the class path");
      }
      t = new ClassType(c, List.of(), Nullness.NON_NULL);
      wellKnown.put(binaryName, t);
    }
    return t;
  }

  /** {@code java.lang.Object}. */
  public ClassType objectType() {
    return wellKnown("java/lang/Object");
  }

  /** The symbol of {@code java.lang.Object}. */
  public ClassSymbol objectSym() {
    return objectType().sym();
  }

  /** {@code java.lang.String}. */
  public ClassType stringType() {
    return wellKnown("java/lang/String");
  }

  /** {@code java.lang.Throwable}. */
  public ClassType throwableType() {
    return wellKnown("java/lang/Throwable");
  }

  /** {@code java.lang.Class<T>}. */
  public ClassType classType(Type arg) {
    return new ClassType(wellKnown("java/lang/Class").sym(), List.of(arg), Nullness.NON_NULL);
  }

  /** The wrapper class type for a primitive ({@code int} -> {@code Integer}). */
  public ClassType boxed(Type.PrimType p) {
    return wellKnown(p.boxBinaryName());
  }

  /** The primitive for a wrapper class, or null. */
  public Type.PrimType unboxedOf(ClassSymbol c) {
    for (Type.PrimType p : Type.PrimType.values()) {
      if (p != Type.PrimType.VOID && p.boxBinaryName().equals(c.binaryName())) {
        return p;
      }
    }
    return null;
  }
}

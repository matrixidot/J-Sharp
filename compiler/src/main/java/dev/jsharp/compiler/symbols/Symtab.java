package dev.jsharp.compiler.symbols;

import dev.jsharp.compiler.classpath.ClassFileLoader;
import dev.jsharp.compiler.classpath.ClassPath;
import dev.jsharp.compiler.types.Nullness;
import dev.jsharp.compiler.types.Type;
import dev.jsharp.compiler.types.Type.ClassType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-compilation symbol table: all class symbols by binary name (source classes shadow class path
 * classes), known packages, and well-known types.
 */
public final class Symtab {
  private final ClassPath classPath;
  private final ClassFileLoader loader;
  private final Map<String, ClassSymbol> classes = new HashMap<>();
  private final Set<String> sourcePackages = new HashSet<>();
  private final Map<String, ClassType> wellKnown = new HashMap<>();

  public Symtab(ClassPath classPath) {
    this.classPath = classPath;
    this.loader = new ClassFileLoader(this);
  }

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
    java.util.TreeMap<String, ClassSymbol> out = new java.util.TreeMap<>();
    for (ClassSymbol c : classes.values()) {
      if (c != null && c.isSource() && c.packageName().equals(dottedPkg) && c.has(Flags.MODULE)) {
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
  }

  /** True if the source set already declares this binary name. */
  public boolean isDeclaredInSource(String binaryName) {
    ClassSymbol c = classes.get(binaryName);
    return c != null && c.isSource();
  }

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

  /** All source classes entered so far. */
  public List<ClassSymbol> sourceClasses() {
    return classes.values().stream().filter(c -> c != null && c.isSource()).toList();
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

  public ClassType objectType() {
    return wellKnown("java/lang/Object");
  }

  public ClassSymbol objectSym() {
    return objectType().sym();
  }

  public ClassType stringType() {
    return wellKnown("java/lang/String");
  }

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

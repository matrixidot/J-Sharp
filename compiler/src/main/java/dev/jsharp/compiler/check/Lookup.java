package dev.jsharp.compiler.check;

import dev.jsharp.compiler.symbols.ClassSymbol;
import dev.jsharp.compiler.symbols.FieldSymbol;
import dev.jsharp.compiler.symbols.Flags;
import dev.jsharp.compiler.symbols.MethodSymbol;
import dev.jsharp.compiler.symbols.PropertySymbol;
import dev.jsharp.compiler.symbols.Symbol;
import dev.jsharp.compiler.symbols.Symtab;
import dev.jsharp.compiler.types.Descriptors;
import dev.jsharp.compiler.types.Type;
import dev.jsharp.compiler.types.Type.ClassType;
import dev.jsharp.compiler.types.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Member lookup through class hierarchies, and access control. */
final class Lookup {
  private final Types types;
  private final Symtab syms;

  Lookup(Types types) {
    this.types = types;
    this.syms = types.syms();
  }

  /** The class symbols whose members a value of {@code site} has, most derived first. */
  List<ClassSymbol> hierarchy(Type site) {
    Set<ClassSymbol> out = new LinkedHashSet<>();
    collect(site, out);
    out.add(syms.objectSym());
    return new ArrayList<>(out);
  }

  private void collect(Type site, Set<ClassSymbol> out) {
    switch (site) {
      case ClassType c -> collectClass(c.sym(), out);
      case Type.TypeVar v -> {
        for (Type b : v.sym().bounds()) {
          collect(b, out);
        }
      }
      case Type.TupleType t -> collectClass(t.erasedSym(), out);
      case Type.ArrayType a -> out.add(syms.objectSym());
      default -> {}
    }
  }

  private void collectClass(ClassSymbol c, Set<ClassSymbol> out) {
    // Breadth-first so that a class's superclass chain precedes its interfaces' members.
    List<ClassSymbol> queue = new ArrayList<>();
    queue.add(c);
    for (int i = 0; i < queue.size(); i++) {
      ClassSymbol x = queue.get(i);
      if (!out.add(x)) {
        continue;
      }
      if (x.superclass() != null) {
        queue.add(x.superclass().sym());
      }
      for (ClassType it : x.interfaces()) {
        queue.add(it.sym());
      }
    }
  }

  /** Finds a field (excluding property backing fields). */
  FieldSymbol findField(Type site, String name) {
    for (ClassSymbol c : hierarchy(site)) {
      FieldSymbol f = c.field(name);
      if (f != null && !f.has(Flags.BACKING_FIELD)) {
        return f;
      }
    }
    return null;
  }

  PropertySymbol findProperty(Type site, String name) {
    for (ClassSymbol c : hierarchy(site)) {
      PropertySymbol p = c.property(name);
      if (p != null) {
        return p;
      }
    }
    return null;
  }

  /**
   * All methods named {@code name} in the hierarchy of {@code site}, minus those overridden by a
   * more derived declaration with the same erased parameters.
   */
  List<MethodSymbol> findMethods(Type site, String name) {
    Map<String, MethodSymbol> bySig = new LinkedHashMap<>();
    for (ClassSymbol c : hierarchy(site)) {
      for (MethodSymbol m : c.methods(name)) {
        if (m.isConstructor()) {
          continue;
        }
        // Compare signatures as seen from the site, so String.compareTo(String) overrides
        // Comparable<String>.compareTo(T).
        ClassType owner = types.asSuper(site, c);
        Map<dev.jsharp.compiler.symbols.TypeVarSymbol, Type> subst =
            owner == null || owner.isRaw() ? Map.of() : types.typeArgMap(owner);
        String key =
            Descriptors.params(
                m.params().stream()
                    .map(
                        p ->
                            p.type() == null
                                ? (Type) Type.ErrorType.INSTANCE
                                : Types.subst(p.type(), subst).erasure())
                    .toList());
        // The hierarchy is ordered most-derived first (superclasses before interfaces), so the
        // first declaration of a signature is the one that overrides the others.
        bySig.putIfAbsent(key, m);
      }
    }
    return new ArrayList<>(bySig.values());
  }

  /** True if any class in the hierarchy declares a method named {@code name}. */
  boolean hasMethodNamed(Type site, String name) {
    for (ClassSymbol c : hierarchy(site)) {
      if (!c.methods(name).isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /** A JavaBeans getter for {@code name}: {@code getName()} or {@code isName()} (boolean). */
  MethodSymbol findGetter(Type site, String name) {
    if (name.isEmpty()) {
      return null;
    }
    String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
    for (String candidate : List.of("get" + cap, "is" + cap)) {
      for (MethodSymbol m : findMethods(site, candidate)) {
        if (m.params().isEmpty()
            && m.typeParams().isEmpty()
            && m.returnType() != Type.PrimType.VOID) {
          if (candidate.startsWith("is")
              && types.primitiveView(m.returnType()) != Type.PrimType.BOOLEAN) {
            continue;
          }
          return m;
        }
      }
    }
    return null;
  }

  /** A JavaBeans setter {@code setName(T)}. */
  MethodSymbol findSetter(Type site, String name) {
    if (name.isEmpty()) {
      return null;
    }
    String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
    for (MethodSymbol m : findMethods(site, "set" + cap)) {
      if (m.params().size() == 1 && m.typeParams().isEmpty()) {
        return m;
      }
    }
    return null;
  }

  /** Record component accessor (Java records or J# records): a no-arg method named like it. */
  MethodSymbol findRecordAccessor(Type site, String name) {
    for (ClassSymbol c : hierarchy(site)) {
      if (c.isRecord()) {
        for (var f : c.recordComponents()) {
          if (f.name().equals(name)) {
            for (MethodSymbol m : c.methods(name)) {
              if (m.params().isEmpty()) {
                return m;
              }
            }
          }
        }
      }
    }
    return null;
  }

  /** Names of members usable for "did you mean" suggestions. */
  Set<String> memberNames(Type site) {
    Set<String> out = new HashSet<>();
    for (ClassSymbol c : hierarchy(site)) {
      for (FieldSymbol f : c.fields()) {
        if (!f.has(Flags.BACKING_FIELD)) {
          out.add(f.name());
        }
      }
      for (PropertySymbol p : c.properties()) {
        out.add(p.name());
      }
      for (MethodSymbol m : c.allMethods()) {
        if (!m.isConstructor()) {
          out.add(m.name());
        }
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ access control

  /**
   * Is {@code member} (declared in {@code owner}) accessible from code in class {@code from}, when
   * accessed through a receiver of type {@code site} (null for static/unqualified access)?
   */
  boolean isAccessible(Symbol member, ClassSymbol owner, Type site, ClassSymbol from) {
    long f = member.flags();
    if (Flags.is(f, Flags.PUBLIC) || (owner.isInterface() && !Flags.is(f, Flags.PRIVATE))) {
      return true;
    }
    if (Flags.is(f, Flags.PRIVATE)) {
      return from != null && from.outermost() == owner.outermost();
    }
    if (from != null && from.packageName().equals(owner.packageName())) {
      return true;
    }
    if (Flags.is(f, Flags.PROTECTED) && from != null) {
      for (ClassSymbol c = from; c != null; c = c.outer()) {
        if (isSubclass(c, owner)) {
          if (member.isStatic()
              || site == null
              || member instanceof MethodSymbol m && m.isConstructor()) {
            return true;
          }
          // JVM protected access: the receiver must be the accessing class or a subclass of it.
          ClassType s = types.asSuper(site, c);
          if (s != null) {
            return true;
          }
        }
      }
    }
    return false;
  }

  boolean isSubclass(ClassSymbol c, ClassSymbol base) {
    for (ClassSymbol x = c; x != null; x = x.superclass() == null ? null : x.superclass().sym()) {
      if (x == base) {
        return true;
      }
    }
    return false;
  }
}

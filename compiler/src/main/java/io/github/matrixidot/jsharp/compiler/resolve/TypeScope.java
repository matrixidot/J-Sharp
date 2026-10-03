package io.github.matrixidot.jsharp.compiler.resolve;

import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.TypeVarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A chain of scopes for resolving simple type names: method type parameters, local classes, class
 * type parameters and member types (including inherited ones), enclosing classes, then the file.
 */
public sealed interface TypeScope {

  /** What a simple name denotes: a type variable, a class, an ambiguity, or nothing (null). */
  sealed interface Found {}

  record FoundVar(TypeVarSymbol sym) implements Found {}

  record FoundClass(ClassSymbol sym) implements Found {}

  record FoundAmbiguous(List<ClassSymbol> candidates) implements Found {}

  /**
   * Looks up a simple type name.
   *
   * @param typeParamsVisible false when searching an enclosing class from a static nested type
   */
  Found find(String name, boolean typeParamsVisible);

  default Found find(String name) {
    return find(name, true);
  }

  FileScope file();

  /** The innermost enclosing class, or null at file level. */
  ClassSymbol enclosingClass();

  /** Adds candidate names for suggestions. */
  void collectNames(Set<String> out);

  /** File-level scope. */
  record FileLevel(FileScope file) implements TypeScope {
    @Override
    public Found find(String name, boolean typeParamsVisible) {
      FileScope.TypeLookup r = file.lookupType(name);
      if (r.isAmbiguous()) {
        return new FoundAmbiguous(r.ambiguous());
      }
      return r.found() ? new FoundClass(r.sym()) : null;
    }

    @Override
    public ClassSymbol enclosingClass() {
      return null;
    }

    @Override
    public void collectNames(Set<String> out) {
      out.addAll(file.candidateTypeNames());
    }
  }

  /**
   * A class body. Type parameters are visible only from the class itself and from non-static
   * contexts (local/anonymous classes); J# named nested types are static.
   */
  record ClassLevel(ClassSymbol cls, TypeScope parent, boolean nestedTypesSeeTypeParams)
      implements TypeScope {
    @Override
    public Found find(String name, boolean typeParamsVisible) {
      if (typeParamsVisible) {
        for (TypeVarSymbol tv : cls.typeParams()) {
          if (tv.name().equals(name)) {
            return new FoundVar(tv);
          }
        }
      }
      ClassSymbol m = memberTypeInherited(cls, name, new HashSet<>());
      if (m != null) {
        return new FoundClass(m);
      }
      return parent.find(name, typeParamsVisible && nestedTypesSeeTypeParams);
    }

    @Override
    public FileScope file() {
      return parent.file();
    }

    @Override
    public ClassSymbol enclosingClass() {
      return cls;
    }

    @Override
    public void collectNames(Set<String> out) {
      for (TypeVarSymbol tv : cls.typeParams()) {
        out.add(tv.name());
      }
      for (ClassSymbol m : cls.memberTypes()) {
        out.add(m.name());
      }
      parent.collectNames(out);
    }
  }

  /** Method (or constructor) type parameters. */
  record MethodLevel(List<TypeVarSymbol> typeParams, TypeScope parent) implements TypeScope {
    @Override
    public Found find(String name, boolean typeParamsVisible) {
      for (TypeVarSymbol tv : typeParams) {
        if (tv.name().equals(name)) {
          return new FoundVar(tv);
        }
      }
      return parent.find(name, typeParamsVisible);
    }

    @Override
    public FileScope file() {
      return parent.file();
    }

    @Override
    public ClassSymbol enclosingClass() {
      return parent.enclosingClass();
    }

    @Override
    public void collectNames(Set<String> out) {
      for (TypeVarSymbol tv : typeParams) {
        out.add(tv.name());
      }
      parent.collectNames(out);
    }
  }

  /** Local classes declared in a block. */
  record LocalLevel(Map<String, ClassSymbol> locals, TypeScope parent) implements TypeScope {
    @Override
    public Found find(String name, boolean typeParamsVisible) {
      ClassSymbol c = locals.get(name);
      return c != null ? new FoundClass(c) : parent.find(name, typeParamsVisible);
    }

    @Override
    public FileScope file() {
      return parent.file();
    }

    @Override
    public ClassSymbol enclosingClass() {
      return parent.enclosingClass();
    }

    @Override
    public void collectNames(Set<String> out) {
      out.addAll(locals.keySet());
      parent.collectNames(out);
    }
  }

  /** Finds a member type declared in {@code c} or inherited from its supertypes. */
  static ClassSymbol memberTypeInherited(ClassSymbol c, String name, Set<ClassSymbol> seen) {
    if (!seen.add(c)) {
      return null;
    }
    ClassSymbol m = c.memberType(name);
    if (m != null) {
      return m;
    }
    Type.ClassType sup = c.superclass();
    if (sup != null) {
      m = memberTypeInherited(sup.sym(), name, seen);
      if (m != null) {
        return m;
      }
    }
    for (Type.ClassType i : c.interfaces()) {
      m = memberTypeInherited(i.sym(), name, seen);
      if (m != null) {
        return m;
      }
    }
    return null;
  }
}

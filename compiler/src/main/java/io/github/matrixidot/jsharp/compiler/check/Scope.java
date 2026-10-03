package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A lexical block scope for local variables and local classes. A scope may be a <em>boundary</em>
 * (lambda body or local/anonymous class body): looking a variable up across it is a capture.
 */
final class Scope {
  enum Boundary {
    NONE,
    LAMBDA,
    CLASS
  }

  final Scope parent;
  final Boundary boundary;
  final Map<String, VarSymbol> vars = new LinkedHashMap<>();
  final Map<String, ClassSymbol> classes = new LinkedHashMap<>();
  final Map<String, LocalFunctions.Fn> functions = new LinkedHashMap<>();

  /** Local functions declared further down this block (for "declared later" hints). */
  final java.util.Set<String> laterFunctions = new java.util.HashSet<>();

  /** For LAMBDA boundaries: the lambda frame collecting captures. */
  final LambdaFrame lambda;

  /** For CLASS boundaries: the local class whose body starts here. */
  final ClassSymbol localClass;

  Scope(Scope parent, Boundary boundary, LambdaFrame lambda, ClassSymbol localClass) {
    this.parent = parent;
    this.boundary = boundary;
    this.lambda = lambda;
    this.localClass = localClass;
  }

  static Scope root() {
    return new Scope(null, Boundary.NONE, null, null);
  }

  Scope child() {
    return new Scope(this, Boundary.NONE, null, null);
  }

  /** Result of a variable lookup: the variable and the boundaries crossed to reach it. */
  record Found(VarSymbol var, java.util.List<Scope> crossed) {}

  Found lookup(String name) {
    java.util.List<Scope> crossed = new java.util.ArrayList<>();
    for (Scope s = this; s != null; s = s.parent) {
      VarSymbol v = s.vars.get(name);
      if (v != null) {
        return new Found(v, crossed);
      }
      if (s.boundary != Boundary.NONE) {
        crossed.add(s);
      }
    }
    return null;
  }

  /** Finds a variable declared in this scope chain up to (not including) a boundary. */
  VarSymbol lookupWithinBoundary(String name) {
    for (Scope s = this; s != null; s = s.parent) {
      VarSymbol v = s.vars.get(name);
      if (v != null) {
        return v;
      }
      if (s.boundary != Boundary.NONE) {
        return null;
      }
    }
    return null;
  }

  ClassSymbol lookupClass(String name) {
    for (Scope s = this; s != null; s = s.parent) {
      ClassSymbol c = s.classes.get(name);
      if (c != null) {
        return c;
      }
    }
    return null;
  }

  /** A local function visible here: not across local class bodies (D083). */
  LocalFunctions.Fn lookupFunction(String name) {
    for (Scope s = this; s != null; s = s.parent) {
      LocalFunctions.Fn f = s.functions.get(name);
      if (f != null) {
        return f;
      }
      if (s.boundary == Boundary.CLASS) {
        return null;
      }
    }
    return null;
  }

  /** Why a local function named {@code name} is not visible here, or null (D083). */
  String hiddenFunctionNote(String name) {
    boolean crossedClass = false;
    for (Scope s = this; s != null; s = s.parent) {
      if (s.functions.containsKey(name)) {
        return crossedClass
            ? "local functions of the enclosing method are not visible inside a local class"
            : null;
      }
      if (s.laterFunctions.contains(name) && !crossedClass) {
        return "'" + name + "' is declared later in this block; move the call after it";
      }
      if (s.boundary == Boundary.CLASS) {
        crossedClass = true;
      }
    }
    return null;
  }

  void collectNames(java.util.Set<String> out) {
    for (Scope s = this; s != null; s = s.parent) {
      out.addAll(s.vars.keySet());
      out.addAll(s.functions.keySet());
    }
  }
}

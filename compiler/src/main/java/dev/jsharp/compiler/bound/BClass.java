package dev.jsharp.compiler.bound;

import dev.jsharp.compiler.source.Span;
import dev.jsharp.compiler.symbols.ClassSymbol;
import dev.jsharp.compiler.symbols.MethodSymbol;
import dev.jsharp.compiler.symbols.VarSymbol;
import java.util.List;

/**
 * A checked class: method bodies plus initializer code, ready for lowering.
 *
 * @param instanceInit field initializers and instance initializer blocks, in source order (run
 *     after the superclass constructor call in every constructor that does not delegate to {@code
 *     this(...)})
 * @param staticInit static field initializers and static blocks, in source order
 * @param nested local and anonymous classes created while checking this class's bodies
 */
public record BClass(
    ClassSymbol sym,
    List<Method> methods,
    List<BStmt> instanceInit,
    List<BStmt> staticInit,
    List<BClass> nested) {

  /**
   * A method body.
   *
   * @param params parameter variables
   * @param body the body, or null for abstract/native/generated methods
   */
  public record Method(MethodSymbol sym, List<VarSymbol> params, BStmt body, Span span) {}
}

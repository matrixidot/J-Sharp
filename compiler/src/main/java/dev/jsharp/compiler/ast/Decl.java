package dev.jsharp.compiler.ast;

import dev.jsharp.compiler.source.Span;
import java.util.List;

/** Declarations (members of types and of compilation units). */
public sealed interface Decl extends Node {

  /** Kinds of type declarations. */
  enum TypeKind {
    CLASS,
    INTERFACE,
    RECORD,
    ENUM;

    public String keyword() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /**
   * A class, interface, record or enum.
   *
   * @param header record components / enum header parameters, or null
   * @param supertypes types after {@code :} (base class first, then interfaces)
   * @param permits explicit {@code permits} list, or null
   * @param enumConstants enum constants (empty for non-enums)
   */
  record TypeDecl(
      TypeKind kind,
      Modifiers modifiers,
      String name,
      Span nameSpan,
      List<TypeParam> typeParams,
      List<Param> header,
      List<TypeNode> supertypes,
      List<TypeNode> permits,
      List<EnumConstant> enumConstants,
      List<Decl> members,
      Span span)
      implements Decl {
    public TypeDecl {
      typeParams = List.copyOf(typeParams);
      header = header == null ? null : List.copyOf(header);
      supertypes = List.copyOf(supertypes);
      permits = permits == null ? null : List.copyOf(permits);
      enumConstants = List.copyOf(enumConstants);
      members = List.copyOf(members);
    }
  }

  /**
   * A method or top-level function.
   *
   * @param returnType declared return type, or null if omitted
   * @param body body, or null for abstract/interface methods
   */
  record Method(
      Modifiers modifiers,
      List<TypeParam> typeParams,
      TypeNode returnType,
      String name,
      Span nameSpan,
      List<Param> params,
      Body body,
      Span span)
      implements Decl {
    public Method {
      typeParams = List.copyOf(typeParams);
      params = List.copyOf(params);
    }
  }

  /** A constructor; {@code params == null} for a record's compact constructor. */
  record Constructor(
      Modifiers modifiers, String name, Span nameSpan, List<Param> params, Body body, Span span)
      implements Decl {
    public Constructor {
      params = params == null ? null : List.copyOf(params);
    }
  }

  /** A field (or top-level value). */
  record Field(
      Modifiers modifiers, LocalKind kind, TypeNode type, List<VarDeclarator> vars, Span span)
      implements Decl {
    public Field {
      vars = List.copyOf(vars);
    }
  }

  /**
   * A property.
   *
   * @param accessors accessor list, or null for an expression-bodied property
   * @param getter the {@code => expr} getter of an expression-bodied property, or null
   * @param initializer {@code = expr} initializer of an auto-property, or null
   */
  record Property(
      Modifiers modifiers,
      TypeNode type,
      String name,
      Span nameSpan,
      List<Accessor> accessors,
      Expr getter,
      Expr initializer,
      Span span)
      implements Decl {
    public Property {
      accessors = accessors == null ? null : List.copyOf(accessors);
    }
  }

  /** {@code static { ... }} or an instance initializer block. */
  record Initializer(boolean isStatic, Stmt.Block body, Span span) implements Decl {}

  /** A statement at the top level of a file (part of the implicit entry point). */
  record TopLevelStmt(Stmt stmt, Span span) implements Decl {}
}

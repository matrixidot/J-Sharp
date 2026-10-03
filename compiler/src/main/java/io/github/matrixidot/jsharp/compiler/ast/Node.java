package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;

/** Root of all syntax tree nodes. Every node knows its source span. */
public sealed interface Node
    permits CompilationUnit,
        Decl,
        Stmt,
        Expr,
        TypeNode,
        Pattern,
        Annotation,
        Param,
        TypeParam,
        Arg,
        Modifiers,
        ImportDecl,
        PackageDecl,
        Accessor,
        EnumConstant,
        CatchClause,
        SwitchArm,
        SwitchSection,
        FieldInit,
        VarDeclarator,
        DeconstructVar,
        Body {
  Span span();
}

package io.github.matrixidot.jsharp.compiler.ast;

import io.github.matrixidot.jsharp.compiler.source.Span;

/**
 * An import: {@code import a.b.C;}, {@code import a.b.*;}, {@code import static a.B.m;}, {@code
 * import a.B as C;}.
 *
 * @param name the dotted name without the trailing {@code .*}
 * @param alias alias name, or null
 */
public record ImportDecl(boolean isStatic, String name, boolean wildcard, String alias, Span span)
    implements Node {}

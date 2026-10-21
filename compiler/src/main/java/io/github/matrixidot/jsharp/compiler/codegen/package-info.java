/**
 * Code generation from the lowered bound tree with the JDK ClassFile API, which also computes the
 * stack maps: {@link io.github.matrixidot.jsharp.compiler.codegen.ClassGen} writes classes and
 * their attributes, {@link io.github.matrixidot.jsharp.compiler.codegen.CodeGen} method bodies.
 * Output is deterministic: the same input gives the same bytes.
 */
package io.github.matrixidot.jsharp.compiler.codegen;

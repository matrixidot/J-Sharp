/**
 * Lowering: rewrites the checked bound tree into the subset code generation understands (accessors,
 * record and enum members, closures, pattern switches, async functions, string interpolation and
 * the other J# constructs Java bytecode has no direct form for). {@link
 * io.github.matrixidot.jsharp.compiler.lower.Lowerer} drives it; pattern switches and patterns are
 * {@code SwitchLowering}'s.
 */
package io.github.matrixidot.jsharp.compiler.lower;

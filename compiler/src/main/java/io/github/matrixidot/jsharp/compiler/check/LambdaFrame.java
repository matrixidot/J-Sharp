package io.github.matrixidot.jsharp.compiler.check;

import io.github.matrixidot.jsharp.compiler.symbols.VarSymbol;
import io.github.matrixidot.jsharp.compiler.types.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Per-lambda bookkeeping: captured variables, use of {@code this}, and returned types. */
final class LambdaFrame {
  final Set<VarSymbol> captures = new LinkedHashSet<>();
  boolean capturesThis;

  /** Types of {@code return} expressions (for inferring the lambda's result type). */
  final List<Type> returnTypes = new ArrayList<>();

  boolean returnsVoid;
}

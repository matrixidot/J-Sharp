package dev.jsharp.compiler.symbols;

import dev.jsharp.compiler.ast.TypeParam;
import dev.jsharp.compiler.types.Type;
import java.util.List;

/** A declared type parameter of a class or method. */
public final class TypeVarSymbol implements Symbol {
  private final String name;
  private final Symbol owner;
  private final int index;
  private final TypeParam.Variance variance;
  private List<Type> bounds = List.of();
  private Type erasedBound;
  private Type lowerBound;
  private boolean captured;

  public TypeVarSymbol(String name, Symbol owner, int index, TypeParam.Variance variance) {
    this.name = name;
    this.owner = owner;
    this.index = index;
    this.variance = variance;
  }

  @Override
  public String name() {
    return name;
  }

  public Symbol owner() {
    return owner;
  }

  public int index() {
    return index;
  }

  public TypeParam.Variance variance() {
    return variance;
  }

  @Override
  public long flags() {
    return 0;
  }

  /** Upper bounds; never empty once resolved (defaults to {@code Object}). */
  public List<Type> bounds() {
    return bounds;
  }

  /**
   * Sets the bounds.
   *
   * @param bounds declared bounds (non-empty)
   * @param erasedBound erasure of the first bound
   */
  public void setBounds(List<Type> bounds, Type erasedBound) {
    this.bounds = List.copyOf(bounds);
    this.erasedBound = erasedBound;
  }

  public Type erasedBound() {
    return erasedBound;
  }

  /** Lower bound of a captured {@code ? super L} wildcard, or null. */
  public Type lowerBound() {
    return lowerBound;
  }

  /** True for a fresh variable created by capture conversion of a wildcard. */
  public boolean isCaptured() {
    return captured;
  }

  /** Creates a capture variable for a wildcard. */
  public static TypeVarSymbol capture(String name, List<Type> upper, Type erasedUpper, Type lower) {
    TypeVarSymbol tv = new TypeVarSymbol(name, null, -1, TypeParam.Variance.INVARIANT);
    tv.setBounds(upper, erasedUpper);
    tv.lowerBound = lower;
    tv.captured = true;
    return tv;
  }

  public Type asType() {
    return new Type.TypeVar(this, dev.jsharp.compiler.types.Nullness.NON_NULL);
  }

  @Override
  public String kindName() {
    return "type parameter";
  }

  @Override
  public String toString() {
    return name;
  }
}

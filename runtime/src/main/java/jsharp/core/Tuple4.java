package jsharp.core;

/** A 4-tuple; J# tuple types {@code (T1, T2, T3, T4)} compile to this record. */
public record Tuple4<T1, T2, T3, T4>(T1 item1, T2 item2, T3 item3, T4 item4) {
  @Override
  public String toString() {
    return "(" + item1 + ", " + item2 + ", " + item3 + ", " + item4 + ")";
  }
}

package jsharp.core;

/** A 3-tuple; J# tuple types {@code (T1, T2, T3)} compile to this record. */
public record Tuple3<T1, T2, T3>(T1 item1, T2 item2, T3 item3) {
  @Override
  public String toString() {
    return "(" + item1 + ", " + item2 + ", " + item3 + ")";
  }
}

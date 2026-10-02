package jsharp.core;

/** A 2-tuple; J# tuple types {@code (T1, T2)} compile to this record. */
public record Tuple2<T1, T2>(T1 item1, T2 item2) {
  @Override
  public String toString() {
    return "(" + item1 + ", " + item2 + ")";
  }
}

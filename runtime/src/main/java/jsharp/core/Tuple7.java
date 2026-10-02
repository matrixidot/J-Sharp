package jsharp.core;

/** A 7-tuple; J# tuple types {@code (T1, T2, T3, T4, T5, T6, T7)} compile to this record. */
public record Tuple7<T1, T2, T3, T4, T5, T6, T7>(
    T1 item1, T2 item2, T3 item3, T4 item4, T5 item5, T6 item6, T7 item7) {
  @Override
  public String toString() {
    return "(" + item1 + ", " + item2 + ", " + item3 + ", " + item4 + ", " + item5 + ", " + item6
        + ", " + item7 + ")";
  }
}

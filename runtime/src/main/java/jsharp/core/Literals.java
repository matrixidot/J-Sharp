package jsharp.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime support for J# collection literals ({@code [a, b, ..xs]} and {@code {k: v}}). The
 * compiler passes the elements as an {@code Object[]} in which {@link #spread} markers stand for
 * the contents of other collections. Results are immutable and keep the literal's order (D080).
 */
public final class Literals {
  private Literals() {}

  /** The contents of {@code items}, spliced into a literal ({@code ..items}). */
  public record Spread(Iterable<?> items) {}

  public static Object spread(Iterable<?> items) {
    return new Spread(items);
  }

  public static Object spread(Object[] items) {
    return new Spread(Arrays.asList(items));
  }

  public static Object spread(int[] items) {
    List<Object> out = new ArrayList<>(items.length);
    for (int x : items) {
      out.add(x);
    }
    return new Spread(out);
  }

  public static Object spread(long[] items) {
    List<Object> out = new ArrayList<>(items.length);
    for (long x : items) {
      out.add(x);
    }
    return new Spread(out);
  }

  public static Object spread(double[] items) {
    List<Object> out = new ArrayList<>(items.length);
    for (double x : items) {
      out.add(x);
    }
    return new Spread(out);
  }

  public static Object spread(boolean[] items) {
    List<Object> out = new ArrayList<>(items.length);
    for (boolean x : items) {
      out.add(x);
    }
    return new Spread(out);
  }

  public static Object spread(char[] items) {
    List<Object> out = new ArrayList<>(items.length);
    for (char x : items) {
      out.add(x);
    }
    return new Spread(out);
  }

  private static void addAll(Collection<Object> out, Object[] items) {
    for (Object x : items) {
      if (x instanceof Spread s) {
        for (Object y : s.items()) {
          out.add(y);
        }
      } else {
        out.add(x);
      }
    }
  }

  /** An immutable list (null elements allowed when the element type is nullable). */
  @SuppressWarnings("unchecked")
  public static <T> List<T> list(Object[] items) {
    boolean plain = true;
    for (Object x : items) {
      if (x == null || x instanceof Spread) {
        plain = false;
        break;
      }
    }
    if (plain) {
      return (List<T>) List.of(items);
    }
    List<Object> out = new ArrayList<>(items.length);
    addAll(out, items);
    return (List<T>) Collections.unmodifiableList(out);
  }

  /** An immutable set in literal order; duplicates collapse. */
  @SuppressWarnings("unchecked")
  public static <T> Set<T> set(Object[] items) {
    Set<Object> out = new LinkedHashSet<>();
    addAll(out, items);
    return (Set<T>) Collections.unmodifiableSet(out);
  }

  /** Adds the elements to {@code target} (a mutable collection created by the literal). */
  @SuppressWarnings("unchecked")
  public static <C extends Collection<?>> C fill(C target, Object[] items) {
    addAll((Collection<Object>) target, items);
    return target;
  }

  /** An immutable map in literal order, from alternating keys and values. */
  @SuppressWarnings("unchecked")
  public static <K, V> Map<K, V> map(Object[] keysAndValues) {
    return (Map<K, V>) Collections.unmodifiableMap(fillMap(new LinkedHashMap<>(), keysAndValues));
  }

  /** Puts alternating keys and values into {@code target}; a repeated key is an error. */
  @SuppressWarnings("unchecked")
  public static <M extends Map<?, ?>> M fillMap(M target, Object[] keysAndValues) {
    Map<Object, Object> m = (Map<Object, Object>) target;
    for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
      Object k = keysAndValues[i];
      if (m.containsKey(k)) {
        throw new IllegalArgumentException("duplicate key in map literal: " + k);
      }
      m.put(k, keysAndValues[i + 1]);
    }
    return target;
  }
}

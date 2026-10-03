package shapes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** Generic Java API with varargs, overloads, static nested types and a listener interface. */
public class Registry<T extends Shape> {
  public interface Listener<S> {
    void added(S shape, int count);
  }

  public static final class Stats {
    public final int count;
    public final double total;

    Stats(int count, double total) {
      this.count = count;
      this.total = total;
    }
  }

  public enum Order {
    ASCENDING,
    DESCENDING
  }

  private final List<T> items = new ArrayList<>();
  private Listener<? super T> listener;
  public static int instances;

  public Registry() {
    instances++;
  }

  public void setListener(Listener<? super T> l) {
    this.listener = l;
  }

  @SafeVarargs
  public final void addAll(T... shapes) {
    for (T s : shapes) {
      add(s);
    }
  }

  public void add(T shape) {
    items.add(shape);
    if (listener != null) {
      listener.added(shape, items.size());
    }
  }

  public List<T> sorted(Order order) {
    List<T> copy = new ArrayList<>(items);
    Collections.sort(copy);
    if (order == Order.DESCENDING) {
      Collections.reverse(copy);
    }
    return copy;
  }

  public List<T> where(Predicate<? super T> p) {
    return items.stream().filter(p).toList();
  }

  public Stats stats() {
    return new Stats(items.size(), items.stream().mapToDouble(Shape::area).sum());
  }

  public String format(int x) {
    return "int " + x;
  }

  public String format(long x) {
    return "long " + x;
  }

  public String format(Object x) {
    return "object " + x;
  }

  public T first() {
    return items.isEmpty() ? null : items.get(0);
  }

  public static void mayThrow(boolean b) throws java.io.IOException {
    if (b) {
      throw new java.io.IOException("checked from Java");
    }
  }
}

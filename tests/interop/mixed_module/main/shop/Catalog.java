package shop;

import java.util.*;
import java.util.function.Function;

/** Java side of the module: uses J# types (Item, Priced, Discount) and is used by J#. */
public final class Catalog {
    public static final int MAX_ITEMS = 100;
    public static final char CURRENCY = '$';
    public static final long BIG = -5L;

    private final Map<String, Item> items = new LinkedHashMap<>();

    public Catalog add(Item... newItems) {
        for (Item i : newItems) items.put(i.name(), i);
        return this;
    }

    public @Nullable Item find(String name) { return items.get(name); }

    public List<Item> all() { return new ArrayList<>(items.values()); }

    public <R> List<R> map(Function<? super Item, ? extends R> f) {
        List<R> out = new ArrayList<>();
        for (Item i : items.values()) out.add(f.apply(i));
        return out;
    }

    public int total(Priced p) { return p.priceCents(); }

    /** Calls a J# top-level function (module class `DiscountsKt`-style holder). */
    public int discounted(String name, Discount d) {
        Item i = items.get(name);
        return i == null ? 0 : d.apply(i.priceCents());
    }

    public enum Category {
        FOOD("food"), TOOLS("tools");
        private final String label;
        Category(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** A generic static nested class. */
    public static final class Pair<A, B> {
        public final A first;
        public final B second;
        public Pair(A first, B second) { this.first = first; this.second = second; }
        public <C> Pair<A, C> withSecond(C c) { return new Pair<>(first, c); }
    }

    /** An inner (non-static) class. */
    public class Cursor {
        private int at;
        public boolean hasNext() { return at < items.size(); }
        public Item next() { return all().get(at++); }
    }

    public Cursor cursor() { return new Cursor(); }

    public interface Visitor<R> {
        R visit(Item item);
        default String describe(Item item) { return "visited " + item.name(); }
    }

    public <R> List<R> accept(Visitor<R> v) {
        List<R> out = new ArrayList<>();
        for (Item i : items.values()) out.add(v.visit(i));
        return out;
    }

    public record Receipt(List<String> lines, int totalCents) {
        public Receipt {
            lines = List.copyOf(lines);
            if (totalCents < 0) throw new IllegalArgumentException("negative total");
        }
        public int count() { return lines.size(); }
    }
}

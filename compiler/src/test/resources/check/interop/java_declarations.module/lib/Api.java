package lib;

import java.util.List;

/** Java declarations whose rules J# must enforce. */
public final class Api {
    public static final int LIMIT = 10;
    public final int fixed = 1;

    private static void hidden() {}
    static void packagePrivate() {}

    public static @Nullable String maybe() { return null; }
    public static String platform() { return "p"; }
    public static int sum(int... xs) { int s = 0; for (int x : xs) s += x; return s; }
    public static <T extends Number> T first(List<T> xs) { return xs.get(0); }

    public sealed interface Animal permits Dog, Cat {}
    public record Dog(String name) implements Animal {}
    public record Cat(String name) implements Animal {}

    public static abstract class Base {
        public abstract int f();
    }

    public interface Shape {
        double area();
        default String describe() { return "shape"; }
    }

    @Deprecated
    public static void old() {}
}

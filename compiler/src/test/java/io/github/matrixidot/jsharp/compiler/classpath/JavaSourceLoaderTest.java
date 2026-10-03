package io.github.matrixidot.jsharp.compiler.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matrixidot.jsharp.compiler.diag.Diagnostics;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symtab;
import io.github.matrixidot.jsharp.compiler.testing.JavaFixtures;
import io.github.matrixidot.jsharp.compiler.testing.SymbolPrinter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Java declarations read from source (D082) must look exactly like the same classes read from
 * javac's class files: the source loader is checked against the class-file loader.
 */
class JavaSourceLoaderTest {
  private static final Map<String, String> SOURCES = new LinkedHashMap<>();

  static {
    SOURCES.put(
        "p/Shapes.java",
        """
        package p;

        import java.util.*;
        import java.util.function.Function;

        public final class Shapes {
            public static final int VERSION = 3;
            public static final String NAME = "shapes";
            protected double scale = 1.0;
            transient int cache;
            private final List<? extends Number> nums = List.of();

            public Shapes() {}
            Shapes(int x) {}

            public sealed interface Shape permits Circle, Square {
                double area();
                default String describe() { return "shape"; }
                static Shape unit() { return new Circle(1); }
            }
            public record Circle(double r) implements Shape {
                public double area() { return Math.PI * r * r; }
            }
            public record Square(double side) implements Shape {
                public Square {
                    if (side < 0) throw new IllegalArgumentException();
                }
                public double area() { return side * side; }
            }
            record Pair<A, B extends Comparable<B>>(A first, List<B> second) {
                Pair(A first) { this(first, List.of()); }
                public A first() { return first; }
            }

            public enum Color {
                RED, GREEN, BLUE;
                Color() {}
                public Color next() { return values()[(ordinal() + 1) % 3]; }
            }
            enum Op {
                ADD { int apply(int a, int b) { return a + b; } },
                SUB { int apply(int a, int b) { return a - b; } };
                abstract int apply(int a, int b);
            }

            public static <T extends Comparable<? super T>> T max(Collection<? extends T> xs) {
                return Collections.max(xs);
            }
            public static String join(String sep, String... parts) { return String.join(sep, parts); }
            public <R> List<R> map(Function<? super String, ? extends R> f, int[][] grid) {
                return List.of();
            }

            public static abstract class Base<T> {
                protected abstract T make();
                public class Inner {
                    public T get() { return make(); }
                }
                public static class Nested implements Comparable<Nested> {
                    public int compareTo(Nested o) { return 0; }
                }
            }

            @FunctionalInterface
            public interface Visitor<R> {
                R visit(Map.Entry<String, Base.Nested> e);
            }

            public @interface Tag {
                String value() default "";
                int[] ids();
            }

            public static class Child extends Base<String> implements Visitor<Integer>, Cloneable {
                protected String make() { return ""; }
                public Integer visit(Map.Entry<String, Nested> e) { return 0; }
            }
        }
        """);
    SOURCES.put(
        "p/Generic.java",
        """
        package p;

        import java.io.Serializable;

        public interface Generic<K extends Serializable & Comparable<K>, V> extends Iterable<V> {
            int SIZE = 4;
            V get(K key);
            <X extends V> X cast(Object o);
        }
        """);
    SOURCES.put(
        "p/Sealed.java",
        """
        package p;

        public sealed abstract class Sealed {
            static final class A extends Sealed {}
            static non-sealed class B extends Sealed {}
        }
        """);
  }

  @Test
  void sourceDeclarationsMatchClassFiles() throws Exception {
    Path out = JavaFixtures.compile(SOURCES, List.of());
    Symtab fromClasses = new Symtab(ClassPath.of(List.of(out)));
    Symtab fromSources = new Symtab(ClassPath.of(List.of()));
    List<SourceFile> files = new ArrayList<>();
    SOURCES.forEach((path, text) -> files.add(new SourceFile(path, text)));
    Diagnostics diags = new Diagnostics(false);
    new JavaSourceLoader(fromSources, diags).load(files);
    assertThat(diags.all()).isEmpty();

    for (String name :
        List.of(
            "p/Shapes",
            "p/Shapes$Shape",
            "p/Shapes$Circle",
            "p/Shapes$Square",
            "p/Shapes$Pair",
            "p/Shapes$Color",
            "p/Shapes$Op",
            "p/Shapes$Base",
            "p/Shapes$Base$Inner",
            "p/Shapes$Base$Nested",
            "p/Shapes$Visitor",
            "p/Shapes$Tag",
            "p/Shapes$Child",
            "p/Generic",
            "p/Sealed",
            "p/Sealed$A",
            "p/Sealed$B")) {
      String expected = dump(fromClasses.lookup(name));
      if (name.equals("p/Shapes$Op")) {
        // javac seals an enum with constant bodies to its anonymous classes, which J# never names.
        expected = expected.replaceAll(" permits[^\\[]*", " ").replace(" sealed", "");
      }
      assertThat(dump(fromSources.lookup(name))).as(name).isEqualTo(expected);
    }
  }

  /**
   * The symbol dump with member lines sorted (class files and sources order members apart) and
   * without parameter names (javac records none for compact constructors; sources have them).
   */
  private static String dump(ClassSymbol c) {
    assertThat(c).isNotNull();
    String[] lines =
        SymbolPrinter.print(List.of(c)).replaceAll(" [A-Za-z_$][\\w$]*(?=[,)])", "").split("\n");
    List<String> members = new ArrayList<>(List.of(lines).subList(1, lines.length));
    members.sort(null);
    return lines[0] + "\n" + String.join("\n", members);
  }
}

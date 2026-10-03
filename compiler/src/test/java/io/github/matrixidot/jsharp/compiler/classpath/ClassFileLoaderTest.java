package io.github.matrixidot.jsharp.compiler.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matrixidot.jsharp.compiler.symbols.ClassSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Flags;
import io.github.matrixidot.jsharp.compiler.symbols.MethodSymbol;
import io.github.matrixidot.jsharp.compiler.symbols.Symtab;
import io.github.matrixidot.jsharp.compiler.testing.JavaFixtures;
import io.github.matrixidot.jsharp.compiler.types.Nullness;
import io.github.matrixidot.jsharp.compiler.types.Type;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClassFileLoaderTest {
  private static Symtab syms;

  @BeforeAll
  static void setUp() throws Exception {
    Path out =
        JavaFixtures.compile(
            fixtures(
                "org/jspecify/annotations/Nullable.java",
                """
                package org.jspecify.annotations;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.RUNTIME)
                public @interface Nullable {}
                """,
                "org/jspecify/annotations/NullMarked.java",
                """
                package org.jspecify.annotations;
                import java.lang.annotation.*;
                @Target({ElementType.TYPE, ElementType.PACKAGE}) @Retention(RetentionPolicy.RUNTIME)
                public @interface NullMarked {}
                """,
                "org/jetbrains/annotations/NotNull.java",
                """
                package org.jetbrains.annotations;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.CLASS)
                public @interface NotNull {}
                """,
                "org/jetbrains/annotations/Nullable.java",
                """
                package org.jetbrains.annotations;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.CLASS)
                public @interface Nullable {}
                """,
                "fix/Api.java",
                """
                package fix;
                import org.jspecify.annotations.Nullable;
                public class Api {
                  public @Nullable String maybe(@Nullable String a, String b) { return a; }
                  @org.jetbrains.annotations.NotNull public String sure(@org.jetbrains.annotations.Nullable Object o) { return ""; }
                  public String plain() { return ""; }
                  public @Nullable String field;
                  public static final int ANSWER = 42;
                  public static final String NAME = "api";
                  public static final boolean FLAG = true;
                  public static int sum(int... xs) { return 0; }
                  public <T extends Comparable<? super T>> T max(java.util.List<? extends T> xs) { return null; }
                  public class Inner<U> { public U get() { return null; } }
                  public static class Nested {}
                }
                """,
                "fix/Marked.java",
                """
                package fix;
                @org.jspecify.annotations.NullMarked
                public class Marked {
                  public String s() { return ""; }
                  public java.util.List<String> list() { return null; }
                }
                """,
                "fix/Shape.java",
                """
                package fix;
                public sealed interface Shape permits Circle, Square {}
                """,
                "fix/Circle.java",
                "package fix; public record Circle(double r) implements Shape {}",
                "fix/Square.java",
                "package fix; public record Square(double side) implements Shape {}",
                "fix/Mode.java",
                "package fix; public enum Mode { ON, OFF }",
                "fix/Ext.java",
                """
                package fix;
                public final class Ext {
                  @jsharp.lang.Extension public static int doubled(int x) { return x * 2; }
                  @jsharp.lang.NoReturn public static RuntimeException fail(String m) { throw new RuntimeException(m); }
                }
                """),
            List.of());
    syms = new Symtab(ClassPath.of(List.of(out)));
  }

  private static Map<String, String> fixtures(String... kv) {
    Map<String, String> m = new java.util.LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  private static MethodSymbol method(String cls, String name) {
    return syms.lookup(cls).methods(name).getFirst();
  }

  @Test
  void jdkGenerics() {
    ClassSymbol list = syms.lookup("java/util/List");
    assertThat(list.isInterface()).isTrue();
    assertThat(list.typeParams()).hasSize(1);
    assertThat(list.typeParams().getFirst().name()).isEqualTo("E");
    MethodSymbol get = list.methods("get").getFirst();
    assertThat(get.returnType()).isInstanceOf(Type.TypeVar.class);
    assertThat(get.params().getFirst().type()).isEqualTo(Type.PrimType.INT);
    ClassSymbol e = syms.lookup("java/lang/Enum");
    Type bound = e.typeParams().getFirst().bounds().getFirst();
    assertThat(bound.display()).isEqualTo("Enum<E>");
  }

  @Test
  void typeUseAndDeclarationNullness() {
    MethodSymbol maybe = method("fix/Api", "maybe");
    assertThat(maybe.returnType().nullness()).isEqualTo(Nullness.NULLABLE);
    assertThat(maybe.params().get(0).type().nullness()).isEqualTo(Nullness.NULLABLE);
    assertThat(maybe.params().get(1).type().nullness()).isEqualTo(Nullness.PLATFORM);
    MethodSymbol sure = method("fix/Api", "sure");
    assertThat(sure.returnType().nullness()).isEqualTo(Nullness.NON_NULL);
    assertThat(sure.params().get(0).type().nullness()).isEqualTo(Nullness.NULLABLE);
    assertThat(method("fix/Api", "plain").returnType().nullness()).isEqualTo(Nullness.PLATFORM);
    assertThat(syms.lookup("fix/Api").field("field").type().nullness())
        .isEqualTo(Nullness.NULLABLE);
  }

  @Test
  void nullMarkedMakesUnannotatedTypesNonNull() {
    assertThat(method("fix/Marked", "s").returnType().nullness()).isEqualTo(Nullness.NON_NULL);
    assertThat(method("fix/Marked", "list").returnType().nullness()).isEqualTo(Nullness.NON_NULL);
  }

  @Test
  void parameterNamesConstantsAndVarargs() {
    MethodSymbol maybe = method("fix/Api", "maybe");
    assertThat(maybe.params().get(0).name()).isEqualTo("a");
    ClassSymbol api = syms.lookup("fix/Api");
    assertThat(api.field("ANSWER").constantValue()).isEqualTo(42);
    assertThat(api.field("NAME").constantValue()).isEqualTo("api");
    assertThat(api.field("FLAG").constantValue()).isEqualTo(true);
    MethodSymbol sum = method("fix/Api", "sum");
    assertThat(sum.isVarargs()).isTrue();
    assertThat(sum.params().getFirst().isVarargs()).isTrue();
  }

  @Test
  void genericMethodWithWildcards() {
    MethodSymbol max = method("fix/Api", "max");
    assertThat(max.typeParams()).hasSize(1);
    assertThat(max.typeParams().getFirst().bounds().getFirst().display())
        .isEqualTo("Comparable<in T>");
    assertThat(max.params().getFirst().type().display()).startsWith("List<out T>");
  }

  @Test
  void nestedAndInnerClasses() {
    ClassSymbol api = syms.lookup("fix/Api");
    ClassSymbol nested = api.memberType("Nested");
    assertThat(nested).isNotNull();
    assertThat(nested.has(Flags.STATIC)).isTrue();
    assertThat(nested.qualifiedName()).isEqualTo("fix.Api.Nested");
    ClassSymbol inner = api.memberType("Inner");
    assertThat(inner.has(Flags.STATIC)).isFalse();
    assertThat(inner.typeParams()).hasSize(1);
  }

  @Test
  void sealedRecordsAndEnums() {
    ClassSymbol shape = syms.lookup("fix/Shape");
    assertThat(shape.has(Flags.SEALED)).isTrue();
    assertThat(shape.permitted()).extracting(ClassSymbol::name).containsExactly("Circle", "Square");
    ClassSymbol circle = syms.lookup("fix/Circle");
    assertThat(circle.isRecord()).isTrue();
    assertThat(circle.recordComponents()).extracting(f -> f.name()).containsExactly("r");
    ClassSymbol mode = syms.lookup("fix/Mode");
    assertThat(mode.isEnum()).isTrue();
    assertThat(mode.enumConstants()).extracting(f -> f.name()).containsExactly("ON", "OFF");
  }

  @Test
  void jsharpMetadataAnnotations() {
    assertThat(method("fix/Ext", "doubled").isExtension()).isTrue();
    assertThat(method("fix/Ext", "fail").returnType()).isEqualTo(Type.NeverType.INSTANCE);
  }

  @Test
  void missingClassesAreNull() {
    assertThat(syms.lookup("no/such/Thing")).isNull();
    assertThat(syms.packageExists("java.util")).isTrue();
    assertThat(syms.packageExists("no.such")).isFalse();
  }
}

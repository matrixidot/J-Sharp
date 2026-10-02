package dev.jsharp.compiler.classpath;

import dev.jsharp.compiler.types.Nullness;
import java.util.Set;

/** Recognizes nullness annotations from common annotation libraries by class descriptor. */
final class NullnessAnnotations {
  private NullnessAnnotations() {}

  private static final Set<String> PACKAGES =
      Set.of(
          "org/jspecify/annotations/",
          "org/jetbrains/annotations/",
          "javax/annotation/",
          "jakarta/annotation/",
          "androidx/annotation/",
          "android/support/annotation/",
          "android/annotation/",
          "edu/umd/cs/findbugs/annotations/",
          "org/checkerframework/checker/nullness/qual/",
          "org/eclipse/jdt/annotation/",
          "lombok/",
          "io/micronaut/core/annotation/",
          "org/springframework/lang/",
          "reactor/util/annotation/");

  /** Returns the nullness an annotation descriptor implies, or null if it is unrelated. */
  static Nullness classify(String descriptor) {
    if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) {
      return null;
    }
    String internal = descriptor.substring(1, descriptor.length() - 1);
    int slash = internal.lastIndexOf('/');
    String pkg = internal.substring(0, slash + 1);
    String simple = internal.substring(slash + 1);
    if (!PACKAGES.contains(pkg)) {
      return null;
    }
    return switch (simple) {
      case "Nullable", "CheckForNull", "NullableDecl", "NullableType" -> Nullness.NULLABLE;
      case "NonNull", "NotNull", "Nonnull", "NonNullDecl", "NonNullType" -> Nullness.NON_NULL;
      default -> null;
    };
  }

  /** True for {@code @NullMarked}-style annotations that make unannotated types non-null. */
  static boolean isNullMarked(String descriptor) {
    return descriptor.equals("Lorg/jspecify/annotations/NullMarked;")
        || descriptor.equals("Lorg/eclipse/jdt/annotation/NonNullByDefault;")
        || descriptor.equals("Ljavax/annotation/ParametersAreNonnullByDefault;");
  }
}

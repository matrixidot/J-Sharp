package io.github.matrixidot.jsharp.compiler.driver;

import io.github.matrixidot.jsharp.compiler.LanguageInfo;
import java.nio.file.Path;
import java.util.List;

/**
 * Options for one compilation.
 *
 * @param classPath user class path entries (directories and jars); the JDK is always included
 * @param outputDir directory for class files, or null to keep them in memory
 * @param warningsAsErrors treat warnings as errors
 * @param strictPlatformNullness warn on member access through Java platform types (spec 3.2)
 * @param emitDebugInfo emit LineNumberTable/LocalVariableTable
 * @param release the Java release to compile for, like javac's {@code --release}: the class file
 *     version, and the JDK API the program sees (D108); see {@link #releaseProblem(int)}
 */
public record CompilerOptions(
    List<Path> classPath,
    Path outputDir,
    boolean warningsAsErrors,
    boolean strictPlatformNullness,
    boolean emitDebugInfo,
    int release) {

  public CompilerOptions {
    classPath = List.copyOf(classPath);
  }

  /** The default release: J#'s own, or the running Java's if that is older. */
  public static int defaultRelease() {
    return Math.min(LanguageInfo.JAVA_RELEASE, Runtime.version().feature());
  }

  /**
   * Why {@code release} cannot be compiled for, or null if it can: from {@link
   * LanguageInfo#MIN_JAVA_RELEASE} up to the running Java (whose API newer releases would need).
   */
  public static String releaseProblem(int release) {
    int newest = Runtime.version().feature();
    if (release < LanguageInfo.MIN_JAVA_RELEASE) {
      return "J# compiles for Java "
          + LanguageInfo.MIN_JAVA_RELEASE
          + " or newer, not Java "
          + release;
    }
    if (release > newest) {
      return "compiling for Java "
          + release
          + " needs Java "
          + release
          + "; this is Java "
          + newest;
    }
    return null;
  }

  public static CompilerOptions defaults() {
    return new CompilerOptions(List.of(), null, false, false, true, defaultRelease());
  }

  public CompilerOptions withClassPath(List<Path> cp) {
    return new CompilerOptions(
        cp, outputDir, warningsAsErrors, strictPlatformNullness, emitDebugInfo, release);
  }

  public CompilerOptions withOutputDir(Path dir) {
    return new CompilerOptions(
        classPath, dir, warningsAsErrors, strictPlatformNullness, emitDebugInfo, release);
  }

  public CompilerOptions withWarningsAsErrors(boolean on) {
    return new CompilerOptions(
        classPath, outputDir, on, strictPlatformNullness, emitDebugInfo, release);
  }

  public CompilerOptions withStrictPlatformNullness(boolean on) {
    return new CompilerOptions(classPath, outputDir, warningsAsErrors, on, emitDebugInfo, release);
  }

  public CompilerOptions withRelease(int r) {
    return new CompilerOptions(
        classPath, outputDir, warningsAsErrors, strictPlatformNullness, emitDebugInfo, r);
  }
}

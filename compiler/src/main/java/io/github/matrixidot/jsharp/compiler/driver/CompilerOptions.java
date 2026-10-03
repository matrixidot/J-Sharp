package io.github.matrixidot.jsharp.compiler.driver;

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
 */
public record CompilerOptions(
    List<Path> classPath,
    Path outputDir,
    boolean warningsAsErrors,
    boolean strictPlatformNullness,
    boolean emitDebugInfo) {

  public CompilerOptions {
    classPath = List.copyOf(classPath);
  }

  public static CompilerOptions defaults() {
    return new CompilerOptions(List.of(), null, false, false, true);
  }

  public CompilerOptions withClassPath(List<Path> cp) {
    return new CompilerOptions(
        cp, outputDir, warningsAsErrors, strictPlatformNullness, emitDebugInfo);
  }

  public CompilerOptions withOutputDir(Path dir) {
    return new CompilerOptions(
        classPath, dir, warningsAsErrors, strictPlatformNullness, emitDebugInfo);
  }
}

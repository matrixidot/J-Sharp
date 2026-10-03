package io.github.matrixidot.jsharp.compiler.testing;

import io.github.matrixidot.jsharp.compiler.classpath.ClassPath;
import io.github.matrixidot.jsharp.compiler.driver.Compilation;
import io.github.matrixidot.jsharp.compiler.driver.CompilerOptions;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import java.util.List;

/** Shared class path for compiler tests (opening the JDK image once). */
public final class TestCompiler {
  private static ClassPath shared;

  private TestCompiler() {}

  public static synchronized ClassPath classPath() {
    if (shared == null) {
      shared = Compilation.openClassPath(CompilerOptions.defaults());
    }
    return shared;
  }

  public static Compilation analyze(List<SourceFile> files) {
    Compilation c = new Compilation(files, CompilerOptions.defaults(), classPath());
    c.analyze();
    return c;
  }

  public static Compilation analyze(String name, String source) {
    return analyze(List.of(new SourceFile(name, source)));
  }
}

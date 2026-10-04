package io.github.matrixidot.jsharp.intellij;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Where the plugin's bundled pieces live, and how to start the bundled J# CLI. */
final class JSharpPlugin {
  static final String ID = "io.github.matrixidot.jsharp";
  static final String EXTENSION = "jsharp";
  static final String MAIN_CLASS = "io.github.matrixidot.jsharp.cli.Main";

  private JSharpPlugin() {}

  /** The plugin's installation directory: {@code <plugin>/lib/<this jar>} is two levels down. */
  static Path home() {
    String jar = PathManager.getJarPathForClass(JSharpPlugin.class);
    if (jar == null) {
      throw new IllegalStateException("cannot locate the J# plugin");
    }
    return Path.of(jar).getParent().getParent();
  }

  static boolean isJSharp(VirtualFile file) {
    return file != null && EXTENSION.equals(file.getExtension());
  }

  /**
   * {@code java -cp <plugin>/server/lib/* Main <args>} on the IDE's own Java runtime (J# needs
   * Java 25, which current JetBrains IDEs ship), so nothing else has to be installed.
   */
  static GeneralCommandLine cli(List<String> args) {
    Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
    List<String> command = new ArrayList<>();
    command.add(java.toString());
    command.add("-cp");
    command.add(home().resolve("server").resolve("lib").resolve("*").toString());
    command.add(MAIN_CLASS);
    command.addAll(args);
    return new GeneralCommandLine(command).withCharset(StandardCharsets.UTF_8);
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("win");
  }
}

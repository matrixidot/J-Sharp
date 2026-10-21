package io.github.matrixidot.jsharp.compiler;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Single source of truth for the language's names. The language is written "J#" in prose; the ASCII
 * identifier {@code jsharp} is used for files, packages and commands.
 */
public final class LanguageInfo {
  /** Display name used in prose and diagnostics. */
  public static final String NAME = "J#";

  /** ASCII identifier used for the CLI, packages and file names. */
  public static final String ID = "jsharp";

  /** Source file extension, including the leading dot. */
  public static final String FILE_EXTENSION = "." + ID;

  /** Root package of the runtime / standard library. */
  public static final String STDLIB_PACKAGE = ID;

  /** Suffix of the synthetic class holding a file's top-level functions. */
  public static final String MODULE_CLASS_SUFFIX = "Module";

  private static final Properties BUILD = buildProperties();

  /** Compiler version, injected from the build. */
  public static final String VERSION = BUILD.getProperty("version", "unknown");

  /**
   * The Java release J# targets: generated class files have its version, and projects created by
   * {@code jsharp new} use its toolchain. Injected from the build ({@code javaVersion}); a copy not
   * built by Gradle uses the running Java.
   */
  public static final int JAVA_RELEASE = javaRelease();

  private LanguageInfo() {}

  private static Properties buildProperties() {
    Properties p = new Properties();
    try (InputStream in = LanguageInfo.class.getResourceAsStream("jsharp-version.properties")) {
      if (in != null) {
        p.load(in);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return p;
  }

  private static int javaRelease() {
    try {
      return Integer.parseInt(BUILD.getProperty("javaRelease", ""));
    } catch (NumberFormatException e) {
      return Runtime.version().feature();
    }
  }
}

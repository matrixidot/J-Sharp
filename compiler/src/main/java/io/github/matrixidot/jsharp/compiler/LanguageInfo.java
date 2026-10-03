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

  /** Compiler version, injected from the build. */
  public static final String VERSION = loadVersion();

  private LanguageInfo() {}

  private static String loadVersion() {
    try (InputStream in = LanguageInfo.class.getResourceAsStream("jsharp-version.properties")) {
      if (in == null) {
        return "unknown";
      }
      Properties p = new Properties();
      p.load(in);
      return p.getProperty("version", "unknown");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

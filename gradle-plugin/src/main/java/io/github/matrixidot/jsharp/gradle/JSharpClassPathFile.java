package io.github.matrixidot.jsharp.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Writes a source set's compile class path, one absolute path per line, to {@code
 * build/jsharp/<set>.classpath}, after a {@code #release=<n>} line with the Java release it
 * compiles for. The language server reads it, so editors know the project's libraries (D095) and
 * Java version (D108).
 */
@DisableCachingByDefault(because = "writing a short list of paths is faster than caching it")
public abstract class JSharpClassPathFile extends DefaultTask {
  @InputFiles
  @PathSensitive(PathSensitivity.ABSOLUTE)
  public abstract ConfigurableFileCollection getClasspath();

  @Input
  public abstract Property<Integer> getRelease();

  @OutputFile
  public abstract RegularFileProperty getOutputFile();

  @TaskAction
  public void write() {
    write(getRelease().get(), getClasspath().getFiles(), getOutputFile().get().getAsFile());
  }

  /** Writes the release and {@code classPath} to {@code listing}, one absolute path per line. */
  static void write(int release, Iterable<File> classPath, File listing) {
    StringBuilder text = new StringBuilder("#release=").append(release).append('\n');
    for (File f : classPath) {
      text.append(f.getAbsolutePath()).append('\n');
    }
    try {
      Files.createDirectories(listing.toPath().getParent());
      Files.writeString(listing.toPath(), text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

package io.github.matrixidot.jsharp.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Writes a source set's compile class path, one absolute path per line, to {@code
 * build/jsharp/<set>.classpath}. The language server reads it, so editors know the project's
 * libraries (D095).
 */
@DisableCachingByDefault(because = "writing a short list of paths is faster than caching it")
public abstract class JSharpClassPathFile extends DefaultTask {
  @InputFiles
  @PathSensitive(PathSensitivity.ABSOLUTE)
  public abstract ConfigurableFileCollection getClasspath();

  @OutputFile
  public abstract RegularFileProperty getOutputFile();

  @TaskAction
  public void write() {
    String text =
        getClasspath().getFiles().stream()
            .map(File::getAbsolutePath)
            .collect(Collectors.joining("\n", "", "\n"));
    try {
      Files.writeString(getOutputFile().get().getAsFile().toPath(), text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

package io.github.matrixidot.jsharp.gradle;

import java.io.File;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Fails the build for {@code .jsharp} files under {@code src/<set>/} that no J# source directory
 * contains (such as {@code src/main/kotlin}): they would not be compiled, and the build would pass
 * without them (D097). Skipped when there are none.
 */
@DisableCachingByDefault(because = "a check with no outputs")
public abstract class JSharpSourceCheck extends DefaultTask {
  @InputFiles
  @SkipWhenEmpty
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getStraySources();

  /** Where J# sources belong, for the message: {@code src/main/jsharp}. */
  @Input
  public abstract Property<String> getExpectedDirectory();

  @TaskAction
  public void check() {
    String files =
        getStraySources().getFiles().stream()
            .map(File::getPath)
            .sorted()
            .collect(Collectors.joining("\n  "));
    throw new GradleException(
        "these J# files are not in a J# source directory, so they would not be compiled:\n  "
            + files
            + "\nmove them to "
            + getExpectedDirectory().get()
            + " (keeping their package folders)");
  }
}

package io.github.matrixidot.jsharp.gradle;

import io.github.matrixidot.jsharp.compiler.diag.Diagnostic;
import io.github.matrixidot.jsharp.compiler.diag.DiagnosticRenderer;
import io.github.matrixidot.jsharp.compiler.diag.Severity;
import io.github.matrixidot.jsharp.compiler.driver.Compilation;
import io.github.matrixidot.jsharp.compiler.driver.CompilerOptions;
import io.github.matrixidot.jsharp.compiler.source.SourceFile;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;

/** Compiles J# sources to class files with the J# compiler (in the Gradle process). */
@CacheableTask
public abstract class JSharpCompile extends DefaultTask {
  /** The source directories (all {@code .jsharp} files below them are compiled together). */
  @InputFiles
  @SkipWhenEmpty
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getSource();

  /**
   * Java source directories of the same source set. J# reads their declarations, so J# code can use
   * the module's Java classes; {@code compileJava} compiles them afterwards (D082).
   */
  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getJavaSource();

  /** Jars and class directories the sources compile against. */
  @Classpath
  public abstract ConfigurableFileCollection getClasspath();

  @OutputDirectory
  public abstract DirectoryProperty getDestinationDirectory();

  /** Treat warnings as errors (default false). */
  @Input
  public abstract Property<Boolean> getWarningsAsErrors();

  @TaskAction
  public void compile() {
    Path out = getDestinationDirectory().get().getAsFile().toPath();
    deleteContents(out);
    List<SourceFile> files = new ArrayList<>();
    for (File root : getSource().getFiles()) {
      collect(root.toPath(), ".jsharp", files);
    }
    if (files.isEmpty()) {
      return;
    }
    int jsharpFiles = files.size();
    for (File root : getJavaSource().getFiles()) {
      collect(root.toPath(), ".java", files);
    }
    List<Path> cp = new ArrayList<>();
    for (File f : getClasspath().getFiles()) {
      if (f.exists()) {
        cp.add(f.toPath());
      }
    }
    CompilerOptions options =
        new CompilerOptions(cp, out, getWarningsAsErrors().get(), false, true);
    Compilation comp = new Compilation(files, options, null).javaDeclarationsOnly();
    comp.compile();
    comp.close();
    List<Diagnostic> ds = comp.diagnostics().sorted();
    if (!ds.isEmpty()) {
      String rendered = new DiagnosticRenderer(false).renderAll(ds);
      if (ds.stream().anyMatch(d -> d.severity() == Severity.ERROR)) {
        getLogger().error(rendered);
      } else {
        getLogger().warn(rendered);
      }
    }
    if (comp.diagnostics().hasErrors()) {
      throw new GradleException("J# compilation failed; see the errors above.");
    }
    getLogger().info("J#: compiled {} files into {}", jsharpFiles, out);
  }

  private static void collect(Path root, String extension, List<SourceFile> out) {
    if (!Files.isDirectory(root)) {
      return;
    }
    try (Stream<Path> s = Files.walk(root)) {
      for (Path p : s.filter(x -> x.toString().endsWith(extension)).sorted().toList()) {
        // Paths relative to the project read well in diagnostics.
        out.add(new SourceFile(p.toString(), Files.readString(p)));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteContents(Path dir) {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (Stream<Path> s = Files.walk(dir)) {
      for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
        if (!p.equals(dir)) {
          Files.delete(p);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

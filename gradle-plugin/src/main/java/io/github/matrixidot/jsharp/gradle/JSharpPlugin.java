package io.github.matrixidot.jsharp.gradle;

import io.github.matrixidot.jsharp.compiler.driver.RuntimeLocator;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

/**
 * {@code plugins { id("io.github.matrixidot.jsharp") }}: applies the Java plugin and, for every
 * source set, compiles {@code src/<name>/jsharp} with the J# compiler before Java. The J# classes
 * join the source set's output (so tests, jars, {@code run} and Java code in the same source set
 * see them), and the J# runtime library is added to {@code implementation}.
 *
 * <p>J# compiles first. It reads the declarations of the source set's Java sources, so the two
 * languages can use each other within one module; {@code compileJava} then compiles the Java
 * sources against the J# classes (D082).
 */
public class JSharpPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    project.getPluginManager().apply(JavaPlugin.class);
    JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
    List<Path> runtime = RuntimeLocator.findAll();
    if (!runtime.isEmpty()) {
      project
          .getDependencies()
          .add(
              JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
              project.files(runtime.stream().map(Path::toFile).toList()));
    }
    java.getSourceSets().all(sourceSet -> configure(project, sourceSet));
  }

  private static void configure(Project project, SourceSet sourceSet) {
    String name = sourceSet.getName();
    SourceDirectorySet jsharp =
        project.getObjects().sourceDirectorySet("jsharp", sourceSet.getName() + " J# source");
    jsharp.srcDir("src/" + name + "/jsharp");
    jsharp.getFilter().include("**/*.jsharp");
    sourceSet.getExtensions().add(SourceDirectorySet.class, "jsharp", jsharp);
    sourceSet.getAllSource().source(jsharp);

    String taskName = sourceSet.getTaskName("compile", "JSharp");
    TaskProvider<JSharpCompile> compile =
        project
            .getTasks()
            .register(
                taskName,
                JSharpCompile.class,
                t -> {
                  t.setDescription("Compiles the " + name + " J# sources.");
                  t.setGroup("build");
                  t.getSource().from(jsharp.getSrcDirs());
                  t.getJavaSource().from(sourceSet.getJava().getSrcDirs());
                  t.getWarningsAsErrors().convention(false);
                  t.getClasspath().from(sourceSet.getCompileClasspath());
                  t.getDestinationDirectory()
                      .convention(
                          project.getLayout().getBuildDirectory().dir("classes/jsharp/" + name));
                });
    jsharp
        .getDestinationDirectory()
        .convention(compile.flatMap(JSharpCompile::getDestinationDirectory));

    // The J# classes are part of the source set's classes: tests, jars and run see them.
    ((ConfigurableFileCollection) sourceSet.getOutput().getClassesDirs())
        .from(compile.flatMap(JSharpCompile::getDestinationDirectory));
    // Java in the same source set can call J#.
    project
        .getTasks()
        .named(
            sourceSet.getCompileJavaTaskName(),
            JavaCompile.class,
            javac ->
                javac.setClasspath(
                    javac
                        .getClasspath()
                        .plus(
                            project.files(
                                compile.flatMap(JSharpCompile::getDestinationDirectory)))));
    project.getTasks().named(sourceSet.getClassesTaskName(), t -> t.dependsOn(compile));
  }
}

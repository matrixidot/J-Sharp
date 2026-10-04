package io.github.matrixidot.jsharp.gradle;

import io.github.matrixidot.jsharp.compiler.driver.RuntimeLocator;
import java.io.File;
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
 *
 * <p>Building also writes {@code build/jsharp/<set>.classpath}, the source set's libraries, which
 * the language server uses for files under {@code src/<set>/jsharp} (D095).
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
    markSourceFoldersForIntelliJ(project, java);
  }

  /**
   * IntelliJ's Gradle import does not know custom source directories, so src/main/jsharp would be a
   * plain folder (no New | Package, no source root). The {@code idea} plugin's model is what the
   * import reads: the J# directories are added to it (D097).
   */
  private static void markSourceFoldersForIntelliJ(Project project, JavaPluginExtension java) {
    project.getPluginManager().apply(org.gradle.plugins.ide.idea.IdeaPlugin.class);
    project.afterEvaluate(
        p -> {
          var module =
              p.getExtensions()
                  .getByType(org.gradle.plugins.ide.idea.model.IdeaModel.class)
                  .getModule();
          for (SourceSet set : java.getSourceSets()) {
            File dir = p.file("src/" + set.getName() + "/jsharp");
            if (set.getName().equals(SourceSet.MAIN_SOURCE_SET_NAME)) {
              module.getSourceDirs().add(dir);
            } else if (set.getName().equals(SourceSet.TEST_SOURCE_SET_NAME)) {
              module.getTestSources().from(dir);
            }
          }
        });
  }

  private static void configure(Project project, SourceSet sourceSet) {
    String name = sourceSet.getName();
    SourceDirectorySet jsharp =
        project.getObjects().sourceDirectorySet("jsharp", sourceSet.getName() + " J# source");
    jsharp.srcDir("src/" + name + "/jsharp");
    // Like Kotlin's .kt files, .jsharp files in the Java source directories are compiled too.
    jsharp.srcDirs(sourceSet.getJava().getSrcDirs());
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
    // For editors: the language server reads the libraries from build/jsharp/<set>.classpath.
    TaskProvider<JSharpClassPathFile> classPathFile =
        project
            .getTasks()
            .register(
                sourceSet.getTaskName("write", "JSharpClassPath"),
                JSharpClassPathFile.class,
                t -> {
                  t.setDescription("Records the " + name + " class path for J# editor support.");
                  t.getClasspath().from(sourceSet.getCompileClasspath());
                  t.getOutputFile()
                      .convention(
                          project
                              .getLayout()
                              .getBuildDirectory()
                              .file("jsharp/" + name + ".classpath"));
                });
    project
        .getTasks()
        .named(sourceSet.getClassesTaskName(), t -> t.dependsOn(compile, classPathFile));

    // .jsharp files elsewhere under src/<set> (src/main/kotlin, ...) fail the build (D097).
    TaskProvider<JSharpSourceCheck> sourceCheck =
        project
            .getTasks()
            .register(
                sourceSet.getTaskName("check", "JSharpSourceLocations"),
                JSharpSourceCheck.class,
                t -> {
                  t.setDescription(
                      "Checks that the " + name + " J# files are where J# compiles them.");
                  t.getExpectedDirectory().set("src/" + name + "/jsharp");
                  t.getStraySources()
                      .from(
                          project
                              .fileTree("src/" + name)
                              .matching(
                                  f -> {
                                    f.include("**/*.jsharp");
                                    f.exclude("resources/**");
                                  })
                              .filter(
                                  file ->
                                      jsharp.getSrcDirs().stream()
                                          .noneMatch(
                                              dir -> file.toPath().startsWith(dir.toPath()))));
                });
    compile.configure(t -> t.dependsOn(sourceCheck));
  }
}

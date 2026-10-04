package io.github.matrixidot.jsharp.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Builds and runs a real two-module project with the plugin (Gradle TestKit). */
class JSharpPluginFunctionalTest {
  @TempDir Path dir;

  private void write(String path, String content) throws IOException {
    Path p = dir.resolve(path);
    Files.createDirectories(p.getParent());
    Files.writeString(p, content);
  }

  private GradleRunner runner(String... args) {
    return GradleRunner.create()
        .withProjectDir(dir.toFile())
        .withPluginClasspath()
        .withArguments(args)
        .forwardOutput();
  }

  private void project() throws IOException {
    write("settings.gradle.kts", "rootProject.name = \"demo\"\ninclude(\"lib\", \"app\")\n");
    write("lib/build.gradle.kts", "plugins { java }\n");
    write(
        "lib/src/main/java/lib/Greeter.java",
        """
        package lib;

        public class Greeter {
          private final String greeting;

          public Greeter(String greeting) {
            this.greeting = greeting;
          }

          public String getGreeting() {
            return greeting;
          }

          public String greet(String name) {
            return greeting + ", " + name + "!";
          }
        }
        """);
    write(
        "app/build.gradle.kts",
        """
        plugins {
            id("io.github.matrixidot.jsharp")
            application
        }
        dependencies { implementation(project(":lib")) }
        application { mainClass.set("app.Launcher") }
        """);
    write(
        "app/src/main/jsharp/app/greetings.jsharp",
        """
        package app;

        import lib.Greeter;
        import java.util.*;

        public record Person(String name, int age);

        public static String welcome(List<Person> people) {
            val g = new Greeter("Hello");
            return people.where(p => p.age >= 18).select(p => g.greet(p.name)).joinToString(" ")
                + $" ({g.greeting.length()})";
        }
        """);
    write(
        "app/src/main/java/app/Launcher.java",
        """
        package app;

        import java.util.List;

        public class Launcher {
          public static void main(String[] args) {
            System.out.println(
                GreetingsModule.welcome(List.of(new Person("Ada", 36), new Person("Tim", 9))));
          }
        }
        """);
  }

  @Test
  void compilesJSharpBeforeJavaAndRuns() throws IOException {
    project();
    BuildResult result = runner(":app:run", "--stacktrace").build();
    assertThat(result.task(":app:compileJSharp").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    assertThat(result.getOutput()).contains("Hello, Ada! (5)");

    // Up to date on a second build, recompiled after an edit.
    BuildResult again = runner(":app:compileJSharp").build();
    assertThat(again.task(":app:compileJSharp").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
  }

  @Test
  void javaAndJSharpUseEachOtherInOneModule() throws IOException {
    project();
    // J# uses a Java class of its own source set, which in turn uses J#.
    write(
        "app/src/main/java/app/Shout.java",
        """
        package app;

        public final class Shout {
          public static String loud(Person p) {
            return p.name().toUpperCase();
          }
        }
        """);
    write(
        "app/src/main/jsharp/app/shouting.jsharp",
        """
        package app;

        public static String shoutAll(java.util.List<Person> people) =>
            people.select(p => Shout.loud(p)).joinToString("+");
        """);
    write(
        "app/src/main/java/app/Launcher.java",
        """
        package app;

        import java.util.List;

        public class Launcher {
          public static void main(String[] args) {
            System.out.println(
                ShoutingModule.shoutAll(List.of(new Person("Ada", 36), new Person("Tim", 9))));
          }
        }
        """);
    BuildResult result = runner(":app:run", "--stacktrace").build();
    assertThat(result.getOutput()).contains("ADA+TIM");
  }

  @Test
  void reportsCompileErrors() throws IOException {
    project();
    write(
        "app/src/main/jsharp/app/broken.jsharp",
        "package app;\npublic static int bad() => \"x\";\n");
    BuildResult result = runner(":app:compileJSharp").buildAndFail();
    assertThat(result.getOutput()).contains("error[JS0600]").contains("J# compilation failed");
  }

  private void singleModule() throws IOException {
    write("settings.gradle.kts", "rootProject.name = \"solo\"\n");
    write("build.gradle.kts", "plugins { id(\"io.github.matrixidot.jsharp\") }\n");
  }

  /** Like Kotlin's .kt files, .jsharp files in src/main/java are compiled (D097). */
  @Test
  void compilesJSharpFilesInTheJavaDirectory() throws IOException {
    singleModule();
    write(
        "src/main/java/solo/Twice.jsharp",
        "package solo;\npublic static int twice(int x) => 2 * x;\n");
    BuildResult result = runner("classes").build();
    assertThat(result.task(":compileJSharp").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    assertThat(dir.resolve("build/classes/jsharp/main/solo"))
        .isDirectoryContaining("glob:**.class");
  }

  /** A .jsharp file the build would skip (here in src/main/kotlin) fails it instead (D097). */
  @Test
  void failsForJSharpFilesOutsideTheSourceDirectories() throws IOException {
    singleModule();
    write("src/main/kotlin/solo/Lost.jsharp", "package solo;\npublic static int one() => 1;\n");
    BuildResult result = runner("jar").buildAndFail();
    assertThat(result.getOutput())
        .contains("not in a J# source directory")
        .contains("Lost.jsharp")
        .contains("move them to src/main/jsharp");
  }

  /** IntelliJ's Gradle import reads the idea model: src/main/jsharp is a source folder. */
  @Test
  void marksTheJSharpDirectoryAsASourceFolderForIntelliJ() throws IOException {
    singleModule();
    write("src/main/jsharp/solo/One.jsharp", "package solo;\npublic static int one() => 1;\n");
    runner("ideaModule").build();
    assertThat(Files.readString(dir.resolve("solo.iml")))
        .contains("url=\"file://$MODULE_DIR$/src/main/jsharp\" isTestSource=\"false\"");
  }
}

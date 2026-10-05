package io.github.matrixidot.jsharp.cli;

import io.github.matrixidot.jsharp.compiler.LanguageInfo;
import io.github.matrixidot.jsharp.compiler.syntax.TokenKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code jsharp new}: starter projects (D098). Gradle projects use the published J# Gradle plugin
 * of this version, a Gradle wrapper, and Gradle's daemon JVM criteria, so Gradle downloads Java 25
 * itself. The IntelliJ and VS Code wizards run this command, so every way of starting a project
 * gives the same files.
 */
public final class ProjectTemplates {
  private ProjectTemplates() {}

  /** Where releases publish the J# Gradle plugin and runtime (GitHub Pages). */
  public static final String MAVEN_REPOSITORY = "https://matrixidot.github.io/J-Sharp/maven";

  /** Paper version the plugin template targets (its {@code paper-api} and test server). */
  public static final String MINECRAFT_VERSION = "26.2";

  /** A template: its name for {@code jsharp new <template>} and a one-line description. */
  public enum Template {
    APP("app", "an application (Gradle; ./gradlew run)"),
    LIBRARY("library", "a library for J# and Java code (Gradle)"),
    PAPER("paper", "a Minecraft server plugin for Paper (Gradle; ./gradlew runServer)"),
    SCRIPT("script", "a single script file, no build tool (jsharp run main.jsharp)");

    public final String id;
    public final String description;

    Template(String id, String description) {
      this.id = id;
      this.description = description;
    }

    public static Template of(String id) {
      for (Template t : values()) {
        if (t.id.equals(id)) {
          return t;
        }
      }
      return null;
    }
  }

  /** The project's name and package, validated. */
  public record Project(String name, String packageName) {
    /** The name as a class name: {@code my-plugin} becomes {@code MyPlugin}. */
    public String className() {
      StringBuilder sb = new StringBuilder();
      boolean upper = true;
      for (char ch : name.toCharArray()) {
        if (Character.isLetterOrDigit(ch)) {
          sb.append(upper ? Character.toUpperCase(ch) : ch);
          upper = false;
        } else {
          upper = true;
        }
      }
      if (sb.isEmpty() || !Character.isJavaIdentifierStart(sb.charAt(0))) {
        sb.insert(0, "App");
      }
      return sb.toString();
    }

    String packagePath() {
      return packageName.replace('.', '/');
    }
  }

  /** The package used when none is given: {@code com.example.<name in lower case>}. */
  public static String defaultPackage(String name) {
    String simple = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    if (simple.isEmpty() || !Character.isJavaIdentifierStart(simple.charAt(0))) {
      simple = "app" + simple;
    }
    return "com.example." + (isReserved(simple) ? simple + "_" : simple);
  }

  /** Why {@code pkg} is not a usable package name, or null if it is. */
  public static String checkPackage(String pkg) {
    if (pkg.isEmpty()) {
      return "the package name is empty";
    }
    for (String part : pkg.split("\\.", -1)) {
      if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
        return "'"
            + pkg
            + "' is not a package name (use letters, digits and dots: com.example.app)";
      }
      for (char ch : part.toCharArray()) {
        if (!Character.isJavaIdentifierPart(ch)) {
          return "'"
              + pkg
              + "' is not a package name (use letters, digits and dots: com.example.app)";
        }
      }
      if (isReserved(part)) {
        return "'" + part + "' is a keyword and cannot be part of a package name";
      }
    }
    return null;
  }

  /** Why {@code name} is not a usable project name, or null if it is. */
  public static String checkName(String name) {
    if (name.isBlank()) {
      return "the project name is empty";
    }
    if (!name.matches("[A-Za-z0-9][A-Za-z0-9 ._-]*")) {
      return "'" + name + "' is not a project name (use letters, digits, '-', '_', '.' and spaces)";
    }
    return null;
  }

  private static boolean isReserved(String word) {
    return TokenKind.keyword(word) != null
        || javax.lang.model.SourceVersion.isKeyword(word)
        || word.equals("_");
  }

  /** The files of a new project, by path relative to the project directory. */
  public static Map<String, String> files(Template template, Project p) {
    Map<String, String> out = new LinkedHashMap<>();
    String version = LanguageInfo.VERSION;
    String pkgDir = p.packagePath();
    switch (template) {
      case SCRIPT -> {
        out.put("main.jsharp", script(p));
        out.put("README.md", scriptReadme(p));
        return out;
      }
      case APP -> {
        out.put("build.gradle.kts", appBuild(p, version));
        out.put("src/main/jsharp/" + pkgDir + "/main.jsharp", appMain(p));
        out.put("README.md", gradleReadme(p, "./gradlew run", "runs the program"));
      }
      case LIBRARY -> {
        out.put("build.gradle.kts", libraryBuild(p, version));
        out.put("src/main/jsharp/" + pkgDir + "/" + p.className() + ".jsharp", libraryMain(p));
        out.put("README.md", gradleReadme(p, "./gradlew build", "builds build/libs/*.jar"));
      }
      case PAPER -> {
        out.put("build.gradle.kts", paperBuild(p, version));
        out.put("src/main/resources/paper-plugin.yml", paperYml(p));
        out.put("src/main/jsharp/" + pkgDir + "/" + p.className() + ".jsharp", paperMain(p));
        out.put("README.md", paperReadme(p));
      }
    }
    out.put("settings.gradle.kts", settings(p, version));
    out.put(".gitignore", gitignore(template));
    return out;
  }

  /**
   * Writes the project into {@code dir}, which may exist (an IDE creates it before the wizard runs)
   * but must not contain any of the files, and returns the files written. Gradle projects also get
   * the Gradle wrapper and the daemon JVM criteria.
   */
  public static List<Path> create(Template template, Project p, Path dir) throws IOException {
    for (String f : allFiles(template, p)) {
      if (Files.exists(dir.resolve(f))) {
        throw new IOException(dir.resolve(f) + " already exists");
      }
    }
    List<Path> written = new ArrayList<>();
    for (var e : files(template, p).entrySet()) {
      written.add(write(dir.resolve(e.getKey()), e.getValue()));
    }
    if (template != Template.SCRIPT) {
      for (String f : GRADLE_FILES) {
        Path target = dir.resolve(f);
        Files.createDirectories(target.getParent());
        try (InputStream in = ProjectTemplates.class.getResourceAsStream("gradle/" + f)) {
          if (in == null) {
            throw new IllegalStateException("missing template resource gradle/" + f);
          }
          Files.copy(in, target);
        }
        written.add(target);
      }
      Path gradlew = dir.resolve("gradlew");
      try {
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
      } catch (UnsupportedOperationException e) {
        // Windows: gradlew.bat needs no permission bits
      }
    }
    return written;
  }

  private static final List<String> GRADLE_FILES =
      List.of(
          "gradlew",
          "gradlew.bat",
          "gradle/wrapper/gradle-wrapper.jar",
          "gradle/wrapper/gradle-wrapper.properties",
          "gradle/gradle-daemon-jvm.properties");

  private static List<String> allFiles(Template template, Project p) {
    List<String> out = new ArrayList<>(files(template, p).keySet());
    if (template != Template.SCRIPT) {
      out.addAll(GRADLE_FILES);
    }
    return out;
  }

  /** The file to open after creating a project: its main source file. */
  public static String mainFile(Template template, Project p) {
    return switch (template) {
      case SCRIPT -> "main.jsharp";
      case APP -> "src/main/jsharp/" + p.packagePath() + "/main.jsharp";
      case LIBRARY, PAPER -> "src/main/jsharp/" + p.packagePath() + "/" + p.className() + ".jsharp";
    };
  }

  private static Path write(Path file, String text) {
    try {
      Files.createDirectories(file.getParent());
      return Files.writeString(file, text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ------------------------------------------------------------------ the files

  private static String settings(Project p, String version) {
    String local =
        version.endsWith("-SNAPSHOT")
            ? "        mavenLocal() // a development build of J# (gradlew publishToMavenLocal)\n"
            : "";
    return """
        pluginManagement {
            repositories {
        %s        maven("%s") // J#
                gradlePluginPortal()
            }
        }

        plugins {
            // Lets Gradle download the Java version a build asks for.
            id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
        }

        rootProject.name = "%s"
        """
        .formatted(local, MAVEN_REPOSITORY, p.name());
  }

  private static String repositories(Project p, String version, String extra) {
    return """
        repositories {
        %s    mavenCentral()
        %s}
        """
        .formatted(version.endsWith("-SNAPSHOT") ? "    mavenLocal()\n" : "", extra);
  }

  private static String appBuild(Project p, String version) {
    return """
        plugins {
            id("io.github.matrixidot.jsharp") version "%s"
            application
        }

        group = "%s"
        version = "0.1.0"

        %s
        java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

        // main.jsharp's top-level statements are the entry point of the class MainModule.
        application { mainClass = "%s.MainModule" }
        """
        .formatted(version, p.packageName(), repositories(p, version, ""), p.packageName());
  }

  private static String appMain(Project p) {
    return """
        package %s;

        import java.util.*;

        // Top-level statements are the program. Run it with ./gradlew run, or the Run button.
        val name = args.length > 0 ? args[0] : "world";
        println($"Hello, {name}!");

        var squares = List.of(1, 2, 3, 4, 5).select(x => x * x);
        println($"squares: {squares}");
        """
        .formatted(p.packageName());
  }

  private static String libraryBuild(Project p, String version) {
    return """
        plugins {
            id("io.github.matrixidot.jsharp") version "%s"
            `java-library`
        }

        group = "%s"
        version = "0.1.0"

        %s
        java { toolchain.languageVersion = JavaLanguageVersion.of(25) }
        """
        .formatted(version, p.packageName(), repositories(p, version, ""));
  }

  private static String libraryMain(Project p) {
    return """
        package %s;

        /** A greeting, usable from J# and from Java. */
        public record Greeting(String name) {
            public String text => $"Hello, {name}!";
        }

        public String shout(Greeting g) => g.text.toUpperCase();
        """
        .formatted(p.packageName());
  }

  private static String paperBuild(Project p, String version) {
    return """
        plugins {
            id("io.github.matrixidot.jsharp") version "%s"
            id("com.gradleup.shadow") version "9.6.1"
            id("xyz.jpenilla.run-paper") version "3.1.0"
        }

        group = "%s"
        version = "0.1.0"

        %s
        dependencies {
            // The server provides Paper's API; the J# runtime goes into the plugin jar.
            compileOnly("io.papermc.paper:paper-api:%s.build.+")
        }

        java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

        tasks {
            runServer {
                // ./gradlew runServer: a test server with this plugin installed, in run/.
                minecraftVersion("%s")
                jvmArgs("-Xms2G", "-Xmx2G")
            }

            processResources {
                val props = mapOf("version" to version)
                filesMatching("paper-plugin.yml") {
                    expand(props)
                }
            }
        }
        """
        .formatted(
            version,
            p.packageName(),
            repositories(
                p, version, "    maven(\"https://repo.papermc.io/repository/maven-public/\")\n"),
            MINECRAFT_VERSION,
            MINECRAFT_VERSION);
  }

  private static String paperYml(Project p) {
    return """
        name: %s
        version: '${version}'
        main: %s.%s
        api-version: '%s'
        description: A Paper plugin written in J#
        """
        .formatted(p.className(), p.packageName(), p.className(), MINECRAFT_VERSION);
  }

  private static String paperMain(Project p) {
    return """
        package %s;

        import com.mojang.brigadier.Command;
        import io.papermc.paper.command.brigadier.Commands;
        import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
        import net.kyori.adventure.text.Component;
        import net.kyori.adventure.text.format.NamedTextColor;
        import org.bukkit.event.*;
        import org.bukkit.event.player.PlayerJoinEvent;
        import org.bukkit.plugin.java.JavaPlugin;

        public class %s : JavaPlugin, Listener {
            public override void onEnable() {
                getServer().getPluginManager().registerEvents(this, this);

                // Paper plugins register their commands in code.
                getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event => {
                    event.registrar().register(
                        Commands.literal("hello")
                            .executes(ctx => {
                                ctx.getSource().getSender().sendMessage(
                                    Component.text("Hello from J#!", NamedTextColor.AQUA));
                                return Command.SINGLE_SUCCESS;
                            })
                            .build(),
                        "Says hello");
                });

                getLogger().info("%s is enabled");
            }

            @EventHandler
            public void onJoin(PlayerJoinEvent e) {
                val player = e.getPlayer();
                player.sendMessage(Component.text($"Welcome, {player.getName()}!", NamedTextColor.GOLD));
            }
        }
        """
        .formatted(p.packageName(), p.className(), p.className());
  }

  private static String script(Project p) {
    return """
        // %s: run it with `jsharp run main.jsharp`, or the Run button in your editor.
        import java.util.*;

        val words = List.of("J#", "runs", "on", "the", "JVM");
        println(String.join(" ", words));

        int square(int x) => x * x;
        println($"7 squared is {square(7)}");
        """
        .formatted(p.name());
  }

  private static String scriptReadme(Project p) {
    return """
        # %s

        A J# script. Run it with the Run button in your editor, or:

        ```
        jsharp run main.jsharp
        ```
        """
        .formatted(p.name());
  }

  private static String gradleReadme(Project p, String command, String what) {
    return """
        # %s

        A J# project. `%s` %s (`gradlew.bat` on Windows). Gradle downloads Java 25 and J# the
        first time.

        Sources are in `src/main/jsharp`. Open this folder in IntelliJ IDEA or VS Code with the J#
        plugin; after the first build, the editor also knows the project's libraries.
        """
        .formatted(p.name(), command, what);
  }

  private static String paperReadme(Project p) {
    return """
        # %s

        A Paper plugin written in J#.

        - `./gradlew runServer` starts a Paper %s test server with the plugin, in `run/`. The first
          time, accept Minecraft's EULA in `run/eula.txt`, then run it again and join `localhost`.
        - `./gradlew build` builds `build/libs/%s-0.1.0-all.jar`, the jar to put in a server's
          `plugins/` folder.

        Use `gradlew.bat` on Windows. Gradle downloads Java 25 and J# the first time. The plugin's
        code is in `src/main/jsharp`, its description in `src/main/resources/paper-plugin.yml`.
        """
        .formatted(p.name(), MINECRAFT_VERSION, p.name());
  }

  private static String gitignore(Template t) {
    return ".gradle/\nbuild/\nout/\n.idea/\n*.iml\n" + (t == Template.PAPER ? "run/\n" : "");
  }
}

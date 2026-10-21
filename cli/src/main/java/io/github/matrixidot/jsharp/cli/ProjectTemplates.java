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
import javax.lang.model.SourceVersion;

/**
 * {@code jsharp new}: starter projects (D098). Gradle projects use the published J# Gradle plugin
 * of this version, a Gradle wrapper, and Gradle's daemon JVM criteria, so Gradle downloads the Java
 * release J# targets itself. The IntelliJ and VS Code wizards run this command, so every way of
 * starting a project gives the same files.
 */
public final class ProjectTemplates {
  private ProjectTemplates() {}

  /** Where releases publish the J# Gradle plugin and runtime (GitHub Pages). */
  public static final String MAVEN_REPOSITORY = "https://matrixidot.github.io/J-Sharp/maven";

  /**
   * The Minecraft versions the Minecraft templates use for a Java release: Paper's newest on that
   * Java, and NeoForge's most used (1.21.1 for Java 21, whose mods most modpacks run).
   *
   * @param paper the Paper version (test server and {@code api-version})
   * @param paperApi the version of the {@code paper-api} dependency
   * @param neoForgeMinecraft the Minecraft version of the NeoForge template
   * @param neoForge the NeoForge version
   */
  public record Minecraft(
      String paper, String paperApi, String neoForgeMinecraft, String neoForge) {
    public static Minecraft forJava(int javaRelease) {
      return javaRelease >= 25
          ? new Minecraft("26.3", "26.3.build.+", "26.3", "26.3.0.95")
          : new Minecraft("1.21.11", "1.21.11-R0.1-SNAPSHOT", "1.21.1", "21.1.264");
    }

    /** NeoForge before 26.1 takes item and block properties, not a function that makes them. */
    boolean neoForgeTakesProperties() {
      return neoForgeMinecraft.startsWith("1.");
    }
  }

  /** The ModDevGradle plugin of the NeoForge template. */
  public static final String MOD_DEV_GRADLE = "2.0.148";

  /**
   * The Java releases a new project can target: the long-term-support ones J# compiles for, newest
   * first (D108).
   */
  public static List<Integer> javaReleases() {
    List<Integer> out = new ArrayList<>();
    for (int r = LanguageInfo.JAVA_RELEASE; r >= LanguageInfo.MIN_JAVA_RELEASE; r--) {
      if ((r - 21) % 4 == 0) {
        out.add(r);
      }
    }
    return out;
  }

  /** A template: its name for {@code jsharp new <template>} and a one-line description. */
  public enum Template {
    APP("app", "an application (Gradle; ./gradlew run)"),
    LIBRARY("library", "a library for J# and Java code (Gradle)"),
    PAPER("paper", "a Minecraft server plugin for Paper (Gradle; ./gradlew runServer)"),
    NEOFORGE("neoforge", "a Minecraft mod for NeoForge (Gradle; ./gradlew runClient)"),
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

  /**
   * The project's name and package, validated, and the Java release it targets (one of {@link
   * #javaReleases()}).
   */
  public record Project(String name, String packageName, int javaRelease) {
    public Project(String name, String packageName) {
      this(name, packageName, LanguageInfo.JAVA_RELEASE);
    }

    /** The name as a NeoForge mod id: lower case letters, digits and {@code _}. */
    public String modId() {
      String id =
          name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
      if (id.isEmpty() || !Character.isLetter(id.charAt(0))) {
        id = "mod_" + id;
      }
      return id.length() > 64 ? id.substring(0, 64) : id;
    }

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
    return TokenKind.keyword(word) != null || SourceVersion.isKeyword(word) || word.equals("_");
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
      case NEOFORGE -> {
        out.put("build.gradle.kts", neoForgeBuild(p, version));
        out.put("src/main/templates/META-INF/neoforge.mods.toml", neoForgeModsToml(p));
        out.put("src/main/resources/assets/" + p.modId() + "/lang/en_us.json", neoForgeLang(p));
        out.put("src/main/jsharp/" + pkgDir + "/" + p.className() + ".jsharp", neoForgeMain(p));
        out.put("README.md", neoForgeReadme(p));
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
      case LIBRARY, PAPER, NEOFORGE ->
          "src/main/jsharp/" + p.packagePath() + "/" + p.className() + ".jsharp";
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
        java { toolchain.languageVersion = JavaLanguageVersion.of(%d) }

        // main.jsharp's top-level statements are the entry point of the class MainModule.
        application { mainClass = "%s.MainModule" }
        """
        .formatted(
            version,
            p.packageName(),
            repositories(p, version, ""),
            p.javaRelease(),
            p.packageName());
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
        java { toolchain.languageVersion = JavaLanguageVersion.of(%d) }
        """
        .formatted(version, p.packageName(), repositories(p, version, ""), p.javaRelease());
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
            compileOnly("io.papermc.paper:paper-api:%s")
        }

        java { toolchain.languageVersion = JavaLanguageVersion.of(%d) }

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
            Minecraft.forJava(p.javaRelease()).paperApi(),
            p.javaRelease(),
            Minecraft.forJava(p.javaRelease()).paper());
  }

  private static String paperYml(Project p) {
    return """
        name: %s
        version: '${version}'
        main: %s.%s
        api-version: '%s'
        description: A Paper plugin written in J#
        """
        .formatted(
            p.className(),
            p.packageName(),
            p.className(),
            Minecraft.forJava(p.javaRelease()).paper());
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

        A J# project. `%s` %s (`gradlew.bat` on Windows). Gradle downloads Java %d and J# the
        first time.

        Sources are in `src/main/jsharp`. Open this folder in IntelliJ IDEA or VS Code with the J#
        plugin; after the first build, the editor also knows the project's libraries.
        """
        .formatted(p.name(), command, what, p.javaRelease());
  }

  private static String paperReadme(Project p) {
    return """
        # %s

        A Paper plugin written in J#.

        - `./gradlew runServer` starts a Paper %s test server with the plugin, in `run/`. The first
          time, accept Minecraft's EULA in `run/eula.txt`, then run it again and join `localhost`.
        - `./gradlew build` builds `build/libs/%s-0.1.0-all.jar`, the jar to put in a server's
          `plugins/` folder.

        Use `gradlew.bat` on Windows. Gradle downloads Java %d and J# the first time. The plugin's
        code is in `src/main/jsharp`, its description in `src/main/resources/paper-plugin.yml`.
        """
        .formatted(p.name(), Minecraft.forJava(p.javaRelease()).paper(), p.name(), p.javaRelease());
  }

  // ------------------------------------------------------------------ NeoForge

  private static String neoForgeBuild(Project p, String version) {
    Minecraft mc = Minecraft.forJava(p.javaRelease());
    return """
        plugins {
            id("io.github.matrixidot.jsharp") version "%s"
            id("net.neoforged.moddev") version "%s"
        }

        val modId = "%s"
        group = "%s"
        version = "0.1.0"
        base { archivesName = modId }

        %s
        // Minecraft %s runs on Java %d.
        java { toolchain.languageVersion = JavaLanguageVersion.of(%d) }

        neoForge {
            version = "%s"
            runs {
                // ./gradlew runClient, runServer: the game with this mod, in run/.
                register("client") { client() }
                register("server") {
                    server()
                    programArgument("--nogui")
                }
            }
            mods {
                register(modId) { sourceSet(sourceSets.main.get()) }
            }
        }

        dependencies {
            // The J# runtime goes into the mod jar (jar-in-jar), so mods share one copy of it.
            jarJar("io.github.matrixidot.jsharp:runtime:%s")
        }

        // neoforge.mods.toml with this build's values.
        val generateModMetadata by tasks.registering(ProcessResources::class) {
            val props =
                mapOf("modId" to modId, "version" to version, "minecraft" to "%s", "neoForge" to "%s")
            inputs.properties(props)
            expand(props)
            from("src/main/templates")
            into(layout.buildDirectory.dir("generated/modMetadata"))
        }
        sourceSets.main { resources.srcDir(generateModMetadata) }
        neoForge.ideSyncTask(generateModMetadata)
        """
        .formatted(
            version,
            MOD_DEV_GRADLE,
            p.modId(),
            p.packageName(),
            repositories(p, version, "    maven(\"" + MAVEN_REPOSITORY + "\") // J#\n"),
            mc.neoForgeMinecraft(),
            p.javaRelease(),
            p.javaRelease(),
            mc.neoForge(),
            version,
            mc.neoForgeMinecraft(),
            mc.neoForge());
  }

  private static String neoForgeModsToml(Project p) {
    return """
        modLoader = "javafml"
        loaderVersion = "[1,)"
        license = "All Rights Reserved"

        [[mods]]
        modId = "${modId}"
        version = "${version}"
        displayName = "%s"
        description = "A NeoForge mod written in J#."

        [[dependencies.${modId}]]
        modId = "neoforge"
        type = "required"
        versionRange = "[${neoForge},)"
        ordering = "NONE"
        side = "BOTH"

        [[dependencies.${modId}]]
        modId = "minecraft"
        type = "required"
        versionRange = "[${minecraft}]"
        ordering = "NONE"
        side = "BOTH"
        """
        .formatted(p.name());
  }

  private static String neoForgeLang(Project p) {
    return """
        {
          "itemGroup.%1$s": "%2$s",
          "block.%1$s.example_block": "Example Block",
          "item.%1$s.example_item": "Example Item"
        }
        """
        .formatted(p.modId(), p.name());
  }

  private static String neoForgeMain(Project p) {
    boolean properties = Minecraft.forJava(p.javaRelease()).neoForgeTakesProperties();
    String block =
        properties
            ? "BlockBehaviour.Properties.of().mapColor(MapColor.STONE)"
            : "p => p.mapColor(MapColor.STONE)";
    String food =
        "new FoodProperties.Builder().alwaysEdible().nutrition(1).saturationModifier(2f).build()";
    String item =
        properties ? "new Item.Properties().food(" + food + ")" : "p => p.food(" + food + ")";
    return """
        package %1$s;

        import com.mojang.logging.LogUtils;
        import net.minecraft.core.registries.Registries;
        import net.minecraft.network.chat.Component;
        import net.minecraft.world.food.FoodProperties;
        import net.minecraft.world.item.*;
        import net.minecraft.world.level.block.state.BlockBehaviour;
        import net.minecraft.world.level.material.MapColor;
        import net.neoforged.bus.api.IEventBus;
        import net.neoforged.bus.api.SubscribeEvent;
        import net.neoforged.fml.ModContainer;
        import net.neoforged.fml.common.Mod;
        import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
        import net.neoforged.neoforge.common.NeoForge;
        import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
        import net.neoforged.neoforge.event.server.ServerStartingEvent;
        import net.neoforged.neoforge.registries.DeferredRegister;

        // FML creates this class: its id is the modId in neoforge.mods.toml.
        @Mod(%2$s.MOD_ID)
        public class %2$s {
            public static final String MOD_ID = "%3$s";
            static val LOGGER = LogUtils.getLogger();

            static val BLOCKS = DeferredRegister.createBlocks(MOD_ID);
            static val ITEMS = DeferredRegister.createItems(MOD_ID);
            static val TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

            public static val EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", %4$s);
            public static val EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);
            public static val EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", %5$s);

            public static val EXAMPLE_TAB = TABS.register("example_tab", () => CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.%3$s"))
                .withTabsBefore(CreativeModeTabs.COMBAT)
                .icon(() => EXAMPLE_ITEM.get().getDefaultInstance())
                .displayItems((parameters, output) => output.accept(EXAMPLE_ITEM.get()))
                .build());

            // FML passes the mod's event bus and container.
            public %2$s(IEventBus modBus, ModContainer container) {
                BLOCKS.register(modBus);
                ITEMS.register(modBus);
                TABS.register(modBus);
                modBus.addListener(this::commonSetup);
                modBus.addListener(this::addToCreativeTabs);
                NeoForge.EVENT_BUS.register(this); // for the @SubscribeEvent methods below
            }

            private void commonSetup(FMLCommonSetupEvent event) {
                LOGGER.info("Hello from J#");
            }

            private void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
                if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
                    event.accept(EXAMPLE_BLOCK_ITEM);
                }
            }

            @SubscribeEvent
            public void onServerStarting(ServerStartingEvent event) {
                LOGGER.info($"{MOD_ID} is running on {event.getServer().getMotd()}");
            }
        }
        """
        .formatted(p.packageName(), p.className(), p.modId(), block, item);
  }

  private static String neoForgeReadme(Project p) {
    Minecraft mc = Minecraft.forJava(p.javaRelease());
    return """
        # %s

        A NeoForge %s mod for Minecraft %s, written in J#.

        - `./gradlew runClient` starts Minecraft with the mod; `./gradlew runServer` a server (the
          first time, accept Minecraft's EULA in `run/eula.txt`, then run it again).
        - `./gradlew build` builds `build/libs/%s-0.1.0.jar`, the jar for a `mods/` folder. The J#
          runtime is inside it.

        Use `gradlew.bat` on Windows. Gradle downloads Java %d, Minecraft and J# the first time.
        The mod's code is in `src/main/jsharp`, its description in
        `src/main/templates/META-INF/neoforge.mods.toml`.
        """
        .formatted(p.name(), mc.neoForge(), mc.neoForgeMinecraft(), p.modId(), p.javaRelease());
  }

  private static String gitignore(Template t) {
    boolean minecraft = t == Template.PAPER || t == Template.NEOFORGE;
    return ".gradle/\nbuild/\nout/\n.idea/\n*.iml\n" + (minecraft ? "run/\n" : "");
  }
}

# A Paper plugin in J#

A Minecraft server plugin for [Paper](https://papermc.io) 26.x written in J#. It greets players
when they join (counting their visits) and adds a `/greet [name]` command.

Paper 26.1 and later run on Java 25, the same Java J# targets, so J# plugins load as they are.

## Build

From the J-Sharp checkout (JDK 25 needed):

```
./gradlew -p examples/paper-plugin jar
```

The plugin is `examples/paper-plugin/build/libs/hello-jsharp.jar`. Copy it into your server's
`plugins/` folder and start (or restart) the server.

## How it is set up

- `settings.gradle.kts` takes the J# Gradle plugin from the checkout (`includeBuild("../..")`).
  For a plugin in its own folder, run `./gradlew publishToMavenLocal` in J-Sharp once, then use
  `pluginManagement { repositories { mavenLocal(); gradlePluginPortal() } }` in settings and
  `id("io.github.matrixidot.jsharp") version "0.1.0-SNAPSHOT"` in `plugins { }`.
- `build.gradle.kts` adds Paper's repository and `paper-api` as `compileOnly` (the server
  provides it), and puts the J# runtime inside the jar, because the server only loads the
  plugin jar.
- `src/main/resources/plugin.yml` names the main class and declares the command.
- `src/main/jsharp/hello/HelloPlugin.jsharp` is the plugin: a class extending `JavaPlugin`, with
  an `@EventHandler` and `onCommand`.

Paper's API carries nullness annotations, and J# reads them, so `player.getLocation()` is
non-null and `getServer().getPlayer(name)` is `Player?`, which J# makes you check.

The editor plugins pick up Paper's classes once the project has been built with Gradle, as long
as the editor is opened on this folder.

import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.matrixidot.jsharp"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// The JetBrains IDE to build against (any IntelliJ-platform IDE with the LSP client, 2026.2+):
// -PidePath=/path/to/ide, default /opt/clion.
val idePath = providers.gradleProperty("idePath").orElse("/opt/clion")

dependencies {
    intellijPlatform {
        local(idePath)
        testFramework(TestFrameworkType.Platform)
    }
    // The J# lexer and parser, for highlighting and finding entry points (from the J# build).
    implementation("io.github.matrixidot.jsharp:compiler:0.1.0-SNAPSHOT")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    // IntelliJ base classes are Serializable but never serialized: no serialVersionUID noise.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial", "-Werror"))
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.matrixidot.jsharp"
        name = "J#"
        version = project.version.toString()
        ideaVersion { sinceBuild = "262" } // the client-based LSP API (2026.2)
    }
    buildSearchableOptions = false
    instrumentCode = false
    // Verify against another IDE with -PverifyIde=/path (default: the build IDE).
    pluginVerification { ides { local(providers.gradleProperty("verifyIde").orElse(idePath)) } }
}

val cliDist = gradle.includedBuild("jsharp").task(":cli:installDist")

// The plugin carries the J# CLI, run on the IDE's own Java for the language server and programs.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    dependsOn(cliDist)
    from("../../cli/build/install/jsharp/lib") { into("${pluginName.get()}/server/lib") }
}

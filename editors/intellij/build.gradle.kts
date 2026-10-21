import java.util.Properties
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.matrixidot.jsharp"
version = providers.gradleProperty("version").getOrElse("0.1.0")

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// The IDE to build and test against, downloaded once into the Gradle cache. The plugin uses only
// the platform's APIs, so the result installs in any JetBrains IDE 2026.2+ (IntelliJ IDEA, CLion,
// PyCharm, ...). CLion, because IntelliJ IDEA Ultimate's license check fails inside the test
// harness and IDEA Community ended with 2025.3. To use an installed IDE instead, pass
// -PidePath=/path/to/ide or put idePath=... in ~/.gradle/gradle.properties.
val idePath: String? = providers.gradleProperty("idePath").orNull
val clionVersion = "2026.2.2"

dependencies {
    intellijPlatform {
        if (idePath != null) local(idePath) else clion(clionVersion)
        testFramework(TestFrameworkType.Platform)
    }
    // The J# lexer and parser, for highlighting and finding entry points (from the J# build).
    implementation("io.github.matrixidot.jsharp:compiler:+")
    // The project templates of `jsharp new`, for the New Project wizard.
    implementation("io.github.matrixidot.jsharp:cli:+")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

// The Java release of the J# build (javaVersion in its gradle.properties, or -PjavaVersion=).
val javaVersion =
    providers.gradleProperty("javaVersion").orNull?.toInt()
        ?: Properties()
            .apply { rootDir.resolve("../../gradle.properties").reader().use { load(it) } }
            .getProperty("javaVersion")
            .toInt()

java { toolchain { languageVersion.set(JavaLanguageVersion.of(javaVersion)) } }

tasks.jar { from("../../LICENSE") { into("META-INF") } }

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
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
    // The release workflow publishes when the JETBRAINS_MARKETPLACE_TOKEN secret is set.
    publishing { token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN") }
    buildSearchableOptions = false
    instrumentCode = false
    // verifyPlugin runs the Marketplace's checks (internal and deprecated APIs, compatibility)
    // against IntelliJ IDEA, or an installed IDE with -PverifyIde=/path.
    val verifyIde = providers.gradleProperty("verifyIde").orNull
    pluginVerification {
        ides {
            if (verifyIde != null) {
                local(verifyIde)
            } else {
                create(IntelliJPlatformType.IntellijIdea, clionVersion)
                create(IntelliJPlatformType.IntellijIdeaCommunity, "2026.2.3") { useInstaller = false }
            }
        }
    }
}

val cliDist = gradle.includedBuild("jsharp").task(":cli:installDist")

// The plugin carries the J# CLI, run on the IDE's own Java for the language server and programs.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    dependsOn(cliDist)
    from("../../cli/build/install/jsharp/lib") { into("${pluginName.get()}/server/lib") }
}

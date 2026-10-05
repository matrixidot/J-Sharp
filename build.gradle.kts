// Root build: shared conventions for every Java module.
plugins {
    id("com.diffplug.spotless") version "8.9.0" apply false
}

val junitVersion = "5.13.4"
val assertjVersion = "3.27.7"

allprojects {
    group = "io.github.matrixidot.jsharp"
    version = providers.gradleProperty("version").get()
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:$junitVersion"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core:$assertjVersion")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "1g"
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            targetExclude("src/**/resources/**")
            googleJavaFormat()
        }
    }
}

tasks.register("e2e") {
    group = "verification"
    description = "Runs the golden end-to-end test suite (tests/cases)."
    dependsOn(":tests:test")
}

tasks.register("jmh") {
    group = "benchmark"
    description = "Runs the JMH benchmark suite."
    dependsOn(":bench:jmh")
}

// ---------------------------------------------------------------- bundled Java runtime (D099)

/**
 * `-Pplatform=linux-x64|linux-arm64|win32-x64|darwin-x64|darwin-arm64` puts that platform's Java
 * runtime (from scripts/runtimes.sh, in build/runtimes/<platform>) into the CLI download and the VS
 * Code extension, so nothing else needs installing. Without it they use an installed Java 25.
 */
val platform: String? = providers.gradleProperty("platform").orNull
val platforms = listOf("linux-x64", "linux-arm64", "win32-x64", "darwin-x64", "darwin-arm64")
val runtimeDir = platform?.let { layout.buildDirectory.dir("runtimes/$it") }
val checkRuntime =
    tasks.register("checkBundledRuntime") {
        val dir = runtimeDir?.get()?.asFile
        val name = platform
        doLast {
            if (name != null) {
                check(name in platforms) { "unknown platform '$name' (one of $platforms)" }
                check(dir != null && dir.resolve("release").isFile) {
                    "no Java runtime for $name in $dir: run scripts/runtimes.sh $name first"
                }
            }
        }
    }

/** The J# CLI files plus, for a platform, its runtime (bin/, lib/, runtime/). */
fun CopySpec.cliWithRuntime() {
    from(project(":cli").layout.buildDirectory.dir("install/jsharp")) {
        filesMatching("bin/*") { permissions { unix("rwxr-xr-x") } }
    }
    if (runtimeDir != null) {
        into("runtime") {
            from(runtimeDir)
            filesMatching(listOf("bin/*", "lib/jspawnhelper", "lib/jexec")) {
                permissions { unix("rwxr-xr-x") }
            }
        }
    }
}

val releaseVersion = providers.gradleProperty("version").get()

/** The `jsharp` command as a download: build/distributions/jsharp-<version>[-<platform>].zip|.tar.gz. */
val cliDistribution =
    if (platform == null || platform.startsWith("win32")) {
        tasks.register<Zip>("cliDistribution") {
            group = "distribution"
            description = "Builds the jsharp command as a zip (with -Pplatform: and its Java runtime)."
            dependsOn(":cli:installDist", checkRuntime)
            archiveFileName.set("jsharp-$releaseVersion${platform?.let { "-$it" } ?: ""}.zip")
            destinationDirectory.set(layout.buildDirectory.dir("distributions"))
            into("jsharp-$releaseVersion") { cliWithRuntime() }
        }
    } else {
        tasks.register<Tar>("cliDistribution") {
            group = "distribution"
            description = "Builds the jsharp command as a .tar.gz with the platform's Java runtime."
            dependsOn(":cli:installDist", checkRuntime)
            compression = Compression.GZIP
            archiveFileName.set("jsharp-$releaseVersion-$platform.tar.gz")
            destinationDirectory.set(layout.buildDirectory.dir("distributions"))
            into("jsharp-$releaseVersion") { cliWithRuntime() }
        }
    }

// ---------------------------------------------------------------- VS Code extension

/**
 * The VS Code extension as a .vsix (a zip in VS Code's package layout), with the J# CLI bundled
 * under extension/server so it works with only Java installed. No npm or vsce needed.
 */
val vscodeVersion = providers.gradleProperty("version").get().removeSuffix("-SNAPSHOT")
val vsixManifest =
    tasks.register("vscodeManifest") {
        val out = layout.buildDirectory.dir("vscode/meta")
        outputs.dir(out)
        inputs.property("version", vscodeVersion)
        inputs.property("platform", platform ?: "")
        // A platform-specific extension (VS Code installs the one matching the machine).
        val targetPlatform = platform?.let { " TargetPlatform=\"$it\"" } ?: ""
        doLast {
            val dir = out.get().asFile
            dir.mkdirs()
            dir.resolve("extension.vsixmanifest").writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011" xmlns:d="http://schemas.microsoft.com/developer/vsx-schema-design/2011">
                  <Metadata>
                    <Identity Language="en-US" Id="jsharp" Version="$vscodeVersion" Publisher="matrixidot"$targetPlatform />
                    <DisplayName>J#</DisplayName>
                    <Description xml:space="preserve">J# language support: highlighting, diagnostics, navigation, rename, parameter hints, completion and Run.</Description>
                    <Categories>Programming Languages</Categories>
                    <Properties>
                      <Property Id="Microsoft.VisualStudio.Code.Engine" Value="^1.85.0" />
                      <Property Id="Microsoft.VisualStudio.Code.ExtensionKind" Value="workspace" />
                    </Properties>
                  </Metadata>
                  <Installation>
                    <InstallationTarget Id="Microsoft.VisualStudio.Code" />
                  </Installation>
                  <Dependencies />
                  <Assets>
                    <Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true" />
                  </Assets>
                </PackageManifest>
                """.trimIndent() + "\n"
            )
            dir.resolve("[Content_Types].xml").writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension=".json" ContentType="application/json" />
                  <Default Extension=".vsixmanifest" ContentType="text/xml" />
                  <Default Extension=".js" ContentType="application/javascript" />
                  <Default Extension=".md" ContentType="text/markdown" />
                  <Default Extension=".jar" ContentType="application/java-archive" />
                  <Default Extension=".bat" ContentType="application/octet-stream" />
                  <Default Extension="" ContentType="application/octet-stream" />
                </Types>
                """.trimIndent() + "\n"
            )
        }
    }

tasks.register<Zip>("vscodeExtension") {
    group = "distribution"
    description = "Builds build/vscode/jsharp-<version>[-<platform>].vsix (install: code --install-extension <file>)."
    dependsOn(":cli:installDist", checkRuntime)
    archiveFileName.set("jsharp-$vscodeVersion${platform?.let { "-$it" } ?: ""}.vsix")
    destinationDirectory.set(layout.buildDirectory.dir("vscode"))
    from(vsixManifest)
    into("extension") {
        from("editors/vscode") { exclude("node_modules/**", "*.vsix", "package-lock.json") }
        into("server") { cliWithRuntime() }
    }
}

tasks.register<Exec>("installVscodeExtension") {
    group = "distribution"
    description = "Builds the VS Code extension and installs it with the `code` command."
    dependsOn("vscodeExtension")
    commandLine(
        "code",
        "--install-extension",
        layout.buildDirectory.file("vscode/jsharp-$vscodeVersion${platform?.let { "-$it" } ?: ""}.vsix").get().asFile.path,
        "--force",
    )
}

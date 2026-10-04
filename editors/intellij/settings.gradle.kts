// The IntelliJ platform plugin is a separate build so the main build never needs a JetBrains IDE.
// Build from the repository root: ./gradlew -p editors/intellij buildPlugin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "jsharp-intellij"

// The J# build, for the CLI distribution the plugin bundles.
includeBuild("../..") { name = "jsharp" }

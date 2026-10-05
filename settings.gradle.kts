plugins {
    // Lets Gradle download Java 25 (gradle/gradle-daemon-jvm.properties, toolchains).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jsharp"

include("compiler", "runtime", "lsp", "cli", "gradle-plugin", "tests", "bench")

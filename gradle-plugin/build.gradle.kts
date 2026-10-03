plugins { `java-gradle-plugin` }

description = "Gradle plugin: compiles src/<sourceSet>/jsharp with the J# compiler."

dependencies {
    implementation(project(":compiler"))
    implementation(project(":runtime"))
}

gradlePlugin {
    plugins {
        create("jsharp") {
            id = "io.github.matrixidot.jsharp"
            implementationClass = "io.github.matrixidot.jsharp.gradle.JSharpPlugin"
            displayName = "J#"
            description = "Compiles J# sources (src/main/jsharp, src/test/jsharp) to JVM bytecode."
        }
    }
}

tasks.test {
    // Functional tests run real (nested) Gradle builds.
    maxHeapSize = "1g"
}

apply(plugin = "maven-publish")
configure<PublishingExtension> {
    repositories {
        maven { name = "buildRepo"; url = uri(rootProject.layout.buildDirectory.dir("repo")) }
    }
}

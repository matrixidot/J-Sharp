plugins { `java-library` }

description = "J# runtime support library (jsharp.* packages); partly written in J#."

val jsharpc by configurations.creating

dependencies {
    jsharpc(project(":compiler"))
}

val jsharpOut = layout.buildDirectory.dir("classes/jsharp/main")

// The J# half of the standard library (src/main/jsharp), compiled by the J# compiler against
// the Java half. Uses the compiler's batch entry point, so there is no dependency on the CLI.
val compileJSharp by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Compiles src/main/jsharp with the J# compiler."
    dependsOn(tasks.compileJava)
    classpath = jsharpc
    mainClass.set("io.github.matrixidot.jsharp.compiler.driver.Batch")
    inputs.dir("src/main/jsharp")
    inputs.files(jsharpc)
    inputs.files(sourceSets.main.get().java.classesDirectory)
    outputs.dir(jsharpOut)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-cp", sourceSets.main.get().java.classesDirectory.get().asFile.absolutePath,
            "-d", jsharpOut.get().asFile.absolutePath,
            file("src/main/jsharp").absolutePath,
        )
    })
    doFirst { delete(jsharpOut) }
}

sourceSets.main {
    output.dir(mapOf("builtBy" to compileJSharp), jsharpOut)
}

// Java consumers in this build compile against the classes-directory variant: include the J#
// half there too (the jar already contains both).
configurations.named("apiElements") {
    outgoing.variants.named("classes") {
        artifact(jsharpOut) {
            type = "java-classes-directory"
            builtBy(compileJSharp)
        }
    }
}

// Maven publication (`./gradlew publishToMavenLocal`), used by the Gradle plugin.
apply(plugin = "maven-publish")
configure<PublishingExtension> {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        // A local repository in the build directory, for checking publications.
        maven { name = "buildRepo"; url = uri(rootProject.layout.buildDirectory.dir("repo")) }
    }
}

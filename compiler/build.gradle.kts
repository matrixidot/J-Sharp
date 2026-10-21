plugins { `java-library` }

description = "The J# compiler: lexer, parser, checker, lowering, codegen."

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    val java = rootProject.extra["javaVersion"].toString()
    inputs.property("javaVersion", java)
    filesMatching("**/jsharp-version.properties") { expand("version" to v, "javaVersion" to java) }
}

dependencies {
    // The runtime is needed on the compiler's *test* class path so tests can resolve jsharp.* names.
    testImplementation(project(":runtime"))
    // Below Java 24, the backport of java.lang.classfile (the root build rewrites the imports).
    if ((rootProject.extra["javaVersion"] as Int) < 24) {
        api("io.github.dmlloyd:jdk-classfile-backport:25.1")
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
        // -PmavenRepo=<dir>: the release workflow publishes into the GitHub Pages checkout.
        maven {
            name = "buildRepo"
            url = uri(providers.gradleProperty("mavenRepo").getOrElse(rootProject.layout.buildDirectory.dir("repo").get().asFile.path))
        }
    }
}

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

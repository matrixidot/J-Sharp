description = "JMH benchmarks comparing J# against equivalent hand-written Java."

val jmhVersion = "1.37"

sourceSets {
    create("jmh") {
        java.srcDir("src/jmh/java")
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

dependencies {
    "jmhImplementation"("org.openjdk.jmh:jmh-core:$jmhVersion")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
    "jmhImplementation"(project(":runtime"))
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs JMH benchmarks. Pass -Pjmh.args='...' for JMH options."
    classpath = sourceSets["jmh"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    args((providers.gradleProperty("jmh.args").orNull ?: "").split(" ").filter { it.isNotBlank() })
}

tasks.named("check") { dependsOn("jmhClasses") }

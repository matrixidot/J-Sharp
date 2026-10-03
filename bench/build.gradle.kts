description = "JMH benchmarks comparing J# against equivalent hand-written Java."

val jmhVersion = "1.37"

val jsharpc by configurations.creating

val jsharpOut = layout.buildDirectory.dir("jsharp-classes")

// Compiles the J# kernels with the J# compiler itself.
val compileJSharp by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Compiles src/jsharp with the J# compiler."
    classpath = jsharpc
    mainClass.set("dev.jsharp.cli.Main")
    inputs.dir("src/jsharp")
    outputs.dir(jsharpOut)
    args("build", "src/jsharp", "-d", jsharpOut.get().asFile.absolutePath)
    // Compiler experiments: -Pjsharpc.jvmArgs="-Djsharp.switch.threshold=1"
    val extra = providers.gradleProperty("jsharpc.jvmArgs").orNull
    if (extra != null) {
        jvmArgs(extra.split(" ").filter { it.isNotBlank() })
        inputs.property("jsharpc.jvmArgs", extra)
    }
    doFirst { delete(jsharpOut) }
}

val jsharpClasses = files(jsharpOut).builtBy(compileJSharp)

sourceSets {
    create("jmh") {
        java.srcDir("src/jmh/java")
        compileClasspath += sourceSets["main"].output + jsharpClasses
        runtimeClasspath += sourceSets["main"].output + jsharpClasses
    }
}

dependencies {
    jsharpc(project(":cli"))
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

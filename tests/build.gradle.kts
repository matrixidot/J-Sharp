description = "End-to-end golden tests: compile tests/cases/*.jsharp, run on a fresh JVM, diff output."

dependencies {
    testImplementation(project(":compiler"))
    testImplementation(project(":runtime"))
}

tasks.test {
    inputs.dir("cases")
    inputs.dir("interop")
    systemProperty("jsharp.cases", file("cases").absolutePath)
    systemProperty("jsharp.interop", file("interop").absolutePath)
    systemProperty("jsharp.runtime.classpath", project(":runtime").sourceSets["main"].output.classesDirs.asPath)
    systemProperty("jsharp.java", javaLauncher.get().executablePath.asFile.absolutePath)
    dependsOn(":runtime:classes")
    maxParallelForks = 1
}

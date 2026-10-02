plugins { `java-library` }

description = "The J# compiler: lexer, parser, checker, lowering, codegen."

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("**/jsharp-version.properties") { expand("version" to v) }
}

dependencies {
    // The runtime is needed on the compiler's *test* class path so tests can resolve jsharp.* names.
    testImplementation(project(":runtime"))
}

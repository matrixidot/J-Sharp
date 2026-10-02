plugins { `java-library` }

description = "The J# compiler: lexer, parser, checker, lowering, codegen."

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("**/jsharp-version.properties") { expand("version" to v) }
}

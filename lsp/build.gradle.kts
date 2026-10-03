plugins { `java-library` }

description = "Language server (LSP) for J#: diagnostics, hover, definition, symbols, completion."

dependencies {
    api(project(":compiler"))
    testImplementation(project(":runtime"))
}

plugins { application }

description = "The `jsharp` command-line tool."

dependencies {
    implementation(project(":compiler"))
    runtimeOnly(project(":runtime"))
}

application {
    mainClass.set("dev.jsharp.cli.Main")
    applicationName = "jsharp"
}

// Fast startup: the Unix launcher uses a class-data-sharing archive that the JVM creates on the
// first run in the user's cache directory (only if that directory is writable, since the JVM
// aborts when it cannot write the archive). Set JSHARP_NO_CDS=1 to disable.
tasks.named<CreateStartScripts>("startScripts") {
    val version = project.version.toString()
    doLast {
        val script = unixScript
        val marker = "DEFAULT_JVM_OPTS=\"\""
        val cds =
            """
            |DEFAULT_JVM_OPTS=""
            |JSHARP_CACHE="${'$'}{XDG_CACHE_HOME:-${'$'}HOME/.cache}/jsharp"
            |if [ -z "${'$'}JSHARP_NO_CDS" ] && mkdir -p "${'$'}JSHARP_CACHE" 2>/dev/null && [ -w "${'$'}JSHARP_CACHE" ]; then
            |    DEFAULT_JVM_OPTS="-XX:SharedArchiveFile=${'$'}JSHARP_CACHE/jsharp-$version.jsa -XX:+AutoCreateSharedArchive"
            |fi
            """.trimMargin()
        val text = script.readText()
        check(text.contains(marker)) { "start script layout changed" }
        script.writeText(text.replace(marker, cds))
    }
}

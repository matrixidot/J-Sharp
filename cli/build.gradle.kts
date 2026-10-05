plugins { application }

description = "The `jsharp` command-line tool."

dependencies {
    implementation(project(":compiler"))
    implementation(project(":lsp"))
    runtimeOnly(project(":runtime"))
}

application {
    mainClass.set("io.github.matrixidot.jsharp.cli.Main")
    applicationName = "jsharp"
}

// Fast startup: the Unix launcher uses a class-data-sharing archive that the JVM creates on the
// first run in the user's cache directory (only if that directory is writable, since the JVM
// aborts when it cannot write the archive). Set JSHARP_NO_CDS=1 to disable.
tasks.named<CreateStartScripts>("startScripts") {
    val version = project.version.toString()
    val mainClassName = "io.github.matrixidot.jsharp.cli.Main"
    doLast {
        val script = unixScript
        val marker = "DEFAULT_JVM_OPTS=\"\""
        val cds =
            """
            |DEFAULT_JVM_OPTS=""
            |JSHARP_CACHE="${'$'}{XDG_CACHE_HOME:-${'$'}HOME/.cache}/jsharp"
            |if [ -z "${'$'}JSHARP_NO_CDS" ] && mkdir -p "${'$'}JSHARP_CACHE" 2>/dev/null && [ -w "${'$'}JSHARP_CACHE" ]; then
            |    # The archive is only valid for these exact jars: name it after their contents and dates.
            |    JSHARP_FP=${'$'}( { ls -l "${'$'}APP_HOME"/lib/*.jar; cat "${'$'}APP_HOME"/lib/*.jar; } 2>/dev/null | cksum | cut -d' ' -f1 )
            |    JSHARP_CDS="${'$'}JSHARP_CACHE/jsharp-$version-${'$'}JSHARP_FP.jsa"
            |    if [ ! -f "${'$'}JSHARP_CDS" ]; then
            |        # First run: record the classes a typical compilation loads (written atomically).
            |        "${'$'}JAVACMD" -XX:ArchiveClassesAtExit="${'$'}JSHARP_CDS.${'$'}${'$'}" -Xlog:cds=off -Xlog:cds+dynamic=off \
            |            -cp "${'$'}CLASSPATH" $mainClassName --cds-train >/dev/null 2>&1 \
            |            && mv -f "${'$'}JSHARP_CDS.${'$'}${'$'}" "${'$'}JSHARP_CDS" 2>/dev/null
            |        rm -f "${'$'}JSHARP_CDS.${'$'}${'$'}"
            |    fi
            |    if [ -f "${'$'}JSHARP_CDS" ]; then
            |        DEFAULT_JVM_OPTS="-XX:SharedArchiveFile=${'$'}JSHARP_CDS -Xlog:cds=off -Xlog:cds+dynamic=off"
            |    fi
            |fi
            """.trimMargin()
        val text = script.readText()
        check(text.contains(marker)) { "start script layout changed" }
        script.writeText(text.replace(marker, cds))
    }
}

// `jsharp new` gives Gradle projects the same wrapper and daemon JVM criteria as this build.
tasks.processResources {
    from(rootProject.layout.projectDirectory) {
        include(
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradle/gradle-daemon-jvm.properties",
        )
        into("io/github/matrixidot/jsharp/cli/gradle")
    }
}

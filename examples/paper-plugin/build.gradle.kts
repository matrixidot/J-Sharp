plugins { id("io.github.matrixidot.jsharp") }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Provided by the server at run time, so it stays out of the jar.
    compileOnly("io.papermc.paper:paper-api:26.3.build.147-beta")
}

java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

// The server only loads the plugin jar, so the J# runtime goes inside it.
tasks.jar {
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

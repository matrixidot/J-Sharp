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

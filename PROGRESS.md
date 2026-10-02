# Progress

## M0 — Scaffolding (done)
- Works: Gradle 9.8 multi-module build (`compiler runtime stdlib cli tests bench`), JDK 25 toolchain,
  `-Xlint:all -Werror`, JUnit 5 + AssertJ, Spotless, JMH source set + `./gradlew jmh`, CI script/workflow.
- Works: `bin/jsharp --version` (application distribution), placeholder tests in each module.
- Deferred: e2e runner and `build`/`run`/`check` commands (M4), real benchmarks (M4).
- Known issues: google-java-format pinned to Spotless' default (see D003).
- Next: M1 lexer & parser.

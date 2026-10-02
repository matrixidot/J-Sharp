# Progress

## M0 — Scaffolding (done)
- Works: Gradle 9.8 multi-module build (`compiler runtime stdlib cli tests bench`), JDK 25 toolchain,
  `-Xlint:all -Werror`, JUnit 5 + AssertJ, Spotless, JMH source set + `./gradlew jmh`, CI script/workflow.
- Works: `bin/jsharp --version` (application distribution), placeholder tests in each module.
- Deferred: e2e runner and `build`/`run`/`check` commands (M4), real benchmarks (M4).
- Known issues: google-java-format pinned to Spotless' default (see D003).
- Next: M1 lexer & parser.

## M1 — Lexer & parser (done)
- Works: hand-written lexer (all literal forms incl. raw `"""`, `$"..{x:F2}.."`, `$"""`), immutable sealed-record AST with spans on every node, recursive-descent + precedence-climbing parser covering every construct in LANGUAGE_SPEC.md (incl. v0.2 list patterns, parsed only).
- Works: error recovery at statement/member boundaries, cascade suppression (malformed tokens, same-position errors), targeted hints for Java-isms; `jsharp parse <file>` dumps the AST.
- Tests: 5 golden parse files (all spec examples), 31 golden syntax-error files covering every JS00xx/JS01xx code, lexer/parser unit tests, 10k-mutation fuzz test (never throws; ~0.7 s).
- Deferred: semantic checks of parsed-only features (operator overloading, list patterns → v0.2), doc-comment capture.
- Known issues: `x ?[i]` with no space is read as null-safe indexing; generic-call disambiguation follows C# (`a < b > (c)` is a generic call).

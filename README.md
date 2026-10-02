# J#

J# (`jsharp`) is a statically typed language for the JVM with Java's ecosystem and C#'s
expressiveness: null safety, properties, records, pattern matching, extension methods,
async/await on virtual threads. It compiles straight to JVM bytecode and interoperates with Java
in both directions.

> Status: under active development. See [`PROGRESS.md`](PROGRESS.md) for what works today.

## Requirements
- JDK 25 (the build uses a Gradle toolchain; `JAVA_HOME` pointing at JDK 25 is simplest)
- Nothing else; the Gradle wrapper downloads Gradle and the test/benchmark libraries.

## Build
```
./gradlew build            # compile + all tests (unit + golden end-to-end)
./gradlew :compiler:test   # compiler unit tests only
./gradlew e2e              # golden end-to-end tests (tests/cases)
./gradlew jmh              # JMH benchmarks (bench/)
./gradlew spotlessApply    # format Java sources
```

## Use
```
bin/jsharp --version
bin/jsharp run examples/hello.jsharp
bin/jsharp build src -d out/
bin/jsharp check src
```
`bin/jsharp` builds the CLI distribution on first use (`cli/build/install/jsharp`). Set
`JSHARP_REBUILD=1` to rebuild it after changing the compiler.

## Layout
| Module      | Contents |
|-------------|----------|
| `compiler/` | lexer, parser, AST, resolution, type checker, lowering, ClassFile-API codegen |
| `runtime/`  | `jsharp.*` runtime support (`Task`, tuples, `Result`, sequences) |
| `stdlib/`   | standard library written in J# |
| `cli/`      | the `jsharp` command |
| `tests/`    | golden end-to-end tests (`tests/cases/*.jsharp` + expected output/diagnostics) |
| `bench/`    | JMH benchmarks, J# vs hand-written Java |

Design notes: [`DECISIONS.md`](DECISIONS.md). Progress log: [`PROGRESS.md`](PROGRESS.md).

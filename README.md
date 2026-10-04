# J#

J# (`jsharp`) is a statically typed language for the JVM that combines Java's ecosystem with C#'s
expressiveness: null safety, properties, records, sealed types and pattern matching, extension
methods, LINQ-style sequences, and async/await on virtual threads. It compiles straight to JVM
bytecode, runs as fast as hand-written Java, and mixes with Java in both directions.

```jsharp
import java.util.*;

public sealed interface Shape permits Circle, Rect;
public record Circle(double r) : Shape;
public record Rect(double w, double h) : Shape;

double area(Shape s) => s switch {
    Circle c => Math.PI * c.r * c.r,
    Rect(var w, var h) => w * h,
};

var shapes = List.of(new Circle(1), new Rect(2, 3), new Circle(0.5));
println($"total area {shapes.sumDouble(s => area(s)):F2}");
foreach (var s in shapes.where(s => area(s) > 1).orderBy(s => area(s))) println(s);
```

## Quick start

You need JDK 25 (`JAVA_HOME` pointing at it is simplest). Nothing else: the Gradle wrapper fetches
everything.

```
git clone https://github.com/matrixidot/J-Sharp.git && cd J-Sharp
bin/jsharp run examples/hello.jsharp            # builds the compiler on first use
bin/jsharp run examples/hello.jsharp J#
```

Then read the [language tour](docs/TOUR.md) (about 15 minutes) and try the demo application in
[`examples/ledger`](examples/ledger), a 1,000-line personal-finance tool.

## The `jsharp` command

```
jsharp run <files...|dir> [args...]              compile in memory and run
jsharp run <src...> -- [args...]                 `--` ends the sources explicitly
jsharp build <src...> -d out                     compile to class files
jsharp build <src...> --jar app.jar --include-runtime
                                                 a self-contained jar for `java -jar app.jar`
jsharp check <src...> [--diagnostics=json]       report errors and warnings only
jsharp lsp                                       language server for editors (stdio)
  -cp <path>                                     Java libraries (jars, class directories)
```

Editor support: the VS Code extension in [`editors/vscode`](editors/vscode) adds highlighting,
live errors, hover, go to definition, references, rename, parameter hints, completion, outline
and a ▶ Run button. It bundles the compiler, so `./gradlew installVscodeExtension` is all it takes
(with Java 25 installed). JetBrains IDEs (IntelliJ IDEA 2026.2+, CLion, ...) have a plugin in
[`editors/intellij`](editors/intellij), with run configurations and a gutter Run icon. Other
editors can run `jsharp lsp`. For a first look, open
[`examples/showcase`](examples/showcase) and press Run.

`bin/jsharp` runs the locally built CLI (`cli/build/install/jsharp`), building it if needed; set
`JSHARP_REBUILD=1` after changing the compiler. On first use the launcher records a class-data
sharing archive in `~/.cache/jsharp` so later startups are fast (`JSHARP_NO_CDS=1` disables it).

## Using J# in a Gradle project

The Gradle plugin compiles `src/main/jsharp` and `src/test/jsharp` with the project's
dependencies and adds the J# runtime. J# and Java in the same module can use each other freely,
and the J# classes go into tests, jars and `run`.

```kotlin
// settings.gradle.kts: use the plugin from a J-Sharp checkout...
pluginManagement { includeBuild("../J-Sharp") }
// ...or after `./gradlew publishToMavenLocal` in the checkout:
// pluginManagement { repositories { mavenLocal(); gradlePluginPortal() } }

// build.gradle.kts
plugins {
    id("io.github.matrixidot.jsharp")
    application
}
dependencies { implementation("com.example:some-java-library:1.0") }
application { mainClass.set("app.MainModule") }
```

After a build, the editors also know the project's dependencies: completion and hover work for
your libraries' classes. [`examples/paper-plugin`](examples/paper-plugin) is a complete example:
a Minecraft server plugin for Paper.

## Mixing Java and J# in one module

Java and J# sources of one module can reference each other in both directions: J# classes can
extend Java classes and implement Java interfaces, and Java can call and extend J# code. The J#
compiler reads the declarations of the module's `.java` files, compiles the J# sources, and then
javac compiles the Java sources against the J# classes. Java code is held to J#'s rules, the same
way as Java from a jar: `@Nullable` returns are nullable, sealed hierarchies are checked for
exhaustiveness, private members stay private.

- `jsharp build src/` and `jsharp run src/` compile every `.jsharp` and `.java` file under `src/`.
  javac's errors and warnings are reported like J#'s (JS1000, JS1001), with the Java line.
- The Gradle plugin reads `src/<set>/java` for J# and leaves compiling it to `compileJava`.
- The language server includes the module's Java files, so completion, hover and go to
  definition work across the two languages.

This needs a JDK (javac), not just a Java runtime. Annotation processors do not run on Java
sources compiled by `jsharp build`; use Gradle for those.

## Documentation

- [docs/TOUR.md](docs/TOUR.md): the language by example (every example is tested)
- [docs/BENCHMARKS.md](docs/BENCHMARKS.md): J# against hand-written Java and `java.util.stream`
- [DECISIONS.md](DECISIONS.md): design decisions and deviations from the specification
- [PROGRESS.md](PROGRESS.md): milestone log and current status

## Development

```
./gradlew build            # compile everything and run all tests
./gradlew :compiler:test   # compiler unit and checker tests
./gradlew e2e              # end-to-end programs, interop, examples and tour
./gradlew jmh              # JMH benchmarks (bench/)
./gradlew spotlessApply    # format the Java sources
```

| Module | Contents |
|---|---|
| `compiler/` | lexer, parser, name resolution, type checker, lowering, ClassFile-API code generation |
| `runtime/` | the `jsharp.*` library: `jsharp.core` (Prelude, `Task`, tuples, `Result`) in Java, `jsharp.collections` and `jsharp.text` written in J# |
| `lsp/` | the language server (`jsharp lsp`): JSON-RPC, diagnostics, hover, definition, symbols, completion |
| `cli/` | the `jsharp` command |
| `gradle-plugin/` | the Gradle plugin `io.github.matrixidot.jsharp` |
| `editors/vscode/` | the VS Code extension (grammar, language configuration, LSP client) |
| `tests/` | end-to-end programs (`tests/cases`), Java interop cases (`tests/interop`), example and documentation tests, the compiler-speed test |
| `bench/` | JMH benchmarks: J# kernels against equivalent Java |
| `examples/` | example programs |

## Status

Version 0.1 plus the v0.2 language features (collection literals, list patterns, operator
overloading), functions as values, local functions, `atomic` locals, and Java and J# sources
compiled together in one module. 452 automated tests, including 133 programs run under
`-Xverify:all`. Not yet available: a Maven Central release, incremental compilation, renaming
types in the editor, structured concurrency and query syntax. See [PROGRESS.md](PROGRESS.md).

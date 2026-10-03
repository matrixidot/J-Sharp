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
jsharp run <file|dir> [args...]                  compile in memory and run
jsharp build <src...> -d out                     compile to class files
jsharp build <src...> --jar app.jar --include-runtime
                                                 a self-contained jar for `java -jar app.jar`
jsharp check <src...> [--diagnostics=json]       report errors and warnings only
  -cp <path>                                     Java libraries (jars, class directories)
```

`bin/jsharp` runs the locally built CLI (`cli/build/install/jsharp`), building it if needed; set
`JSHARP_REBUILD=1` after changing the compiler. On first use the launcher records a class-data
sharing archive in `~/.cache/jsharp` so later startups are fast (`JSHARP_NO_CDS=1` disables it).

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
| `cli/` | the `jsharp` command |
| `tests/` | end-to-end programs (`tests/cases`), Java interop cases (`tests/interop`), example and documentation tests, the compiler-speed test |
| `bench/` | JMH benchmarks: J# kernels against equivalent Java |
| `examples/` | example programs |

## Status

Version 0.1 (milestones M0–M7 of the roadmap). The compiler implements the full v0.1 language
and is tested by about 400 automated tests, including 127 programs run under `-Xverify:all`.
Not yet available: IDE support, a build-tool plugin, incremental compilation, and the
v0.2+ features (list patterns, collection literals, query syntax). See
[PROGRESS.md](PROGRESS.md).

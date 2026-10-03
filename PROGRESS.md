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

## M2 — Declarations, resolution, Java symbols (done)
- Works: ClassFile-API class path (jrt image, dirs, jars) with lazy two-stage symbol completion; generic signatures, wildcards, F-bounds, inner/nested classes, records, sealed, enums, constants, parameter names, nullness annotations (declaration + type-use, `@NullMarked`), J# metadata annotations.
- Works: source symbol entry for all declaration kinds (properties -> accessors/backing fields, records, enums, module classes + entry point), imports (single, on-demand, static, aliases), type resolution with "did you mean" and "add import" hints, inheritance/sealed/modifier/duplicate checks; `jsharp check` runs parse + resolution.
- Tests: 16 golden resolution cases (symbol dumps + diagnostics, incl. multi-file), class-file loader tests against javac-compiled fixtures.
- Deferred: override/implementation checks, type-argument bound checks and inferred member types (M3), annotation resolution for codegen (M4).
- Known issues: compiles against the running JDK's class library (no `--release` ct.sym view).

## M3 — Type checker (done)
- Works: bidirectional checking to a typed bound tree: primitives/boxing/promotion, generics with bounds, declaration-site variance, wildcard capture, inference (incl. lambdas and poly call arguments), overloads with named/default/varargs args, extension methods, properties, records, enums, nullability with flow typing, definite (un)assignment, reachability, patterns with exhaustiveness/dominance, tuples, `with`, ranges, async/await typing, local and anonymous classes.
- Tests: 114 checker files (~600 inline assertions: positive and negative) plus resolution goldens; every diagnostic code has a test (enforced by `DiagnosticCoverageTest`).
- Deferred: list patterns (v0.2), `super::m` references, Java inner (non-static) class instantiation.
- Known issues: inference is a pragmatic subset of JLS 18 (complex nested generic lambdas may need explicit types); smart casts do not apply to fields.

## M4 — Codegen: real programs (done)
- Works: lowering (lambdas → `invokedynamic`, local/anonymous captures, enums, records via `ObjectMethods`, properties, foreach/using/switch/patterns, async, checked arithmetic, exception filters) and ClassFile-API codegen with generated stack maps, reachability-aware emission, qualifying-type member refs, Signature/InnerClasses/NestMembers/PermittedSubclasses/Record/MethodParameters/nullness attributes; `jsharp build` (dirs or `--jar`) and `jsharp run` (in-memory).
- Tests: 124 golden e2e programs (single and multi-file, args/stdin/exit codes) run on a fresh JVM with `-Xverify:all` plus the ClassFile verifier; 3 Java⇄J# interop cases in both directions (javac against J# output and J# against javac output); 121 checker files.
- Benchmarks: 7 J#-vs-Java JMH kernels, all within noise (ratios 0.97–1.02; `docs/BENCHMARKS.md`, raw data in `bench/results/m4.json`).
- Deferred: J#-written stdlib (`jsharp.collections`, `jsharp.text`) and `where/select` (M5/M6), `tableswitch` for dense int switches (M7).
- Known issues: inference still a subset of JLS 18; `typeSwitch` not yet benchmarked against `instanceof` chains.

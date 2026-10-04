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

## M5 — Patterns, async, extensions, defaults (done)
- Works: every spec section 6/11 program compiles and runs (`spec_section6`, `spec_section11`, `spec_expr_eval` e2e cases): switch expressions with exhaustiveness and `MatchException`, type/positional/property/relational/logical/null patterns with guards and generic-record inference, async/await on virtual threads (`Task<T>`), extension methods (imported with `import p.*` or implicitly from the stdlib), default/named args (source-order evaluation; real overloads for Java callers), tuples, `with`, null-safe operators, ranges/indices, `I.super.m()`.
- Tests: 127 e2e programs, 121 checker files incl. sealed-exhaustiveness negatives (JS0650), 3 interop cases.
- Deferred: list patterns (v0.2, parsed only), `SwitchBootstraps.typeSwitch` evaluation (M7).
- Known issues: exception filters run after inner `finally` blocks (JVM has no filter pass; D050).

## M6 — Stdlib & LINQ-style sequences (done)
- Works: `jsharp.collections` (lazy `Sequence<T>` with where/select/selectMany/orderBy/thenBy/groupBy/take/skip/zip/distinct/aggregate/first/any/all/sum/minBy/toList/toMap/joinToString/..., primitive `IntSequence`/`LongSequence`/`DoubleSequence`, `range`, `generate`, extensions on `Iterable`, arrays and streams) and `jsharp.text` string extensions, all written in J# and compiled into the runtime jar by the J# compiler.
- Benchmarks: `PipelineBench` vs `java.util.stream`: primitive/array pipelines allocate 0 B/op and run 2–28% faster; boxed, groupBy, firstMatch faster; topTen at parity; mapFilterCollect +9% within error bars. KernelBench unchanged from M4 (no regressions).
- Fixed along the way: synthetic lambda methods no longer carry generic signatures (javac rejected J# classes using them), J# tuple types round-trip through class files, StringConcat passes wrapper types like javac.
- Deferred: `foreach` over primitive sequences without boxing, query syntax (v0.3).
- Known issues: the machine's JMH runs are noisy (bimodal JIT on some Java baselines); see BENCHMARKS.md.

## M7 — Performance & polish (done)
- Performance: constant int switches with 20+ labels compile to `tableswitch`/`lookupswitch`, smaller ones to compare chains, which measured faster (D065). Type-pattern switches stay `instanceof` chains, which beat `SwitchBootstraps.typeSwitch` at 3 and 10 cases (D066). The suite now has 13 J#-vs-Java kernels and 7 sequence-vs-stream pipelines; all pairs except the bimodal `mapFilterCollect` are within noise or faster (BENCHMARKS.md, raw data in `bench/results/m7.json`).
- Compiler speed: a generated 10k-line project compiles (through codegen) in about 0.4 s warm, against the 2 s target, guarded by `CompilerSpeedTest`. Cold `jsharp check` of a small file takes about 0.26 s, against 0.3 s, using a CDS archive the launcher trains on first run (D067). `-Djsharp.timings=true` prints phase times.
- Tooling and diagnostics: `--include-runtime` builds self-contained jars. Unresolved names suggest the missing import. Null literals and `for (x : xs)` get targeted messages. Deep nesting is reported as JS0109, never as a crash (D069).
- Docs and examples: `docs/TOUR.md` (every example compiled and run by `DocsTest`), a new README, and `examples/ledger`, a 1,000-line demo app with 12 end-to-end tests (`ExamplesTest`).
- Other: package root renamed to `io.github.matrixidot.jsharp` (D068); Kotlin-style variance for JDK functional interfaces (D070); the default-argument overload cap is documented (D071).

## Final assessment (v0.1)

**Done:** every exit criterion of M0–M7. The compiler implements the v0.1 language of LANGUAGE_SPEC.md, and the spec's example programs (sections 6 and 11) compile and run. Java⇄J# interop works in both directions. Benchmarks show J# at parity with or faster than hand-written Java and `java.util.stream` in every pair except one bimodal pipeline (BENCHMARKS.md). 402 automated tests (all passing) cover it:
- 129 golden programs under `-Xverify:all`;
- 121 checker files with 616 inline assertions;
- parser and resolution goldens, plus a fuzz test;
- 3 interop cases, 12 demo-app cases, 13 tour examples and a compiler-speed bound.

**Incomplete or limited:**
- Type inference is a pragmatic subset of JLS 18. Unusual nested generic calls can still need explicit type arguments; the corpus found and fixed many such cases, so expect a long tail.
- Smart casts apply to locals only, not fields.
- `super::m` method references and instantiating Java inner (non-static) classes are not supported.
- Local functions and enum constants with bodies are not supported (D014).
- `foreach` over primitive sequences boxes elements.
- Cold-start latency for files that exercise heavy inference is about 0.5 s, above the 0.3 s single-file target; small files meet it.
- No IDE/LSP support, build-tool plugin or incremental compilation.
- v0.2/v0.3 features are not implemented: list patterns (parsed only), collection literals, query syntax.

**Deviations from the spec**, all in DECISIONS.md:
- Null checks guard every public/protected method, not only Java entry points (D045).
- Exception filters run after inner `finally` blocks, since the JVM has no filter pass (D050).
- Constant narrowing is allowed for arguments, ranked below exact and widening matches (D051).
- The common type of records sharing an interface is the interface (D054).
- Sequences are lazy (C# LINQ semantics) rather than "eager-by-default" (D063).
- `typeSwitch` is never used (D066).
- Platform-type member access is silent unless `--strict-platform-nullness` is given (D026).

**Next steps:**
1. A language server (diagnostics and completion already have stable codes and spans).
2. A Gradle plugin and Maven publication of the compiler and runtime under `io.github.matrixidot.jsharp`.
3. Incremental compilation using class-file metadata.
4. Grow inference toward full JLS 18 and extend smart casts to `val` fields.
5. v0.2 features: list patterns and collection literals.
6. Primitive-specialized `foreach` over `IntSequence`.
7. A per-package compile cache to cut cold-start time further.

## Post-v0.1: editor support
- Works: `jsharp lsp` (module `lsp/`, JDK-only) speaks the Language Server Protocol over stdio. It provides live diagnostics (identical to `jsharp check`), hover (expression types; method, property, field and class signatures), go to definition (locals, members, classes, top-level functions, across files), the document outline, and completion after `.` (members plus stdlib extensions, ranked own > inherited > Object) and of names in scope. It analyzes files in units without a project file (D072).
- Works: `editors/vscode`, a VS Code extension with a TextMate grammar (interpolated, raw and char literals, keywords, declarations), language configuration, and an LSP client. Highlighting works without npm; language features need `npm install`.
- Compiler support: `SourceIndex` (positions to symbols, types and declarations), plus `Compilation.recordExpressionTypes()` so the receivers of unfinished `x.` expressions keep their types.
- Tests: an LSP session test (initialize, open, diagnostics, hover, definition, symbols, completion, an edit that fixes an error, shutdown), workspace unit tests, index tests. Smoke-tested over real stdio on `examples/ledger`: 0.2 s per analysis, completion in 0.09 s.
- Not yet: rename, find references, signature help, formatting, incremental analysis, and a published `.vsix` (needs npm/vsce on the release machine).

## Post-v0.1: Gradle plugin
- Works: `plugins { id("io.github.matrixidot.jsharp") }` compiles `src/main/jsharp` and `src/test/jsharp` with the project's dependencies (D073). A TestKit functional test builds and runs a two-module project (J# using a Java library module, Java calling J# in the same module), checks the second compile is up to date, and checks that J# errors fail the build with full diagnostics. `compiler`, `runtime` and the plugin publish as Maven artifacts (verified into `build/repo`).
- Not yet: incremental compilation, publication to Maven Central and the Gradle plugin portal.

## Post-v0.1: language changes requested by the owner
- `base` replaces `open` (D075), `init { }` replaces the compact record constructor and adds instance initializers (D076), and more than one entry point is an error (D074).
- Operator overloading (D077) with readable JVM names for Java callers. Tested by an e2e program, checker files (declaration rules, syntax, use), and interop in both directions (Java calls `Item.plus`; a J# library's operators are used from another J# compilation).
- Fixed along the way: object initializers on `init` properties of compiled J# classes (D078).

## Post-v0.1: v0.2 language features
- Collection literals with spreads and map literals, target-typed (D080), and list patterns with slice bindings and exhaustiveness by length (D081). With operator overloading (D077), this completes the v0.2 language items of the spec. Structured concurrency (`Task.scope`) waits for `StructuredTaskScope` to leave preview in the JDK.
- Tests: e2e `collection_literals` and `list_patterns`, checker files for literal errors and list-pattern rules, and tour examples.

## Post-v0.1: joint Java/J# compilation
- Java and J# sources of one module now reference each other in both directions: `jsharp build`/`run` (javac runs in process after J#), the Gradle plugin (J# reads `src/<set>/java`; `compileJava` compiles it), and the language server (Java files join the unit; go to definition opens them). D082.
- Tests:
  - a differential test checking that source-read Java declarations equal class-file ones;
  - an interop program under `-Xverify:all` with a Java sealed interface, records, enums, generics, inner classes, a Java class extending a J# class, and J# implementing Java interfaces;
  - checker modules for JS1000/JS1001, duplicate classes, and access, nullness, sealed and abstract rules on Java sources;
  - plugin, CLI and LSP tests.

## Post-v0.1: functions as values, local functions
- `xs.select(twice)`, `xs.forEach(println)`, `Math.abs` and `list.add` as values, plus local functions with captures and recursion (D083). Also `sum()`/`average()`/`min()`/`max()` on collections of numbers without a selector.
- Tests: e2e `function_references` and `local_functions` (under `-Xverify:all`), checker files for every error case, and a tour section.

## Post-v0.1: atomic locals
- `atomic var count = 0;` lets lambdas, local functions and local classes update a local, atomically (D084). Uses java.util.concurrent.atomic cells, single atomic operations for `++`/`+=`/`-=`, compare-and-set loops otherwise, and warnings for two-step updates and needless `atomic`.
- Tests: e2e `atomic_locals` (1000 virtual threads, no lost updates, under `-Xverify:all`), checker files for each diagnostic, and a tour example.

## Post-v0.1: editor for demos
- VS Code extension rebuilt: no npm dependency, bundles the compiler (one `.vsix`, needs only Java 25), `./gradlew installVscodeExtension`. Adds references, highlights, rename, parameter hints and a ▶ Run button, plus hover and definition fixes for local functions, atomic locals, record components and enum constants (D086).
- `examples/showcase`: a multi-file tour program for demos, run by ExamplesTest.
- Tests: an LSP session test for hover, definition, references (writes included), rename across files and its refusals, signature help on unclosed calls, and the Run lens. The extension client was smoke-tested in Node against the bundled server, with a stub `vscode` module.

## Post-v0.1: IntelliJ plugin
- `editors/intellij`: a J# file type and highlighter (the compiler's lexer), the language server through the IDE's LSP client (completion, errors, hover, navigation, rename, parameter hints), brace and Enter handling, and run configurations with a gutter Run icon. The compiler is bundled and runs on the IDE's Java (D087).
- Tests: 9 headless-IDE tests (highlighting, Enter and brace behaviour, entry points and run configurations, running a program with its library files, the language server answering), and the Plugin Verifier against CLion 2026.2.

# Design decisions

Gaps filled in, or deviations from, `LANGUAGE_SPEC.md` / `ARCHITECTURE.md`. Newest last.
Format: **decision** — reason. *Rejected:* alternatives.

## 2026-10-02 — M0 scaffolding

- **D001: Project docs location.** The owner's `.gitignore` ignores `/docs` (the input spec
  documents). Files the project maintains live at the repo root (`DECISIONS.md`,
  `PROGRESS.md`, `README.md`); requested deliverables under `docs/` (`TOUR.md`,
  `BENCHMARKS.md`) are force-added individually. — Respects the owner's ignore rule while keeping
  deliverables versioned. *Rejected:* removing the ignore rule.
- **D002: JMH without a Gradle plugin.** `bench/` has a `jmh` source set with the JMH annotation
  processor and a `JavaExec` task running `org.openjdk.jmh.Main`. — Avoids plugin/Gradle-9
  compatibility risk; fewer moving parts. *Rejected:* `me.champeau.jmh` plugin.
- **D003: google-java-format version.** Spotless uses its default google-java-format (1.28.0);
  1.37.0 fails under Spotless 8.9 on JDK 25 (reflection mismatch). The Gradle daemon gets
  `--add-exports jdk.compiler/...` flags (in `gradle.properties`) that the formatter needs.
- **D004: Build version.** Single version in `gradle.properties`, injected into
  `io.github.matrixidot.jsharp.compiler.LanguageInfo` (which also holds the `jsharp`/`.jsharp`/`Module` naming
  constants the spec asks to keep in one place).

## 2026-10-02 — M1 lexer & parser

- **D005: Reserved vs contextual keywords.** Reserved: all Java keywords plus `foreach using typeof
  internal operator`. Everything else J#-specific is contextual (`var val record sealed base
  override async await required in out is as with when where get set init field value permits
  and or not params lock checked nameof`) so Java names like `System.out`, `System.in` and
  `Lock.lock()` stay usable. Any name can be escaped with backticks: `` `class` ``.
- **D006: `>` is always a single token.** The parser joins adjacent `>` tokens into `>>`, `>=`,
  `>>>`, `>>=`, `>>>=` in expression context; nested generics `List<List<T>>` need no token
  splitting.
- **D007: Generic methods use C# placement** — `T first<T>(List<T> xs) where T : Comparable<T>`.
  Java's `<T> T first(...)` is not accepted. Type-parameter bounds use `T : A & B` inline or
  `where` clauses (merged by the parser). *Rejected:* supporting both orders (two ways to say one
  thing).
- **D008: Top-level declarations vs statements.** At file top level, a `var/val/Type x = ...`
  declaration *without* modifiers is a statement (a local of the implicit entry point); *with*
  any modifier/annotation (`public val PI = 3.14;`, `final var z = 1;`) it is a module-level static
  field. Functions are recognized by `Type name(params)` followed by a body. — Keeps scripts and
  library files unambiguous without lookahead into semantics.
- **D009: Raw strings follow Java text blocks**: `"""` followed by a newline starts a multi-line
  raw string whose common indentation (closing delimiter included) and trailing whitespace are
  stripped; a closing delimiter on its own line leaves a trailing `\n`. No escapes are processed.
  Single-line `"""x"""` is taken verbatim. `$"""..."""` is the interpolated form.
- **D010: Interpolation holes** end at the first top-level `:` (format specifier) or `}`; a
  conditional `?:` inside a hole must be parenthesized (as in C#). Holes may contain nested
  strings, interpolations, braces and `::`.
- **D011: Numeric literals.** No octal and no leading zeros (`0123` is an error, avoiding Java's
  octal footgun); no leading-dot floats (`.5`), which keeps `1..5` and member access trivial.
  Integer range checks happen in the checker so `-2147483648` works.
- **D012: `throw;`** (C#-style rethrow inside `catch`) is accepted; `throw expr` is also an
  expression usable anywhere a value is expected (type: never).
- **D013: Java-isms get targeted errors** instead of cascades: `extends/implements`, `throws`,
  `for (T x : xs)`, `try (...)`, `X.class`, `T...` varargs each produce one error with the J#
  spelling as help.
- **D014: Not supported (spec does not require):** local functions, enum constants with bodies.
  Reported as JS0109 with a suggested alternative. `operator` declarations and `value class`
  are parsed far enough to report "reserved for a future version".
- **D015: `if`/`switch` as expressions.** In expression position `if (c) a else b` requires
  `else`; `switch (x) { arms }` is accepted as a prefix form of `x switch { arms }`. At statement
  start `switch` always begins a switch *statement* (`case pattern [when g]:` / `default:`).
- **D016: Use-site variance for Java interop** is written `List<out T>`, `List<in T>`, `List<?>`
  (Kotlin-like projections) rather than Java's `? extends` / `? super`.
- **D017: Annotations use Java syntax**; file-level annotations are those before `package` or
  with an explicit `@file:` target, e.g. `@file:ClassName("Util")`.
- **D018: `new T(args) { ... }`** is an object initializer when the brace is followed by
  `name =`, otherwise an anonymous class body. Target-typed `new(...)` and diamond `new T<>()`
  are both accepted.
- **D019: Range `..` binds looser than `+`/`-`** (Kotlin-like), so `1..n+1` means `1..(n+1)`.
  *Rejected:* C#'s tighter range precedence, which makes that expression an error.
- **D020: Nesting limit.** The parser reports JS0109 instead of overflowing the stack beyond 1000
  nested syntactic levels.
- **D021: Omitted return types** (spec 3.4) are accepted syntactically (`private helper(int x) =>
  x + 1;`); the checker enforces "private, non-recursive, expression-bodied only".

## 2026-10-02 — M2 declarations & resolution

- **D022: Default accessibility is `internal`** (JVM package-private) for types and members;
  interface members default to `public`. `internal` is the explicit spelling. *Rejected:*
  public-by-default (Kotlin) — the spec's examples write `public` explicitly and package-private
  maps 1:1 to the JVM.
- **D023: Named nested types are always static** (C#-like): they never capture an outer instance,
  so outer type parameters are not visible inside them. Only local and anonymous classes capture.
- **D024: Methods are final by default** in `base`/`abstract` classes (emitted `ACC_FINAL`);
  `base`, `abstract` and `override` members are overridable, and an `override` stays open unless
  marked `final override` (Kotlin model). Consistent with final-by-default classes.
- **D025: `sealed class` is implicitly abstract** (Kotlin). `sealed` without `permits` permits the
  direct subtypes declared in the same file (spec 4.1); permitted subtypes must be in the same
  package (JVM rule for the unnamed module).
- **D026: Java platform types are flexible** (Kotlin model): a platform `T!` is usable as `T` or
  `T?` without `!`. Member access through them is silent by default; `--strict-platform-nullness`
  turns on the spec's warning. Where a platform value flows into a non-null J# variable, parameter
  or return, a runtime null check is inserted. *Reason:* the spec's literal rule (warn on every
  member access, require `!` on every Java result) would flag `System.out.println` and
  `"a".trim()`; that cost outweighs the benefit. Nullness annotations (JSpecify, JetBrains,
  javax, Android, Checker, Eclipse, Spring...) and `@NullMarked` are honored.
- **D027: Type variables are non-null by default inside generic code** unless written `T?`;
  instantiation with nullable types (`List<String?>`) is allowed. This matches C# nullable
  reference types and is knowingly unsound at the margins.
- **D028: Primitive type arguments are boxed**: `List<int>` means `List<Integer>` (spec 7's
  `Function<int, int>`).
- **D029: Implicit imports** are `java.lang.*`, `jsharp.core.*`, and static imports of
  `jsharp.core.Prelude` and the stdlib extension containers (`jsharp.collections.Sequences`,
  `jsharp.text.Strings`). Explicit on-demand imports take precedence over implicit ones, and
  ambiguities between explicit ones are errors. `string` resolves to `java.lang.String` unless a
  type named `string` is in scope.
- **D030: Property JVM shape**: `getX`/`setX`, `isX` for primitive `boolean` (a property already
  named `isX` keeps its name; setter `setX`). An `init` accessor is a *synthetic* setter, so javac
  will not let Java code call it. The backing field is private and named like the property.
- **D031: Records** expose components as properties backed by the record accessor `x()`; records
  may not declare instance fields or backed properties (JVM/Java record rules). Explicit accessors
  must be public.
- **D032: Enum header parameters** (`enum Planet(double mass)`) become private final fields with
  public read-only properties (`getMass()`), plus a private constructor.
- **D033: Module classes** are named from the file name in PascalCase plus `Module`
  (`my_app.jsharp` -> `MyAppModule`), overridable with `@file:ClassName("X")`. At most one file per
  compilation may contain top-level statements (C# rule), which become `public static void
  main(String[])`.
- **D034: Variance (`in`/`out`) is only allowed on interface type parameters** (C# rule; classes
  have mutable state where variance would be unsound).
- **D035: Default argument values must be compile-time constants** (C# rule). They are recorded
  with `@jsharp.lang.DefaultValue` (class retention) so J# callers in other compilations can fill
  them in; Java callers get real trailing overloads (M5).
- **D036: J# metadata in class files** uses annotations in `jsharp.lang`: `@Metadata` (class
  compiled by J#; unannotated types are non-null), `@Extension`, `@DefaultValue`, `@NoReturn`
  (calls typed `never`, e.g. Prelude `error()`).
- **D037: Java-style generic methods** (`<T> T first(...)`) get a targeted error pointing to the
  J# order `T first<T>(...)`.

## 2026-10-03 — M3 type checker

- **D038: `override` is required for interface implementations too** (Kotlin rule), including in
  anonymous classes. Catches signature typos; error JS0617 with a fix hint.
- **D039: Smart casts apply to locals and parameters only** (not fields or properties, whose
  values may change between the check and the use). Narrowing comes from `!= null`, `is T`, `!`,
  early exits and assignments of non-null values.
- **D040: Reachability follows Java**: unreachable code is an error (JS0901), but `if` ignores
  constant conditions so `if (DEBUG)` works; definite assignment still uses constants.
- **D041: Switch rules.** Statement sections may not fall through (JS0906, last section exempt). A
  switch expression must be exhaustive (sealed hierarchies, enums, booleans, `_`), and a nullable
  selector needs a `null` arm. In switch statements a null selector goes to `default`. Arms
  dominated by earlier unguarded arms are errors (JS0652).
- **D042: Generic calls as arguments are poly expressions** (simplified JLS 18): they are typed
  against the selected overload's parameter type, so `collect(Collectors.toList())` and
  `sort(Comparator.comparing(String::length))` infer like Java. Implicit lambdas are checked
  speculatively per candidate overload.
- **D043: `new()` with a collection *interface* target** (`List<String> xs = new();`, spec 3.4)
  creates the default implementation: List/Collection/Iterable -> ArrayList, Map -> HashMap,
  Set -> HashSet, Queue/Deque -> ArrayDeque, Sorted/Navigable -> TreeMap/TreeSet.
- **D044: Private top-level declarations are file-private** (usable by any class in the file);
  they compile to package-private members of the module class.
- **D045: Parameter null checks at public entry points** use `Objects.requireNonNull` inside
  public/protected methods of J# classes (Kotlin's approach). *Deviation:* the spec asks that
  internal J# calls skip the check; doing that needs a second unchecked entry point per method,
  which breaks overriding. The JIT removes the check when the argument is known non-null.
- **D046: `==` on references** is `Objects.equals`, except for enums and `Class` which use
  identity; comparing provably unrelated types (`"a" == 1`) is an error (always false).
- **D047: Lambdas and local classes capture effectively-final locals only** (Java/javac shape).
- **D048: Interpolation formats** accept .NET-style `F2 N0 D5 X8 E3 P1 G` plus raw Java specs
  starting with `%`; formatting uses `Locale.ROOT` for reproducible output.
- **D049: Tuples** compile to `jsharp.core.Tuple2..Tuple8` records with boxed elements; element
  names exist only at compile time.
- **D050: Exception filters** `catch (E e) when (c)`: a `try` with any filtered clause compiles
  to one JVM handler for all its clause types that tests the clauses in source order (type, then
  filter) and rethrows the original exception when none matches, so a false filter falls through
  to the later clauses (C# semantics). Unlike the CLR's two-pass model, filters run after inner
  `finally` blocks have executed (the JVM has no filter phase).
- **D051: Constant narrowing in calls.** `f(byte)` accepts `f(10)` (C#-like, unlike Java), but
  the conversion ranks with boxing (applicable only in the loose phase), so an identity or
  widening overload always wins: `println(3)` calls `println(int)`, not `println(char)`.
- **D052: Code generation** uses the JDK ClassFile API with generated stack maps. Codegen tracks
  reachability and never emits statically dead statements; a call to a `@NoReturn`/`never`
  method is followed by `aconst_null; athrow` because the JVM cannot know it does not return.
  `finally` bodies are inlined at every exit (like javac); `lock` uses monitorenter/exit with a
  catch-all handler.
- **D053: `jsharp run`** compiles in memory and runs the entry point in the same JVM via a class
  loader (fast startup); `jsharp build` writes class files (`-d`, default `./out`) or a jar
  (`--jar`) with a `Main-Class` manifest entry. Jar entries are sorted with zero timestamps so
  builds are reproducible.
- **D054: Common type of records/enums.** When the only shared superclass is `Record` or `Enum`
  and exactly one shared interface exists that the base does not provide, the common type (lub)
  is that interface: `cond ? new Circle() : new Square()` is a `Shape`. Java would infer
  `Record & Shape`; J# has no intersection types and the interface is the useful half.
- **D055: `import p.*` imports top-level functions, values and extensions** of package `p` (its
  module classes; compiled module classes are marked `@Metadata(module = true)`), like Kotlin.
  Users never need to name a synthetic `XModule` class; `import static p.XModule.*` still works.
- **D056: Inside a record, reading a component on `this` reads its field** (Java rule), not the
  accessor; this lets an explicit accessor (`double c() => round(c)`) use the stored value.
- **D057: Arguments are evaluated in source order.** When named arguments reorder parameters,
  impure arguments are spilled into temporaries in the order written (C#/Kotlin rule).
- **D058: `I.super.m()`** calls the default method of a direct superinterface `I` (Java syntax and
  rule); it is the way to resolve conflicting defaults that a class must override.
- **D059: Overriding a get-only property may add a setter** (C# rule); the new setter is a new
  member, not an override.
- **D060: Inference additions** (all following javac): explicitly typed lambda parameters fix the
  function type's parameters; an *exact* method reference (one non-generic, non-varargs method by
  that name) constrains parameter and return types before its target is known, and method
  references are only potentially compatible with function types of a fitting arity; lambdas and
  method references are potentially compatible with a parameter typed by the candidate's own type
  variable; diamond-style constructor references (`TreeSet::new`) take their type arguments from
  the bound of the inference variable they produce (`C extends Collection<T>` gives
  `C = TreeSet<T>`); generic poly-call arguments wait until other arguments resolve their target;
  unconstrained variables are defaulted one at a time so dependent variables follow.
- **D061: Patterns on generic types** use the parameterization implied by the input's static type:
  `Res<Integer> r` matched by `Ok(var v)` infers `Ok<Integer>` (JLS 18.5.5), and testing
  `Ok<Integer>` is allowed because it is fully determined (a checked narrowing, JLS 5.1.6.1).
- **D062: Switch statements follow Java's exhaustiveness rule.** Only an "enhanced" switch
  statement (type/record patterns or `case null`) is checked for exhaustiveness and ends the flow
  when exhaustive; a classic constant switch (`case North:`) without `default` is not
  exhaustive, so code after it is reachable (and a trailing `return` is required). Case labels
  and switch-expression arms on an enum selector may name constants unqualified (`case North`).
  In a switch expression, an unguarded `null =>` arm makes the selector local non-null in the
  arms after it.
- **D063: Sequences are lazy, push-based pipelines with two traversal paths** (like
  `java.util.stream`): `forEach`/`forEachInt` (no early exit) for terminals that consume everything,
  and `forEachWhile`/`forEachWhileInt` (sink returns false to stop) for short-circuiting ones
  (`first`, `any`, `take`). Intermediate stages push through small named sink classes, because
  lambda sinks inline worse (measured in `docs/BENCHMARKS.md`). `foreach` over a sequence uses
  pull iterators. C# LINQ semantics: nothing runs before a terminal operation, and a sequence can
  be traversed again if its source can. `groupBy`/`toMap`/`toSet` return insertion-ordered
  `LinkedHashMap`/`LinkedHashSet`. Numeric sums over `T` are `sum` (int), `sumLong`, `sumDouble`,
  because implicit lambdas cannot choose between overloads that differ only in functional
  interface type (Java rule).
- **D064: The stdlib is written in J#** (`runtime/src/main/jsharp`), except what must exist before
  J# code can run: `jsharp.core` (Prelude, Task, tuples, Result) and the `jsharp.lang` metadata
  annotations. The runtime build compiles the J# half with the compiler's batch entry point, so
  the compiler never depends on the CLI or on itself.
- **D065: Constant switches.** A switch on `int`/`char`/`short`/`byte` whose labels are all
  constants (no guards) compiles to `tableswitch`/`lookupswitch` (javac's cost heuristic) only when
  it has at least 20 labels; smaller ones compile to compare chains, which C2 handles better:
  with uniformly random selectors, a 9-label switch ran in 80 µs as a chain vs 144 µs as a
  `tableswitch` (javac's choice), and a 32-label switch in 236 vs 184 µs (KernelBench
  `intSwitch`/`wideSwitch`; threshold overridable with `-Djsharp.switch.threshold=N` for
  experiments). String switches stay `equals` chains (211 vs javac's hashCode switch 251 µs for 8
  short labels); enum switches compare constants by identity, so separately compiled enums that
  reorder constants never break callers (javac needs a `$SwitchMap` class for that).
- **D066: Type-pattern switches compile to `instanceof` chains, never `SwitchBootstraps.typeSwitch`.**
  Spec 6 asked for a benchmark-based threshold; none was found. Chains beat javac's `typeSwitch`
  at 3 cases (19.3 vs 20.3 µs) and at 10 cases (169 vs 184 µs; KernelBench
  `patternSwitch`/`widePatternSwitch`, uniformly random subtypes). Chains also need no
  bootstrap or linkage at first use.
- **D067: Startup and compiler speed.** The Unix launcher uses a class-data-sharing archive that
  the JVM creates on first run in `${XDG_CACHE_HOME:-~/.cache}/jsharp` (only when writable: the
  JVM aborts if it cannot write the archive; `JSHARP_NO_CDS=1` disables it). Cold `jsharp check`
  of a small file takes ~0.26 s instead of ~0.37 s. C1-only compilation (`TieredStopAtLevel=1`)
  would shave a little more but would slow user programs started by `jsharp run`, so it is not
  used. A warm in-process compile of the generated 10k-line project (`scripts/gen_project.py`)
  takes ~0.4 s through codegen; `CompilerSpeedTest` fails above 2 s. `-Djsharp.timings=true`
  prints per-phase times.
- **D068: Package root `io.github.matrixidot.jsharp`** (owner's request, replacing `dev.jsharp`):
  `io.github.matrixidot.jsharp.{compiler,cli,tests,bench}`, Maven group
  `io.github.matrixidot.jsharp`. The language's own library namespace stays `jsharp.*`
  (`jsharp.core`, `jsharp.collections`, ...), like `kotlin.*`. Raw JMH results recorded before the
  rename (`bench/results/m4.json`, `m6.json`) keep the old benchmark class names.
- **D069: Deep nesting.** Compiler phases run on a thread with a 512 MB (reserved) stack, and the
  parser reports "code is nested too deeply" (JS0109) at ~1000 source levels or on a stack
  overflow, so pathological input never escapes as an internal error.
- **D070: Declaration-site variance for JDK interfaces.** J# source has no use-site wildcards, so
  a few JDK interfaces whose type parameters are used only as inputs or only as outputs get
  Kotlin-style variance when read from class files: `Comparable<in T>`, `Comparator<in T>`,
  `Callable<out V>`, and the `java.util.function` types (`Function<in T, out R>`,
  `Predicate<in T>`, `Consumer<in T>`, `Supplier<out T>`, `ToIntFunction<in T>`, ...). Then
  `LocalDate` (a `Comparable<ChronoLocalDate>`) satisfies `K : Comparable<K>`, and a
  `Predicate<Object>` is a `Predicate<String>`. This affects type checking only; bytecode is
  unchanged.
- **D071: Default-argument overloads for Java callers are capped at 8.** A method whose last `k`
  parameters have defaults gets `min(k, 8)` extra overloads, each dropping one more trailing
  parameter (Kotlin `@JvmOverloads` style). Defaults beyond the cap are still filled in by J#
  callers, which read `@DefaultValue`. Named-argument-only combinations get no overloads; Java
  has no named arguments.
- **D072: Editor tooling is a language server** (`jsharp lsp`, module `lsp/`, JDK-only like the
  compiler), not editor-specific plugins. It re-analyzes on every change with the real compiler,
  so editor diagnostics are exactly `jsharp check`'s. Hover, definition and outline come from
  `SourceIndex` (positions mapped to bound-tree nodes). Completion analyzes the text with a
  marker identifier at the cursor, and the checker records the types of subexpressions
  (`Compilation.recordExpressionTypes`), so the receiver of an unfinished `x.` has a type.
  Files are grouped into *units*: a file plus every `.jsharp` file under its source root that
  has no top-level statements, so folders of scripts and multi-file programs both work without
  a project file. Re-analysis is not incremental; it is fast enough for small and medium
  projects (~0.4 s per 10k lines warm).
- **D073: Gradle plugin** `io.github.matrixidot.jsharp` (module `gradle-plugin/`). It compiles each
  source set's `src/<name>/jsharp` in the Gradle process before `compileJava`, adds the output to
  the source set's classes (tests, jars and `run` see it) and to `compileJava`'s classpath, and
  adds the J# runtime to `implementation`. It also reads the source set's Java sources for their
  declarations (D082), so the two languages can use each other within a module. The task is
  cacheable but not incremental.
  `compiler`, `runtime` and the plugin publish as Maven artifacts under group
  `io.github.matrixidot.jsharp` (`publishToMavenLocal` today; Maven Central and the plugin portal
  need the owner's accounts).
- **D074: One entry point per program.** Top-level statements, a top-level `main` function and
  `static void main(String[])` methods all count; a compilation with more than one is error
  JS0411 (listing the others) instead of the tools picking one arbitrarily. Methods named `main`
  with other signatures, or instance methods, are not entry points.
- **D075: `base` replaces `open`** (owner's choice): `public base class Animal` allows
  inheritance and `public base String speak()` allows overriding. `open` (Kotlin's word) is an
  error, JS0410 "J# uses 'base' instead of 'open'", parsed as `base` so nothing else cascades.
  `base` is contextual, so it stays usable as an identifier. J# keeps Java's `super` for calls to
  the superclass, so `base` has no second meaning (unlike C#'s `base.Method()`).
- **D076: `init { ... }` blocks** (owner's choice, replacing Java's compact constructor syntax
  `public Point { ... }`, which now reports JS0113 with a fix). In a record, `init` is the compact
  canonical constructor (public): it runs before the fields are assigned and may validate or
  reassign the component parameters. In a class, it is an instance initializer that runs in
  every constructor, like Kotlin's `init`. `init` blocks take no modifiers; `init` stays
  contextual (it is also the init-only property accessor).
- **D077: Operator overloading** (owner's request, ahead of the spec's v0.2): `public static R
  operator <op>(params) body`. Overloadable: binary `+ - * / % & | ^ << >> >>>`, comparisons
  `< > <= >=` (must return `boolean`, declared in pairs `<`/`>` and `<=`/`>=`) and unary `- + ! ~`.
  Not overloadable: `==`/`!=`/`===` (those mean `equals`/identity), `&& || ??`, assignment and
  `++`/`--`. An operator must be `static`, non-generic, declared in a class (not at file top
  level), and take at least one operand of its declaring type. `a op b` looks for operator
  methods in the classes (and superclasses) of both operands when either operand is a class
  type without a built-in meaning (not String and not a boxed number), then picks one with
  normal overload resolution. String `+` and numeric operators never change meaning.
  `x op= y` is `x = op(x, y)` with `x` evaluated once. Each operator compiles to a static
  method with a readable JVM name (`plus`, `minus`, `times`, `div`, `rem`, `and`, `or`, `xor`,
  `shl`, `shr`, `ushr`, `lessThan`, `greaterThan`, `lessOrEqual`, `greaterOrEqual`, `unaryMinus`,
  `unaryPlus`, `not`, `inv`) annotated `@jsharp.lang.Operator("+")`, so Java calls
  `Vec.plus(a, b)` and other J# compilations recognize it. Diagnostics show `Vec.operator +(...)`.
- **D078: `init` accessors of compiled J# classes** (synthetic setters, D030) are loaded from
  class files of `@Metadata` classes, so J# object initializers work across compilations, while
  ordinary assignments through them are rejected (JS0628), as for source properties.
- **D079: No `out` or `ref` parameters** (decided with the owner). Their C# uses are covered
  better in J#: try-patterns by nullable returns with `is` patterns
  (`if (map.get(k) is V v)`, `if (s.toIntOrNull() is int n)`), multiple results by tuples
  (`(boolean, int) tryParse(...)` with `var (ok, n) = tryParse(s);`). On the JVM they would need
  hidden cell objects anyway (no performance gain) and give Java callers awkward `Ref<T>`
  parameters. Writing `out`/`ref` as a parameter or argument mode is a syntax error that points
  to these alternatives; both words remain ordinary identifiers. Likewise `int x, float y = f();`
  (a declarator with its own type) reports "all variables of a declaration share its type" and
  suggests `(int x, float y) = f();`.
- **D080: Collection literals are target-typed** (like C# collection expressions).
  - `[a, b, ..xs]` is an array for an array target; a `Set` for `Set`/`SequencedSet`; a filled
    instance for a concrete collection class with a public no-argument constructor
    (`ArrayList<T> xs = [...]` is mutable); otherwise an immutable `List` of the target's
    element type or of the elements' common type (numbers promote first:
    `[1, 2.5]` is `List<Double>`).
  - `{k: v}` is likewise an immutable map, or a filled `TreeMap`/`HashMap`/… for a concrete
    target; `{}` with a map target is an empty map.
  - **Deviation from the spec** (`List.of`/`Map.of`): literal sets and maps keep their written
    order (`LinkedHashSet`/`LinkedHashMap`, wrapped unmodifiable), because `Set.of`/`Map.of`
    iterate in an order that changes between runs. Lists use `List.of` unless they contain
    `null` or spreads.
  - Duplicate set elements collapse. A duplicate map key is a runtime
    `IllegalArgumentException`, and a compile error when both keys are constants (JS0659).
  - `..` spreads take an `Iterable` or an array (primitive arrays box) and are not allowed in
    array targets.
  - An empty literal needs a typed target. The `{` of a block or lambda body is never a literal.
  - Runtime helpers live in `jsharp.core.Literals`.
- **D081: List patterns** `[p0, p1, .. slice, q0]` match a `java.util.List` (by static type) or an
  array:
  - Without `..` the length must be exact; with one `..` it is a minimum, and the `..` may bind
    the middle (`.. var rest`: a `subList` view or an array copy).
  - Elements are read once by index; `_` positions are not read. Null never matches.
  - A list pattern on a static type that is not a List or array (e.g. `Object`) is an error
    (JS0651); test the type first.
  - Exhaustiveness counts lengths for patterns whose element patterns match anything, so
    `[]`, `[_]`, `[_, .., _]` is exhaustive and missing cases are reported as `[_]`,
    `[_, _, ..]`, ….
- **D082: Joint Java/J# compilation by reading Java declarations** (as Kotlin does), not by
  generating Java stubs for J#.
  - `JavaSourceLoader` parses `.java` files with javac's parser (`JavacTask.parse`, part of the
    JDK, so the compiler still has no external dependencies) and models their declarations as
    class symbols: classes, type parameters, supertypes, fields, methods and constructors. It
    also models what Java declares implicitly: default constructors, record accessors, canonical
    constructors, `equals`/`hashCode`/`toString`, enum `values`/`valueOf`, and interface members'
    implicit modifiers. Names resolve by Java's rules (member types including inherited ones,
    same file, single imports, package, on-demand imports, `java.lang`).
  - The symbols must equal what the class-file loader reads from javac's output for the same
    source; `JavaSourceLoaderTest` checks this on a fixture covering generics, wildcards, inner
    classes, records, enums with constant bodies, annotations and sealed types. Known
    differences: javac seals enums with constant bodies to anonymous classes that J# never
    names. Compact constructors keep their parameter names, which their class files lack.
  - Unannotated Java types are platform types, as for Java class files. Nullness annotations
    are recognized by simple name, and `@NullMarked` applies to its class. Constants are known
    for literal initializers (and negated numeric literals). Other constant expressions are read
    at run time.
  - After J# code generation, `jsharp build`/`run` invoke javac in process on the Java sources
    with the J# classes on the class path, and add javac's class files to the output. javac
    diagnostics become JS1000 (error) and JS1001 (warning) at the Java source position. Java
    syntax errors are reported when the declarations are read, before J# analysis.
  - The Gradle plugin uses `Compilation.javaDeclarationsOnly()`: `compileJava` compiles the Java
    sources (with annotation processing), against the J# output.
  - A class declared in both languages is JS0400. Java `main` methods count for the
    single-entry-point rule (D074), and a Java `main` is the entry point when J# has none.
  - Java sources must not depend on anything J# cannot see at declaration time, such as types
    generated by annotation processors. Such types stay unresolved (error types) in J#, and
    javac reports the real problem.

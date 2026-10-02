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
  `dev.jsharp.compiler.LanguageInfo` (which also holds the `jsharp`/`.jsharp`/`Module` naming
  constants the spec asks to keep in one place).

## 2026-10-02 — M1 lexer & parser

- **D005: Reserved vs contextual keywords.** Reserved: all Java keywords plus `foreach using typeof
  internal operator`. Everything else J#-specific is contextual (`var val record sealed open
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

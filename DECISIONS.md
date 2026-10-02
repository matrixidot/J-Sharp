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

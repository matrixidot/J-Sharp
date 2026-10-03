# Benchmarks

J# kernels (`bench/src/jsharp/bench/kernels.jsharp`) against hand-written Java equivalents
(`bench/src/jmh/java/io/github/matrixidot/jsharp/bench/JavaKernels.java`) on identical inputs. Run with
`./gradlew :bench:jmh` (add `-Pjmh.args='-rf json -rff out.json'` for raw results). Raw results
per milestone are in `bench/results/`.

Lower is better. "Ratio" is J# time / baseline time; anything within the error bars is noise.
`scripts/bench_table.py bench/results/<milestone>.json` regenerates a table from raw results.

## M4 baseline

Settings: JMH 1.37, `AverageTime`, 1 fork, 3×1 s warmup, 5×1 s measurement, µs/op.
Machine: Intel(R) Core(TM) Ultra 7 256V; `openjdk version "25.0.4.1" 2026-08-18`.

| Kernel | What it measures | Java (µs/op) | J# (µs/op) | Ratio |
|---|---|---:|---:|---:|
| fib | recursive fib(20) | 35.22 ± 1.93 | 35.30 ± 2.67 | 1.00 |
| interpolation | string interpolation in a loop (1k) | 32.12 ± 3.08 | 32.48 ± 2.05 | 1.01 |
| patternSwitch | type-pattern switch over sealed records (10k) | 21.43 ± 0.85 | 20.71 ± 2.99 | 0.97 |
| properties | property get/set in a particle simulation (1k × 10 steps) | 30.99 ± 3.30 | 31.10 ± 6.14 | 1.00 |
| streamPipeline | filter/mapToLong/sum over List<Integer> (10k), lambdas | 15.60 ± 1.41 | 15.74 ± 1.84 | 1.01 |
| sumArray | foreach over int[10k] | 1.85 ± 0.35 | 1.88 ± 0.34 | 1.02 |
| wordCount | HashMap.merge with Integer::sum (10k words) | 382.89 ± 45.92 | 388.36 ± 65.42 | 1.01 |

The J# compiler emits the same bytecode shapes javac does: `invokedynamic` lambdas through
`LambdaMetafactory`, `StringConcatFactory` concatenation, record methods through `ObjectMethods`,
and properties as plain getters/setters the JIT inlines. As a result, every kernel is within
measurement error of Java. Pattern switches are lowered to `instanceof` chains; javac uses
`SwitchBootstraps.typeSwitch`. At three cases the chain is no slower.

## M6 — stdlib sequences vs java.util.stream

`PipelineBench` pits J# sequences (`bench/src/jsharp/bench/pipelines.jsharp`, using the J#-written
`jsharp.collections`) against the equivalent `java.util.stream` code
(`JavaPipelines.java`). `KernelBench` is re-run as the regression check against M4. Settings are as
for M4, plus `-prof gc` (B/op = `gc.alloc.rate.norm`). The noisy pairs (`streamPipeline`,
`mapFilterCollect`, `boxed`) were re-run with 3 forks.

| Benchmark | Baseline (µs/op) | J# (µs/op) | Ratio | Baseline B/op | J# B/op |
|---|---:|---:|---:|---:|---:|
| KernelBench.fib | 34.29 ± 2.47 | 34.30 ± 2.57 | 1.00 | 0 | 0 |
| KernelBench.interpolation | 31.29 ± 0.83 | 30.88 ± 0.35 | 0.99 | 69,024 | 69,024 |
| KernelBench.patternSwitch | 20.66 ± 2.51 | 19.24 ± 1.69 | 0.93 | 0 | 0 |
| KernelBench.properties | 30.59 ± 1.22 | 30.61 ± 1.23 | 1.00 | 0 | 0 |
| KernelBench.streamPipeline | 37.63 ± 11.04 | 15.88 ± 0.74 | 0.42 | 325 | 280 |
| KernelBench.sumArray | 1.80 ± 0.13 | 1.81 ± 0.17 | 1.01 | 0 | 0 |
| KernelBench.wordCount | 373.85 ± 16.63 | 368.58 ± 38.95 | 0.99 | 24,291 | 24,291 |
| PipelineBench.array | 9.32 ± 0.62 | 9.14 ± 0.86 | 0.98 | 320 | 0 |
| PipelineBench.boxed | 36.97 ± 6.10 | 22.26 ± 7.39 | 0.60 | 325 | 3 |
| PipelineBench.firstMatch | 0.21 ± 0.02 | 0.04 ± 0.00 | 0.18 | 208 | 24 |
| PipelineBench.groupBy | 238.46 ± 15.19 | 225.97 ± 23.97 | 0.95 | 332,898 | 172,930 |
| PipelineBench.mapFilterCollect | 59.75 ± 0.91 | 65.02 ± 9.42 | 1.09 | 117,384 | 125,414 |
| PipelineBench.primitive | 11.93 ± 1.10 | 8.63 ± 0.70 | 0.72 | 264 | 0 |
| PipelineBench.topTen | 1957.66 ± 164.00 | 1972.43 ± 107.63 | 1.01 | 92,550 | 92,441 |

Findings:

- **Primitive and array pipelines allocate nothing** (0 B/op after escape analysis; Streams allocate
  ~260–320 B/op of pipeline objects). The `primitive` pipeline is 28% faster than `IntStream`.
- **Lambda-based stages were 25% slower than Streams; named sink classes fixed it.** The first design
  pushed elements through lambdas created inside each stage. A Java replica of that design was just
  as slow as the J# version, so the cause was the design, not J#'s code generation. Rewriting the
  intermediate stages as small named sink classes, like `java.util.stream`'s `Sink.Chained*`, made the
  `primitive` pipeline go from 14.6 to 8.6 µs.
- **Collection sources traverse through `spliterator().forEachRemaining`.** `ArrayList.forEach`
  re-checks `modCount` on every element; this change closed the `where`+`toList` gap (34 → 28 µs,
  same as Streams).
- **Short-circuiting is cheaper:** `first(pred)` is a plain early exit (0.04 µs vs 0.21 µs), because
  short-circuiting terminals use a separate `forEachWhile` traversal path.
- **`mapFilterCollect` is +9% with overlapping error bars** (an isolated 2-fork run measured
  62.9 vs 61.9 µs). Its cost is dominated by string concatenation and list growth.
- **`KernelBench.streamPipeline` is the same `java.util.stream` code in both languages.** Its
  0.42 ratio is bimodal JIT behavior of the Java baseline run on this machine (M4 measured
  15.6 µs for both), not a J# effect.

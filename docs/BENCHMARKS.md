# Benchmarks

J# kernels (`bench/src/jsharp/bench/kernels.jsharp`) against hand-written Java equivalents
(`bench/src/jmh/java/dev/jsharp/bench/JavaKernels.java`) on identical inputs. Run with
`./gradlew :bench:jmh` (add `-Pjmh.args='-rf json -rff out.json'` for raw results). Raw results
per milestone are in `bench/results/`.

Lower is better. "Ratio" is J# time / Java time; anything within the error bars is noise.

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

package dev.jsharp.bench;

import bench.KernelsModule;
import bench.Particle;
import bench.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Pairs of benchmarks: {@code jsharp_X} runs the J# kernel from {@code kernels.jsharp}, {@code
 * java_X} the hand-written Java equivalent in {@link JavaKernels}. Inputs are identical.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class KernelBench {
  private int[] ints;
  private List<Integer> boxed;
  private List<Shape> jsShapes;
  private List<JavaKernels.Shape> javaShapes;
  private String[] words;
  private Particle[] jsParticles;
  private JavaKernels.Particle[] javaParticles;

  @Setup
  public void setup() {
    Random r = new Random(42);
    ints = new int[10_000];
    boxed = new ArrayList<>();
    for (int i = 0; i < ints.length; i++) {
      ints[i] = r.nextInt(1000);
      boxed.add(ints[i]);
    }
    jsShapes = KernelsModule.makeShapes(10_000);
    javaShapes = JavaKernels.makeShapes(10_000);
    words = new String[10_000];
    for (int i = 0; i < words.length; i++) {
      words[i] = "w" + r.nextInt(500);
    }
    jsParticles = new Particle[1000];
    javaParticles = new JavaKernels.Particle[1000];
    for (int i = 0; i < 1000; i++) {
      double x = r.nextDouble() * 100;
      double y = r.nextDouble() * 100;
      double vx = r.nextDouble() - 0.5;
      double vy = r.nextDouble() - 0.5;
      jsParticles[i] = new Particle(x, y, vx, vy);
      javaParticles[i] = new JavaKernels.Particle(x, y, vx, vy);
    }
  }

  @Benchmark
  public int jsharp_fib() {
    return KernelsModule.fib(20);
  }

  @Benchmark
  public int java_fib() {
    return JavaKernels.fib(20);
  }

  @Benchmark
  public long jsharp_sumArray() {
    return KernelsModule.sumArray(ints);
  }

  @Benchmark
  public long java_sumArray() {
    return JavaKernels.sumArray(ints);
  }

  @Benchmark
  public long jsharp_streamPipeline() {
    return KernelsModule.sumSquaresOfEvens(boxed);
  }

  @Benchmark
  public long java_streamPipeline() {
    return JavaKernels.sumSquaresOfEvens(boxed);
  }

  @Benchmark
  public double jsharp_patternSwitch() {
    return KernelsModule.totalArea(jsShapes);
  }

  @Benchmark
  public double java_patternSwitch() {
    return JavaKernels.totalArea(javaShapes);
  }

  @Benchmark
  public int jsharp_interpolation() {
    return KernelsModule.buildStrings(1000);
  }

  @Benchmark
  public int java_interpolation() {
    return JavaKernels.buildStrings(1000);
  }

  @Benchmark
  public int jsharp_wordCount() {
    return KernelsModule.wordCount(words);
  }

  @Benchmark
  public int java_wordCount() {
    return JavaKernels.wordCount(words);
  }

  @Benchmark
  public double jsharp_properties() {
    return KernelsModule.simulate(jsParticles, 10);
  }

  @Benchmark
  public double java_properties() {
    return JavaKernels.simulate(javaParticles, 10);
  }
}

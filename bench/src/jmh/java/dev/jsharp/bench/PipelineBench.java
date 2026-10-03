package dev.jsharp.bench;

import bench.PipelinesModule;
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

/** J# sequences ({@code jsharp_X}) against java.util.stream ({@code stream_X}). */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PipelineBench {
  private int[] ints;
  private List<Integer> boxed;
  private List<String> words;

  @Setup
  public void setup() {
    Random r = new Random(7);
    ints = new int[10_000];
    boxed = new ArrayList<>();
    words = new ArrayList<>();
    for (int i = 0; i < ints.length; i++) {
      ints[i] = r.nextInt(1000);
      boxed.add(ints[i]);
      words.add((char) ('a' + r.nextInt(26)) + "w" + i);
    }
  }

  @Benchmark
  public int jsharp_primitive() {
    return PipelinesModule.primitivePipeline(10_000);
  }

  @Benchmark
  public int stream_primitive() {
    return JavaPipelines.primitivePipeline(10_000);
  }

  @Benchmark
  public int jsharp_array() {
    return PipelinesModule.arrayPipeline(ints);
  }

  @Benchmark
  public int stream_array() {
    return JavaPipelines.arrayPipeline(ints);
  }

  @Benchmark
  public long jsharp_boxed() {
    return PipelinesModule.boxedPipeline(boxed);
  }

  @Benchmark
  public long stream_boxed() {
    return JavaPipelines.boxedPipeline(boxed);
  }

  @Benchmark
  public int jsharp_groupBy() {
    return PipelinesModule.groupCount(words);
  }

  @Benchmark
  public int stream_groupBy() {
    return JavaPipelines.groupCount(words);
  }

  @Benchmark
  public List<Integer> jsharp_topTen() {
    return PipelinesModule.topTen(boxed);
  }

  @Benchmark
  public List<Integer> stream_topTen() {
    return JavaPipelines.topTen(boxed);
  }

  @Benchmark
  public int jsharp_firstMatch() {
    return PipelinesModule.firstMatch(boxed);
  }

  @Benchmark
  public int stream_firstMatch() {
    return JavaPipelines.firstMatch(boxed);
  }

  @Benchmark
  public List<String> jsharp_mapFilterCollect() {
    return PipelinesModule.mapFilterCollect(boxed);
  }

  @Benchmark
  public List<String> stream_mapFilterCollect() {
    return JavaPipelines.mapFilterCollect(boxed);
  }
}

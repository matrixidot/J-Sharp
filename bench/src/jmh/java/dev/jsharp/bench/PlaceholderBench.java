package dev.jsharp.bench;

import org.openjdk.jmh.annotations.Benchmark;

/** Placeholder benchmark until J# codegen exists (M4). */
public class PlaceholderBench {
  @Benchmark
  public int baseline() {
    return 42;
  }
}

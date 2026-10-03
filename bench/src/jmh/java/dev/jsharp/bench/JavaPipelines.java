package dev.jsharp.bench;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** java.util.stream equivalents of {@code src/jsharp/bench/pipelines.jsharp}. */
final class JavaPipelines {
  private JavaPipelines() {}

  static int primitivePipeline(int n) {
    return IntStream.range(0, n).filter(i -> i % 3 == 0).map(i -> i * i).sum();
  }

  static int arrayPipeline(int[] xs) {
    return Arrays.stream(xs).filter(x -> x > 500).map(x -> x * 2).sum();
  }

  static long boxedPipeline(List<Integer> xs) {
    return xs.stream().filter(x -> x % 2 == 0).mapToLong(x -> (long) x * x).sum();
  }

  static int groupCount(List<String> words) {
    return words.stream().collect(Collectors.groupingBy(w -> w.charAt(0))).size();
  }

  static List<Integer> topTen(List<Integer> xs) {
    return xs.stream().sorted(Comparator.reverseOrder()).limit(10).toList();
  }

  static int firstMatch(List<Integer> xs) {
    return xs.stream().filter(x -> x > 990).findFirst().orElseThrow();
  }

  static List<String> mapFilterCollect(List<Integer> xs) {
    return xs.stream().filter(x -> x % 7 == 0).map(x -> "n" + x).toList();
  }
}

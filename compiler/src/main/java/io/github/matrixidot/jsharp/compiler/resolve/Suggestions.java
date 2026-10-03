package io.github.matrixidot.jsharp.compiler.resolve;

import java.util.Collection;
import java.util.Locale;

/** "Did you mean" support: finds the closest candidate name by edit distance. */
public final class Suggestions {
  private Suggestions() {}

  /** Returns the best candidate within a length-dependent threshold, or null. */
  public static String closest(String name, Collection<String> candidates) {
    String best = null;
    int bestDist = Integer.MAX_VALUE;
    String lower = name.toLowerCase(Locale.ROOT);
    for (String c : candidates) {
      if (c.equals(name)) {
        continue;
      }
      if (c.toLowerCase(Locale.ROOT).equals(lower)) {
        return c; // case-only difference is the strongest signal
      }
      int d = distance(lower, c.toLowerCase(Locale.ROOT));
      if (d < bestDist || (d == bestDist && best != null && c.compareTo(best) < 0)) {
        bestDist = d;
        best = c;
      }
    }
    // Short names are only suggested for small, likely typos ('s' -> 'o' would be noise).
    int len = name.length();
    int threshold = len <= 2 ? 0 : len <= 4 ? 1 : len <= 8 ? 2 : 3;
    return bestDist <= threshold ? best : null;
  }

  /** Optimal string alignment (Damerau-Levenshtein with adjacent transpositions). */
  static int distance(String a, String b) {
    int n = a.length();
    int m = b.length();
    if (Math.abs(n - m) > 3) {
      return Math.abs(n - m);
    }
    int[][] d = new int[n + 1][m + 1];
    for (int i = 0; i <= n; i++) {
      d[i][0] = i;
    }
    for (int j = 0; j <= m; j++) {
      d[0][j] = j;
    }
    for (int i = 1; i <= n; i++) {
      for (int j = 1; j <= m; j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
        if (i > 1
            && j > 1
            && a.charAt(i - 1) == b.charAt(j - 2)
            && a.charAt(i - 2) == b.charAt(j - 1)) {
          d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
        }
      }
    }
    return d[n][m];
  }
}

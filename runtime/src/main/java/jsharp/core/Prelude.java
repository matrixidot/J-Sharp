package jsharp.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import jsharp.lang.NoReturn;

/**
 * Functions available in every J# file without an import ({@code println}, {@code require}, ...).
 * Primitive overloads avoid boxing.
 */
public final class Prelude {
  private Prelude() {}

  public static void println() {
    System.out.println();
  }

  public static void println(Object value) {
    System.out.println(value);
  }

  public static void println(String value) {
    System.out.println(value);
  }

  public static void println(int value) {
    System.out.println(value);
  }

  public static void println(long value) {
    System.out.println(value);
  }

  public static void println(double value) {
    System.out.println(value);
  }

  public static void println(float value) {
    System.out.println(value);
  }

  public static void println(boolean value) {
    System.out.println(value);
  }

  public static void println(char value) {
    System.out.println(value);
  }

  public static void print(Object value) {
    System.out.print(value);
  }

  public static void print(String value) {
    System.out.print(value);
  }

  public static void print(int value) {
    System.out.print(value);
  }

  public static void print(long value) {
    System.out.print(value);
  }

  public static void print(double value) {
    System.out.print(value);
  }

  public static void print(boolean value) {
    System.out.print(value);
  }

  public static void print(char value) {
    System.out.print(value);
  }

  private static BufferedReader stdin;

  /** Reads a line from standard input; returns null at end of input. */
  public static synchronized String readLine() {
    if (stdin == null) {
      stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }
    try {
      return stdin.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Throws {@link IllegalArgumentException} if {@code condition} is false. */
  public static void require(boolean condition) {
    if (!condition) {
      throw new IllegalArgumentException("Failed requirement.");
    }
  }

  /** Throws {@link IllegalArgumentException} with {@code message} if {@code condition} is false. */
  public static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  /** Throws {@link IllegalStateException} if {@code condition} is false. */
  public static void check(boolean condition) {
    if (!condition) {
      throw new IllegalStateException("Check failed.");
    }
  }

  /** Throws {@link IllegalStateException} with {@code message} if {@code condition} is false. */
  public static void check(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  /** Always throws {@link IllegalStateException}. */
  @NoReturn
  public static RuntimeException error(String message) {
    throw new IllegalStateException(message);
  }

  /** Marks unfinished code; always throws {@link UnsupportedOperationException}. */
  @NoReturn
  public static RuntimeException todo() {
    throw new UnsupportedOperationException("Not implemented.");
  }
}

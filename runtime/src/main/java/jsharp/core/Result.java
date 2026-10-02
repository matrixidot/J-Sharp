package jsharp.core;

import java.util.function.Function;

/**
 * The outcome of an operation that may fail: {@link Ok} with a value or {@link Err} with an error.
 *
 * @param <T> success value type
 * @param <E> error type
 */
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

  /** A successful result. */
  record Ok<T, E>(T value) implements Result<T, E> {}

  /** A failed result. */
  record Err<T, E>(E error) implements Result<T, E> {}

  static <T, E> Result<T, E> ok(T value) {
    return new Ok<>(value);
  }

  static <T, E> Result<T, E> err(E error) {
    return new Err<>(error);
  }

  default boolean isOk() {
    return this instanceof Ok;
  }

  default boolean isErr() {
    return this instanceof Err;
  }

  /** The value, or {@code fallback} if this is an error. */
  default T getOrElse(T fallback) {
    return this instanceof Ok<T, E> ok ? ok.value() : fallback;
  }

  /** The value; throws {@link IllegalStateException} if this is an error. */
  default T getOrThrow() {
    return switch (this) {
      case Ok<T, E> ok -> ok.value();
      case Err<T, E> err -> throw new IllegalStateException("Result is an error: " + err.error());
    };
  }

  default <U> Result<U, E> map(Function<? super T, ? extends U> f) {
    return switch (this) {
      case Ok<T, E> ok -> new Ok<>(f.apply(ok.value()));
      case Err<T, E> err -> new Err<>(err.error());
    };
  }

  default <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> f) {
    return switch (this) {
      case Ok<T, E> ok -> f.apply(ok.value());
      case Err<T, E> err -> new Err<>(err.error());
    };
  }
}

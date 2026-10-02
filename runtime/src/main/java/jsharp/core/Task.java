package jsharp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The result of an {@code async} computation. A {@code Task} <em>is</em> a {@link
 * CompletableFuture} (and so a {@link java.util.concurrent.CompletionStage} and {@link Future}),
 * which gives Java callers zero-ceremony interop. Async bodies run on virtual threads; {@code
 * await} parks the current virtual thread until the task completes.
 *
 * @param <T> result type
 */
public final class Task<T> extends CompletableFuture<T> {
  private static final Executor VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();

  public Task() {}

  @Override
  public <U> CompletableFuture<U> newIncompleteFuture() {
    return new Task<>();
  }

  /** Runs {@code body} on a new virtual thread. */
  public static <T> Task<T> run(Callable<T> body) {
    Task<T> t = new Task<>();
    VIRTUAL.execute(
        () -> {
          try {
            t.complete(body.call());
          } catch (Throwable e) {
            t.completeExceptionally(e);
          }
        });
    return t;
  }

  /** Runs {@code body} on a new virtual thread; the task completes with {@code null}. */
  public static Task<Void> runVoid(Runnable body) {
    return run(
        () -> {
          body.run();
          return null;
        });
  }

  /** A task that is already complete. */
  public static <T> Task<T> completed(T value) {
    Task<T> t = new Task<>();
    t.complete(value);
    return t;
  }

  /** A task that has already failed. */
  public static <T> Task<T> failed(Throwable error) {
    Task<T> t = new Task<>();
    t.completeExceptionally(error);
    return t;
  }

  /** Adapts any future to a task. */
  public static <T> Task<T> from(Future<T> future) {
    if (future instanceof Task<T> t) {
      return t;
    }
    if (future instanceof CompletableFuture<T> cf) {
      Task<T> t = new Task<>();
      cf.whenComplete(
          (v, e) -> {
            if (e != null) {
              t.completeExceptionally(unwrap(e));
            } else {
              t.complete(v);
            }
          });
      return t;
    }
    return run(() -> await(future));
  }

  /** Completes after {@code millis} milliseconds. */
  public static Task<Void> delay(long millis) {
    Task<Void> t = new Task<>();
    CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS, VIRTUAL)
        .execute(() -> t.complete(null));
    return t;
  }

  /** Completes with all results (in order) when every task has completed. */
  @SafeVarargs
  public static <T> Task<List<T>> whenAll(Future<? extends T>... tasks) {
    List<Future<? extends T>> list = new ArrayList<>(tasks.length);
    for (Future<? extends T> f : tasks) {
      list.add(f);
    }
    return whenAll(list);
  }

  /** Completes with all results (in order) when every task has completed. */
  public static <T> Task<List<T>> whenAll(List<? extends Future<? extends T>> tasks) {
    return run(
        () -> {
          List<T> out = new ArrayList<>(tasks.size());
          for (Future<? extends T> f : tasks) {
            out.add(await(f));
          }
          return out;
        });
  }

  /** Completes with the first result of any of the tasks. */
  @SafeVarargs
  public static <T> Task<T> whenAny(Future<? extends T>... tasks) {
    Task<T> t = new Task<>();
    for (Future<? extends T> f : tasks) {
      from(f)
          .whenComplete(
              (v, e) -> {
                if (e != null) {
                  t.completeExceptionally(unwrap(e));
                } else {
                  t.complete(v);
                }
              });
    }
    return t;
  }

  /**
   * Waits for {@code future} and returns its value, rethrowing its failure unwrapped. This is what
   * {@code await} compiles to.
   */
  public static <T> T await(Future<T> future) {
    try {
      return future.get();
    } catch (ExecutionException e) {
      throw sneaky(e.getCause() != null ? e.getCause() : e);
    } catch (CompletionException e) {
      throw sneaky(unwrap(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw sneaky(e);
    }
  }

  /** Waits for this task: {@code await task}. */
  public T await() {
    return await(this);
  }

  private static Throwable unwrap(Throwable e) {
    while ((e instanceof CompletionException || e instanceof ExecutionException)
        && e.getCause() != null) {
      e = e.getCause();
    }
    return e;
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> RuntimeException sneaky(Throwable e) throws E {
    throw (E) e;
  }
}

package dev.horizon.cinder;

import io.vertx.core.Future;

/**
 * Wraps the run of each statement. An interceptor calls the next one, or gives a result of its own, such as a cached
 * one.
 *
 * <pre>{@code
 * public <R> Future<R> intercept(Invocation<R> invocation, Chain<R> next) {
 *   long start = System.nanoTime();
 *   return next.proceed(invocation).andThen(done -> record(invocation, System.nanoTime() - start));
 * }
 * }</pre>
 */
public interface Interceptor {

  /**
   * Runs a statement.
   *
   * @param invocation the statement, its kind and its model
   * @param next the interceptors after this one, and the run of the statement at the end
   * @param <R> the type of the result
   * @return the result that the next one gives, or one of its own
   */
  <R> Future<R> intercept(Invocation<R> invocation, Chain<R> next);

  /**
   * The interceptors after this one, and the run of the statement at the end.
   *
   * @param <R> the type of the result
   */
  interface Chain<R> {

    /**
     * Runs a statement through the rest of the chain.
     *
     * @param invocation the invocation, or another in its place, such as one with other values
     * @return the result of the statement, or of an interceptor further down the chain
     */
    Future<R> proceed(Invocation<R> invocation);
  }
}

package dev.horizon.cinder;

import dev.horizon.basepoint.RenderedStatement;

/**
 * The run of one statement, after the decorators, which an {@link Interceptor} wraps.
 *
 * @param kind what the statement does
 * @param model the model of the operation that runs the statement
 * @param statement the SQL and its values
 * @param <R> the type of the result
 */
public record Call<R>(Kind kind, Model<?> model, RenderedStatement statement) {

  /**
   * What a statement does.
   */
  public enum Kind {
    /** Reads rows. */
    QUERY,
    /** Inserts rows, or merges them into the rows of their keys. */
    INSERT,
    /** Updates rows. */
    UPDATE,
    /** Deletes rows. */
    DELETE
  }
}

package dev.horizon.cinder;

/**
 * What an operation gives when it runs, in the shape the terminal picks: a future of a list, a count or the groups of
 * an aggregate, or a stream of entities. An extension runs its own with {@link Operation#run(Terminal)}.
 *
 * <p>
 * A terminal that reads entities works for one type. A terminal that reads only values, such as {@link Aggregate},
 * is a terminal of {@code Object} and works for every type.
 *
 * @param <E> the type of the entities that the terminal reads
 * @param <R> the type of the result
 */
public interface Terminal<E, R> {

  /**
   * Runs the statement of an operation.
   *
   * @param execution the statement, with the means to run it and read its rows
   * @return the result, such as a {@code Future} or a {@code ReadStream}
   */
  R run(Execution<? extends E> execution);
}

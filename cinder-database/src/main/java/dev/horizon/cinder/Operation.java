package dev.horizon.cinder;

/**
 * A statement on the rows of a model that has not run yet: a {@link Query}, an {@link Insert}, an {@link Update} or a
 * {@link Delete}. Each method gives a new operation.
 *
 * <p>
 * The methods of an operation cover what every database does. Anything else goes through the two open methods:
 * {@link #with(Part)} adds a part, such as a recursive filter of a graph, and {@link #run(Terminal)} runs the
 * operation for a result of its own, such as the groups of an aggregate. The common methods are parts and terminals
 * too, so an extension can do all that they do.
 *
 * @param <E> the type of the entities
 * @param <O> the type of the operation
 */
public interface Operation<E, O extends Operation<E, O>> {

  /**
   * Gives the model of the rows.
   *
   * @return the model
   */
  Model<E> model();

  /**
   * Adds a part, which changes the statement before it runs and the entities after.
   *
   * @param part the part
   * @return the operation, with the part after the others
   */
  O with(Part part);

  /**
   * Runs the operation.
   *
   * @param terminal what to make of the statement and its rows
   * @param <R> the type of the result
   * @return the result of the terminal, such as a {@code Future} or a {@code ReadStream}
   */
  <R> R run(Terminal<? super E, R> terminal);
}

package dev.horizon.cinder;

import java.time.Instant;
import java.util.Optional;

/**
 * Where an operation runs: its database, its model, what it does and when. A part reads it to know the fields of the
 * statement, and to run more operations, such as the query of an include.
 *
 * @param <E> the type of the entities
 */
public interface Context<E> {

  /**
   * Gives the database of the operation.
   *
   * @return the database. Its operations run in the transaction of the operation, if it has one.
   */
  Database database();

  /**
   * Gives the model of the operation.
   *
   * @return the model
   */
  Model<E> model();

  /**
   * Gives the transaction of the operation.
   *
   * @return the transaction, or empty if the operation runs without one, so that each of its statements commits as it
   * runs
   */
  Optional<Transaction> transaction();

  /**
   * Gives what the operation does.
   *
   * @return {@code QUERY}, {@code INSERT}, {@code UPDATE} or {@code DELETE}. The insert of a conflict policy that
   * merges is an {@code INSERT}.
   */
  Call.Kind kind();

  /**
   * Gives the time of the operation, from the clock of the database. It is the same for every row of the operation,
   * so that the times that one write stamps are equal.
   *
   * @return the time
   */
  Instant now();
}

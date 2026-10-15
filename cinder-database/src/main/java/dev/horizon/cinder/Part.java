package dev.horizon.cinder;

import dev.horizon.basepoint.Statement;
import io.vertx.core.Future;
import java.util.List;

/**
 * A part of an operation. It changes the rows that a write sends, the Basepoint statement before it runs, and the
 * entities after, as an include reads the related rows into them. Each method defaults to no change, so a part
 * overrides only what it changes.
 *
 * <p>
 * A part goes in one operation with {@link Operation#with(Part)}, or in every operation of a model with
 * {@link Extension#parts(Model)}, as tenancy, soft deletes, keys, times and versions do. The parts of the extensions
 * come before the parts of the operation.
 */
public interface Part {

  /**
   * Changes a row that the operation writes: the values of each entity of an insert, or the changes of an update.
   *
   * @param row the row, by field, with the changes of the parts before this one. A value that the database computes,
   * such as {@code CURRENT_TIMESTAMP}, is an expression, and the write reads it back with {@code RETURNING}.
   * @param context the database, the model, the kind and the time of the operation
   * @return the changed row
   */
  default Assignments write(Assignments row, Context<?> context) {
    return row;
  }

  /**
   * Changes the statement of the operation, before the decorators.
   *
   * @param statement the statement, with the changes of the parts before this one. Its expressions name the fields
   * of the model, and its clauses of names, such as {@code RETURNING}, name the columns. A part narrows a query, an
   * update or a delete with {@code statement.where(filter)}.
   * @param context the database, the model, the kind and the time of the operation
   * @return the changed statement
   */
  default Statement shape(Statement statement, Context<?> context) {
    return statement;
  }

  /**
   * Changes the entities that the operation read or returned.
   *
   * @param entities the entities, with the changes of the parts before this one
   * @param context the database and the model of the operation, to run more operations on
   * @param <E> the type of the entities
   * @return the changed entities
   */
  default <E> Future<List<E>> complete(List<E> entities, Context<E> context) {
    return Future.succeededFuture(entities);
  }
}

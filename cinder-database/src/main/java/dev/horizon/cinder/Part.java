package dev.horizon.cinder;

import dev.horizon.basepoint.statement.Statement;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;

/**
 * A part of an operation. It changes the fields that a write sends, the Basepoint statement before it runs, and the
 * entities after. Each method defaults to no change, so a part overrides only what it changes.
 *
 * <p>
 * A part goes in the operations of one table with {@link Table#with(Part)}, or in every operation of a model with
 * {@link Extension#parts(Model)}, as tenancy, soft deletes, keys, times and versions do. The parts of the extensions
 * come before the parts of the table.
 */
public interface Part {

  /**
   * Changes the fields that the operation writes: the values of each entity of an insert, or the fields that an
   * update sets.
   *
   * @param row the fields, by name, with the changes of the parts before this one. A value is a value of the field,
   * {@code null}, or a Basepoint expression that the database computes, such as {@code version + 1}. A field that
   * the row leaves out keeps the default of its column in an insert, and its value in an update.
   * @param context the database, the model, the kind and the time of the operation
   * @return the changed fields
   */
  default Map<String, Object> write(Map<String, Object> row, Context<?> context) {
    return row;
  }

  /**
   * Changes the statement of the operation, before the decorators.
   *
   * @param statement the statement, with the changes of the parts before this one. It names the fields of the model,
   * which Cinder maps to their columns after the parts. A part narrows a query, an update or a delete with
   * {@code statement.where(filter)}.
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

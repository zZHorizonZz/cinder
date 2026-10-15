package dev.horizon.cinder;

import dev.horizon.basepoint.Filter;
import io.vertx.core.Future;
import java.util.List;

/**
 * A delete of rows of a model. Each method gives a new delete, and nothing runs until a method gives a future. A
 * delete needs a filter or {@link #all()}, so that no delete removes every row by mistake.
 *
 * @param <E> the type of the entities
 */
public interface Delete<E> extends Operation<E, Delete<E>> {

  /**
   * Adds a condition. The conditions of a delete must all hold.
   *
   * @param filter the condition, whose names are fields of the model
   * @return the delete, with the condition
   */
  Delete<E> where(Filter filter);

  /**
   * Adds the condition of an AIP-160 filter. The conditions of a delete must all hold.
   *
   * @param filter the filter, whose names are fields of the model
   * @return the delete, with the condition
   * @throws IllegalArgumentException if the filter is not valid, or names a field that the model does not have
   */
  Delete<E> where(String filter);

  /**
   * Deletes every row of the table.
   *
   * @return the delete, without a condition
   */
  Delete<E> all();

  /**
   * Runs the delete.
   *
   * @return the number of deleted rows, or a future that fails with an {@link IllegalStateException} if the delete
   * has neither a filter nor {@link #all()}
   */
  Future<Long> execute();

  /**
   * Runs the delete, and reads back the rows it deleted, with RETURNING where the database has it.
   *
   * @return the entities as they were stored
   */
  Future<List<E>> returning();
}

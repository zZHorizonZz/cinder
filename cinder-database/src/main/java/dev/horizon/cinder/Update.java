package dev.horizon.cinder;

import dev.horizon.basepoint.Filter;
import io.vertx.core.Future;
import java.util.List;

/**
 * An update of rows of a model. Each method gives a new update, and nothing runs until a method gives a future. An
 * update needs a filter or {@link #all()}, so that no update changes every row by mistake.
 *
 * @param <E> the type of the entities
 */
public interface Update<E> extends Operation<E, Update<E>> {

  /**
   * Adds a condition. The conditions of an update must all hold.
   *
   * @param filter the condition, whose names are fields of the model
   * @return the update, with the condition
   */
  Update<E> where(Filter filter);

  /**
   * Adds the condition of an AIP-160 filter. The conditions of an update must all hold.
   *
   * @param filter the filter, whose names are fields of the model
   * @return the update, with the condition
   * @throws IllegalArgumentException if the filter is not valid, or names a field that the model does not have
   */
  Update<E> where(String filter);

  /**
   * Updates every row of the table.
   *
   * @return the update, without a condition
   */
  Update<E> all();

  /**
   * Adds changes.
   *
   * @param assignments the changes to the fields of each row
   * @return the update, with the changes after the others
   */
  Update<E> set(Assignments assignments);

  /**
   * Sets the fields of an AIP-134 update mask to their values in an entity.
   *
   * @param entity the entity, such as the one of an update request
   * @param updateMask the paths of the fields, separated by commas, such as {@code state,total_cents}, or {@code *}
   * for every field but the key
   * @return the update, with a change per field of the mask
   * @throws IllegalArgumentException if the mask names a field that the model does not have, or a key field
   */
  Update<E> set(E entity, String updateMask);

  /**
   * Runs the update.
   *
   * @return the number of updated rows, or a future that fails with an {@link IllegalStateException} if the update
   * has neither a filter nor {@link #all()}
   */
  Future<Long> execute();

  /**
   * Runs the update, and reads back the rows it changed, with RETURNING where the database has it.
   *
   * @return the entities as stored after the update
   */
  Future<List<E>> returning();
}

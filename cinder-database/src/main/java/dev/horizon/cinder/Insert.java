package dev.horizon.cinder;

import io.vertx.core.Future;
import java.util.List;

/**
 * An insert of rows of a model. Each method gives a new insert, and nothing runs until a method gives a future.
 *
 * @param <E> the type of the entities
 */
public interface Insert<E> extends Operation<E, Insert<E>> {

  /**
   * Adds a row.
   *
   * @param entity the entity of the row
   * @return the insert, with the row after the others
   */
  Insert<E> values(E entity);

  /**
   * Adds rows.
   *
   * @param entities the entities of the rows
   * @return the insert, with the rows after the others
   */
  Insert<E> values(List<E> entities);

  /**
   * Sets what happens to a row whose key or unique fields another row has. Without a conflict policy, the insert
   * fails.
   *
   * @param conflict the policy, such as {@link Conflict#ignore()}
   * @return the insert, with the policy
   */
  Insert<E> onConflict(Conflict conflict);

  /**
   * Runs the insert.
   *
   * @return the number of inserted or merged rows, or a future that fails with a {@link DuplicateKeyException} if a row
   * conflicts without a policy
   */
  Future<Long> execute();

  /**
   * Runs the insert, and reads back the rows it inserted or merged, with RETURNING where the database has it.
   *
   * @return the entities as stored, with the values that the database computed, in the order given. A row that the policy
   * ignored is left out.
   */
  Future<List<E>> returning();
}

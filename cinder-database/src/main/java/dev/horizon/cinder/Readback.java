package dev.horizon.cinder;

import io.vertx.core.Future;
import java.util.List;

/**
 * How a database gives back the rows of a write, as the table holds them after it: from the write statement itself,
 * or by reading them again by their keys.
 */
sealed interface Readback permits StatementReadback, KeyReadback {

  /**
   * Gives the readback of a driver.
   */
  static Readback of(Driver driver) {
    return driver.returnsWrittenRows() ? new StatementReadback() : new KeyReadback();
  }

  /**
   * Tells whether an update or a delete that gives back its rows runs in a transaction, because it reads them under
   * a lock.
   */
  boolean needsTransaction();

  <E> Future<List<E>> insert(TableInsert<E> insert);

  <E> Future<List<E>> update(TableUpdate<E> update);

  <E> Future<List<E>> delete(TableDelete<E> delete);
}

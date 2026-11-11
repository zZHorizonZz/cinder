package dev.horizon.cinder;

import io.vertx.core.Future;
import java.util.List;

/**
 * The rows of a write from the write itself, with {@code RETURNING}, as PostgreSQL gives them after its triggers. An
 * insert that skips its conflicts reads its rows by the conflict target, because {@code RETURNING} leaves out the rows
 * that it kept.
 */
final class StatementReadback implements Readback {

  @Override
  public boolean needsTransaction() {
    return false;
  }

  @Override
  public <E> Future<List<E>> insert(TableInsert<E> insert) {
    return insert.skipsConflicts() ? insert.run().compose(count -> insert.readTargets()) : insert.runReturning();
  }

  @Override
  public <E> Future<List<E>> update(TableUpdate<E> update) {
    return update.runReturning();
  }

  @Override
  public <E> Future<List<E>> delete(TableDelete<E> delete) {
    return delete.runReturning();
  }
}

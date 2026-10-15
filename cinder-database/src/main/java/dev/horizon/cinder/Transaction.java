package dev.horizon.cinder;

/**
 * The transaction of some work, which {@link Database#transaction} gives the work. The database commits it when the
 * future of the work succeeds, and rolls it back when the future fails, so the work never commits or rolls back by
 * itself.
 *
 * <pre>{@code
 * database.transaction(tx -> orders.updateById(42L, Assignments.none().set("state", "PAID"))
 *     .andThen(paid -> tx.onCommit(() -> events.publish("order.paid", 42L))));
 * }</pre>
 */
public interface Transaction {

  /**
   * Runs an action once the transaction commits, such as one that publishes an event of a change, so that nothing
   * sees a change that rolls back. The actions run in the order they were added, on the Vert.x context of the
   * transaction, after it ends: a statement of an action runs outside the transaction, on a connection of the pool, or
   * in a transaction of its own. A failing action does not change the result of the transaction.
   *
   * <p>
   * The actions of a savepoint run when its outermost transaction commits, and not if the savepoint rolls back.
   *
   * @param action the action
   * @throws IllegalStateException if the transaction has ended
   */
  void onCommit(Runnable action);

  /**
   * Runs an action once the transaction rolls back, in the order the actions were added, on the Vert.x context of
   * the transaction, after it ends, as {@link #onCommit} runs its actions.
   *
   * @param action the action
   * @throws IllegalStateException if the transaction has ended
   */
  void onRollback(Runnable action);

  /**
   * Marks the transaction to roll back when the work ends, even if its future succeeds, such as for a dry run. The
   * future of the transaction then gives the result of the work.
   *
   * @throws IllegalStateException if the transaction has ended
   */
  void rollbackOnly();

  /**
   * Gives the options of the transaction.
   *
   * @return the isolation level and the access mode, those of the outermost transaction for a savepoint
   */
  TransactionOptions options();
}

package dev.horizon.cinder;

import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.SqlConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * The transaction of some work, which {@link Database#transaction} gives the work. The database commits it when the
 * future of the work succeeds, and rolls it back when the future fails, so the work never commits or rolls back by
 * itself.
 *
 * <pre>{@code
 * database.transaction(tx -> orders.execute(orders.update().set("state", "PAID").where(eq("id", 42L)))
 *     .andThen(paid -> tx.onCommit(() -> events.publish("order.paid", 42L))));
 * }</pre>
 *
 * <p>
 * The transaction is held on a duplicate of the Vert.x context of the work, so that each transaction has local state
 * of its own. Only the internal {@code ContextInternal} of Vert.x creates a duplicate context, so a new version of
 * Vert.x can change it.
 */
public final class Transaction {

  private final ContextInternal context;
  private final SqlConnection connection;
  private final TransactionOptions options;
  private final Transaction parent;
  private final List<Runnable> commits = new ArrayList<>();
  private final List<Runnable> rollbacks = new ArrayList<>();
  private boolean rollbackOnly;
  private boolean ended;
  private int savepoints;

  Transaction(ContextInternal context, SqlConnection connection, TransactionOptions options, Transaction parent) {
    this.context = context;
    this.connection = connection;
    this.options = options;
    this.parent = parent;
  }

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
  public synchronized void onCommit(Runnable action) {
    requireOpen();
    commits.add(action);
  }

  /**
   * Runs an action once the transaction rolls back, in the order the actions were added, on the Vert.x context of
   * the transaction, after it ends, as {@link #onCommit} runs its actions.
   *
   * @param action the action
   * @throws IllegalStateException if the transaction has ended
   */
  public synchronized void onRollback(Runnable action) {
    requireOpen();
    rollbacks.add(action);
  }

  /**
   * Marks the transaction to roll back when the work ends, even if its future succeeds, such as for a dry run. The
   * future of the transaction then gives the result of the work.
   *
   * @throws IllegalStateException if the transaction has ended
   */
  public synchronized void rollbackOnly() {
    requireOpen();
    rollbackOnly = true;
  }

  /**
   * Gives the options of the transaction.
   *
   * @return the isolation level and the access mode, those of the outermost transaction for a savepoint
   */
  public TransactionOptions options() {
    return options;
  }

  ContextInternal context() {
    return context;
  }

  SqlConnection connection() {
    return connection;
  }

  synchronized boolean ended() {
    return ended;
  }

  void close() {
    synchronized (this) {
      ended = true;
    }
    context.removeLocal(TransactionLocal.KEY);
  }

  synchronized boolean isRollbackOnly() {
    return rollbackOnly;
  }

  synchronized String nextSavepoint() {
    return parent == null ? "cinder_" + ++savepoints : parent.nextSavepoint();
  }

  synchronized void adopt(Transaction savepoint) {
    synchronized (savepoint) {
      commits.addAll(savepoint.commits);
      rollbacks.addAll(savepoint.rollbacks);
    }
  }

  void committed() {
    run(commits);
  }

  void rolledBack() {
    run(rollbacks);
  }

  private void requireOpen() {
    if (ended) {
      throw new IllegalStateException("The transaction has ended.");
    }
  }

  private void run(List<Runnable> actions) {
    List<Runnable> copy;
    synchronized (this) {
      copy = List.copyOf(actions);
      actions.clear();
    }
    for (Runnable action : copy) {
      context.runOnContext(ignored -> {
        try {
          action.run();
        } catch (RuntimeException failed) {
          context.reportException(failed);
        }
      });
    }
  }
}

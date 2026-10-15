package dev.horizon.cinder.impl;

import dev.horizon.cinder.Transaction;
import dev.horizon.cinder.TransactionOptions;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.SqlConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * A transaction, or a savepoint of one, on the connection that the transaction holds until it ends.
 */
final class TransactionImpl implements Transaction {

  private final ContextInternal context;
  private final SqlConnection connection;
  private final TransactionOptions options;
  private final TransactionImpl parent;
  private final List<Runnable> commits = new ArrayList<>();
  private final List<Runnable> rollbacks = new ArrayList<>();
  private boolean rollbackOnly;
  private boolean ended;
  private int savepoints;

  TransactionImpl(ContextInternal context, SqlConnection connection, TransactionOptions options,
      TransactionImpl parent) {
    this.context = context;
    this.connection = connection;
    this.options = options;
    this.parent = parent;
  }

  @Override
  public synchronized void onCommit(Runnable action) {
    open();
    commits.add(action);
  }

  @Override
  public synchronized void onRollback(Runnable action) {
    open();
    rollbacks.add(action);
  }

  @Override
  public synchronized void rollbackOnly() {
    open();
    rollbackOnly = true;
  }

  @Override
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

  /**
   * Ends the transaction on its context, before its actions run, so that nothing on the context runs a statement on
   * its connection after the connection goes back to the pool.
   */
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

  /** Hands the actions of a savepoint that succeeded to its parent, for when the parent ends. */
  synchronized void adopt(TransactionImpl savepoint) {
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

  private void open() {
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

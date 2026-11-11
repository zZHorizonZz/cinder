package dev.horizon.cinder;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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
  private final Driver driver;
  private final TransactionOptions options;
  private final Transaction parent;
  private final List<Runnable> commits = new ArrayList<>();
  private final List<Runnable> rollbacks = new ArrayList<>();
  private boolean rollbackOnly;
  private boolean hasEnded;
  private int savepoints;

  Transaction(ContextInternal context, SqlConnection connection, Driver driver, TransactionOptions options,
      Transaction parent) {
    this.context = context;
    this.connection = connection;
    this.driver = driver;
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

  /**
   * Gives the transaction of the current Vert.x context.
   */
  static Transaction current() {
    ContextInternal context = (ContextInternal) Vertx.currentContext();
    return context == null ? null : active(context);
  }

  /**
   * Runs work in a transaction on a context: a new one on a connection of the pool, or a savepoint of the transaction
   * that the context already has. The future completes on the context.
   */
  static <T> Future<T> run(ContextInternal context, Pool pool, Driver driver, TransactionOptions options,
      Function<Transaction, Future<T>> work) {
    Transaction current = active(context);
    Future<T> result = current == null ? begin(context, pool, driver, options, work)
        : current.savepoint(context, work);
    Promise<T> promise = context.promise();
    result.onComplete(promise);
    return promise.future();
  }

  /**
   * Begins the transaction on its connection.
   */
  Future<Void> start() {
    return sql(driver.begin(options));
  }

  /**
   * Commits the transaction after the result of its work, or rolls it back after a failure or if the work asked for
   * it, and runs the actions of its end.
   */
  <T> Future<T> end(AsyncResult<T> result) {
    if (result.succeeded() && !isRollbackOnly()) {
      return sql(List.of(driver.commit())).transform(commit -> sql(driver.end(options)).transform(end -> {
        close();
        if (commit.failed()) {
          runRollbackActions();
          return Future.failedFuture(commit.cause());
        }
        runCommitActions();
        return Future.succeededFuture(result.result());
      }));
    }
    return sql(List.of(driver.rollback())).transform(rollback -> sql(driver.end(options))).transform(end -> {
      close();
      runRollbackActions();
      return outcome(result);
    });
  }

  SqlConnection connection() {
    return connection;
  }

  synchronized boolean hasEnded() {
    return hasEnded;
  }

  void close() {
    synchronized (this) {
      hasEnded = true;
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

  void runCommitActions() {
    perform(commits);
  }

  void runRollbackActions() {
    perform(rollbacks);
  }

  private static Transaction active(ContextInternal context) {
    Transaction transaction = context.getLocal(TransactionLocal.KEY);
    return transaction == null || transaction.hasEnded() ? null : transaction;
  }

  private static <T> Future<T> begin(ContextInternal context, Pool pool, Driver driver, TransactionOptions options,
      Function<Transaction, Future<T>> work) {
    ContextInternal duplicate = context.duplicate();
    Promise<T> promise = duplicate.promise();
    duplicate.runOnContext(ignored -> pool.getConnection().compose(connection -> {
      Transaction transaction = new Transaction(duplicate, connection, driver, options, null);
      return transaction.start().compose(begin -> transaction.within(work).transform(transaction::end))
          .eventually(connection::close);
    }).onComplete(promise));
    return promise.future();
  }

  private <T> Future<T> savepoint(ContextInternal on, Function<Transaction, Future<T>> work) {
    String name = nextSavepoint();
    Transaction savepoint = new Transaction(on.duplicate(), connection, driver, options, this);
    return sql(List.of(driver.savepoint(name))).compose(start -> savepoint.within(work).transform(result -> {
      if (result.succeeded() && !savepoint.isRollbackOnly()) {
        return sql(driver.release(name).map(List::of).orElse(List.of())).map(release -> {
          savepoint.close();
          adopt(savepoint);
          return result.result();
        });
      }
      return sql(List.of(driver.rollbackTo(name))).transform(rollback -> {
        savepoint.close();
        savepoint.runRollbackActions();
        return rollback.failed() ? Future.failedFuture(rollback.cause()) : outcome(result);
      });
    }));
  }

  private <T> Future<T> within(Function<Transaction, Future<T>> work) {
    context.putLocal(TransactionLocal.KEY, this);
    Promise<T> promise = context.promise();
    context.runOnContext(ignored -> Future.future((Promise<T> inner) -> work.apply(this).onComplete(inner))
        .onComplete(promise));
    return promise.future();
  }

  private Future<Void> sql(List<String> statements) {
    Future<Void> done = Future.succeededFuture();
    for (String statement : statements) {
      done = done.compose(previous -> connection.query(statement).execute().mapEmpty());
    }
    return done;
  }

  private static <T> Future<T> outcome(AsyncResult<T> result) {
    return result.succeeded() ? Future.succeededFuture(result.result()) : Future.failedFuture(result.cause());
  }

  private void requireOpen() {
    if (hasEnded) {
      throw new IllegalStateException("The transaction has ended.");
    }
  }

  private void perform(List<Runnable> actions) {
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

package dev.horizon.cinder;

import dev.horizon.cinder.impl.DatabaseBuilder;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import java.time.Clock;
import java.util.function.Function;
import javax.crypto.SecretKey;

/**
 * The tables that a pool reaches, through the SQL of a Basepoint dialect. The model sources of the extensions map
 * types of entities to the tables, such as the protobuf extension does for messages with a table option.
 *
 * <p>
 * An operation runs in the transaction of the current Vert.x context, when {@link #transaction} started one.
 * Otherwise each of its statements commits as it runs. A transaction started off every Vert.x context runs on the
 * context of the connections of the pool.
 */
public interface Database {

  /** The most rows of a page, unless the builder sets another number. */
  int MAX_PAGE_SIZE = 1000;

  /**
   * Starts a database on a pool.
   *
   * @param pool the pool that runs the statements
   * @param driver the driver of the database of the pool, such as {@code new PostgresDriver()}
   * @return the builder of the database
   */
  static Builder builder(Pool pool, Driver driver) {
    return new DatabaseBuilder(pool, driver);
  }

  /**
   * Sets up a database. Each setter gives this builder.
   */
  interface Builder {

    /**
     * Adds an extension. The database applies its extensions in the order they were added.
     *
     * @param extension the extension
     * @return this builder
     */
    Builder extension(Extension extension);

    /**
     * Sets the clock of {@link Context#now()}, the time of each operation.
     *
     * @param clock the clock. The default is the system clock in UTC.
     * @return this builder
     */
    Builder clock(Clock clock);

    /**
     * Sets the key of page tokens. Give every database that serves the same clients the same key, so that a token of
     * one is good at the others.
     *
     * @param key the AES key of 128, 192 or 256 bits. The default is a random key, so the tokens of a database do not
     * outlive it.
     * @return this builder
     */
    Builder pageTokenKey(SecretKey key);

    /**
     * Sets the most rows that a page holds. A page request for more gets this many, as AIP-158 asks, so that a client
     * cannot read a whole table at once.
     *
     * @param rows the most rows of a page, at least one. The default is {@value #MAX_PAGE_SIZE}.
     * @return this builder
     * @throws IllegalArgumentException if the number is less than one
     */
    Builder maxPageSize(int rows);

    /**
     * Builds the database.
     *
     * @return the database
     */
    Database build();
  }

  /**
   * Gives the model of a type, from the first model source that knows it.
   *
   * @param type the type of the entities, such as {@code Order.class}
   * @param <E> the type of the entities
   * @return the model
   * @throws IllegalArgumentException if no model source knows the type
   */
  <E> Model<E> model(Class<E> type);

  /**
   * Starts a query of every row of a model.
   *
   * @param model the model
   * @param <E> the type of the entities
   * @return the query, to narrow, sort and run
   */
  <E> Query<E> query(Model<E> model);

  /**
   * Starts an insert of rows of a model.
   *
   * @param model the model
   * @param <E> the type of the entities
   * @return the insert, without rows
   */
  <E> Insert<E> insert(Model<E> model);

  /**
   * Starts an update of rows of a model.
   *
   * @param model the model
   * @param <E> the type of the entities
   * @return the update, without conditions or changes
   */
  <E> Update<E> update(Model<E> model);

  /**
   * Starts a delete of rows of a model.
   *
   * @param model the model
   * @param <E> the type of the entities
   * @return the delete, without conditions
   */
  <E> Delete<E> delete(Model<E> model);

  /**
   * Runs work in a transaction with the default options.
   *
   * @param work starts the operations of the work, and gives its future
   * @param <T> the type of the result of the work
   * @return the result of the work after the commit, or its failure after the rollback
   * @see #transaction(TransactionOptions, Function)
   */
  <T> Future<T> transaction(Function<Transaction, Future<T>> work);

  /**
   * Runs work in a transaction. Every operation of the work, on any backend of this database, runs in it. The
   * transaction commits when the future of the work succeeds, and rolls back when it fails or the work marks it
   * {@link Transaction#rollbackOnly() rollback only}.
   *
   * <p>
   * A transaction in the work of another runs as a savepoint of it: when the inner work fails, only its changes roll
   * back, and the outer work goes on if it handles the failure.
   *
   * @param options the isolation level and the access mode, which a savepoint keeps from its outer transaction
   * @param work starts the operations of the work, and gives its future
   * @param <T> the type of the result of the work
   * @return the result of the work after the commit, or its failure after the rollback. After a rollback that the
   * work asked for, the result of the work.
   */
  <T> Future<T> transaction(TransactionOptions options, Function<Transaction, Future<T>> work);

  /**
   * Closes the database. The pool stays open, for the caller that made it to close.
   *
   * @return a future that completes when the database is closed
   */
  Future<Void> close();
}

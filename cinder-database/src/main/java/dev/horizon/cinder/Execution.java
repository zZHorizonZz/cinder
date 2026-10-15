package dev.horizon.cinder;

import dev.horizon.basepoint.Statement;
import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import java.util.List;

/**
 * The run of an operation, which a {@link Terminal} gets: the statement after every part, and the means to run it the
 * way the database runs its own statements.
 *
 * @param <E> the type of the entities
 */
public interface Execution<E> extends Context<E> {

  /**
   * Gives the statement of the operation.
   *
   * @return the statement, with the changes of every part, its fields named by their columns and its values bound,
   * before the decorators. A statement that a terminal makes of it names columns too.
   */
  Statement statement();

  /**
   * Runs a statement: the one of the operation, or another that the terminal made of it. The statement goes through
   * the decorators of the dialect and the extensions, and its run through the interceptors, in the transaction of the
   * operation if it has one.
   *
   * @param statement the statement
   * @return the rows, or a future that fails if the statement does not build or the database refuses it
   */
  Future<RowSet<Row>> run(Statement statement);

  /**
   * Reads the rows of a statement through a cursor, with the back pressure of the stream. In the transaction of the
   * operation, the cursor uses its connection. Without one, it takes a connection and a transaction of its own, and
   * gives them back when the stream ends or fails.
   *
   * @param statement the statement, which goes through the decorators and the interceptors as {@link #run} does
   * @param fetch the number of rows that the cursor reads at a time
   * @return the stream of the rows
   */
  ReadStream<Row> stream(Statement statement, int fetch);

  /**
   * Reads an entity from a row, through the codecs of its fields.
   *
   * @param row a row of the table of the model
   * @return the entity
   */
  E read(Row row);

  /**
   * Gives entities to the parts of the operation, in order, such as to an include that reads their related rows. A
   * terminal that streams gives them each batch that its cursor reads.
   *
   * @param entities the entities that the terminal read
   * @return the entities, with the changes of every part
   */
  Future<List<E>> complete(List<E> entities);
}

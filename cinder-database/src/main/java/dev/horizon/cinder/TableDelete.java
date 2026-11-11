package dev.horizon.cinder;

import dev.horizon.basepoint.DeleteQuery;
import dev.horizon.basepoint.statement.Statement;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import java.util.List;
import java.util.function.Function;

/**
 * A delete of the rows of a table.
 *
 * @param <E> the type of the entities
 */
final class TableDelete<E> {

  private final Execution<E> execution;
  private final Mapping<E> mapping;
  private final DeleteQuery delete;

  TableDelete(Execution<E> execution, DeleteQuery delete) {
    this.execution = execution;
    this.mapping = execution.mapping();
    this.delete = delete;
  }

  Execution<E> execution() {
    return execution;
  }

  /**
   * Gives the statement that runs, shaped by the parts and mapped to the columns.
   */
  Statement statement() {
    return execution.shape(delete.statement());
  }

  /**
   * Runs the delete.
   *
   * @return the number of rows that it deleted
   */
  Future<Long> run() {
    return execution.run(statement()).map(rows -> (long) rows.rowCount());
  }

  /**
   * Runs the delete with {@code RETURNING}, and reads the rows that it gives.
   */
  Future<List<E>> runReturning() {
    Statement statement = execution.shape(delete.returning(mapping.fields()).statement());
    Function<Row, E> reader = mapping.reader(statement);
    return execution.run(statement).compose(rows -> execution.entities(rows, reader));
  }
}

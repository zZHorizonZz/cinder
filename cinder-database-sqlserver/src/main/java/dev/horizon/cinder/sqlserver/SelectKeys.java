package dev.horizon.cinder.sqlserver;

import dev.horizon.basepoint.statement.Clause;
import dev.horizon.basepoint.statement.StatementWriter;
import java.util.List;

/**
 * Reads the keys of the inserted rows from the table variable, as the last rows of the batch:
 * {@code ; SELECT [id] FROM @cinder_keys}.
 *
 * @param columns the columns of the key
 */
record SelectKeys(List<KeyColumn> columns) implements Clause {

  @Override
  public void render(StatementWriter sql) {
    sql.append("; SELECT ").names(columns.stream().map(KeyColumn::name).toList()).append(" FROM @cinder_keys");
  }
}

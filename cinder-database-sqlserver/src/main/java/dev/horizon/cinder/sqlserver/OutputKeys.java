package dev.horizon.cinder.sqlserver;

import dev.horizon.basepoint.statement.Clause;
import dev.horizon.basepoint.statement.StatementWriter;
import java.util.List;

/**
 * Writes the keys of the inserted rows into the table variable, which SQL Server allows on a table with triggers:
 * {@code OUTPUT INSERTED.[id] INTO @cinder_keys}.
 *
 * @param columns the columns of the key
 */
record OutputKeys(List<KeyColumn> columns) implements Clause {

  @Override
  public void render(StatementWriter sql) {
    sql.append("OUTPUT ");
    for (int i = 0; i < columns.size(); i++) {
      sql.append(i == 0 ? "INSERTED." : ", INSERTED.").name(columns.get(i).name());
    }
    sql.append(" INTO @cinder_keys");
  }
}

package dev.horizon.cinder.sqlserver;

import dev.horizon.basepoint.statement.Clause;
import dev.horizon.basepoint.statement.StatementWriter;
import java.util.List;

/**
 * Declares the table variable that holds the keys of the rows of an insert: {@code DECLARE @cinder_keys TABLE (...);}.
 *
 * @param columns the columns of the key
 */
record DeclareKeys(List<KeyColumn> columns) implements Clause {

  @Override
  public void render(StatementWriter sql) {
    sql.append("DECLARE @cinder_keys TABLE (");
    for (int i = 0; i < columns.size(); i++) {
      sql.append(i == 0 ? "" : ", ").name(columns.get(i).name()).append(" " + columns.get(i).type());
    }
    sql.append(");");
  }
}

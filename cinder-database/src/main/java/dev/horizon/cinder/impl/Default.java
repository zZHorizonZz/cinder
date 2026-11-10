package dev.horizon.cinder.impl;

import dev.horizon.basepoint.expression.Expression;
import dev.horizon.basepoint.statement.StatementWriter;

/**
 * The default of a column, {@code DEFAULT}, which an update sets a column to when it unsets a field whose column
 * cannot be NULL.
 */
record Default() implements Expression {

  @Override
  public void render(StatementWriter sql) {
    sql.append("DEFAULT");
  }
}

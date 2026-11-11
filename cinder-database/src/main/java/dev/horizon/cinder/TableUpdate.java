package dev.horizon.cinder;

import dev.horizon.basepoint.UpdateQuery;
import dev.horizon.basepoint.expression.Expression;
import dev.horizon.basepoint.expression.Null;
import dev.horizon.basepoint.expression.Value;
import dev.horizon.basepoint.statement.Assignment;
import dev.horizon.basepoint.statement.Assignments;
import dev.horizon.basepoint.statement.Statement;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * An update of the rows of a table. The parts write the fields that it sets.
 *
 * @param <E> the type of the entities
 */
final class TableUpdate<E> {

  private final Execution<E> execution;
  private final Mapping<E> mapping;
  private final UpdateQuery update;

  TableUpdate(Execution<E> execution, UpdateQuery update) {
    this.execution = execution;
    this.mapping = execution.mapping();
    this.update = update;
  }

  Execution<E> execution() {
    return execution;
  }

  /**
   * Gives the statement that runs: the fields that the parts write, shaped by the parts and mapped to the columns.
   */
  Statement statement() {
    return execution.shape(setFields(update.statement()));
  }

  /**
   * Runs the update.
   *
   * @return the number of rows that it updated
   */
  Future<Long> run() {
    return execution.run(statement()).map(rows -> (long) rows.rowCount());
  }

  /**
   * Runs the update with {@code RETURNING}, and reads the rows that it gives.
   */
  Future<List<E>> runReturning() {
    Statement statement = execution.shape(setFields(update.returning(mapping.fields()).statement()));
    Function<Row, E> reader = mapping.reader(statement);
    return execution.run(statement).compose(rows -> execution.entities(rows, reader));
  }

  private Statement setFields(Statement statement) {
    Optional<Assignments> found = statement.find(Assignments.class);
    if (found.isEmpty()) {
      return statement;
    }
    Map<String, Object> row = new LinkedHashMap<>();
    for (Assignment assignment : found.get().assignments()) {
      row.put(assignment.column(), switch (assignment.value()) {
        case Value value -> value.value();
        case Null ignored -> null;
        default -> assignment.value();
      });
    }
    List<Assignment> assignments = new ArrayList<>();
    execution.write(row).forEach((field, value) -> assignments.add(new Assignment(field, Expression.value(value))));
    return statement.replace(found.get(), new Assignments(assignments));
  }
}

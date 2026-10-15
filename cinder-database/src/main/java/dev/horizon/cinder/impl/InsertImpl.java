package dev.horizon.cinder.impl;

import dev.horizon.basepoint.Clause;
import dev.horizon.basepoint.InsertQuery;
import dev.horizon.basepoint.Statement;
import dev.horizon.cinder.Assignments;
import dev.horizon.cinder.Call;
import dev.horizon.cinder.Conflict;
import dev.horizon.cinder.Insert;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.Part;
import dev.horizon.cinder.Terminal;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class InsertImpl<E> implements Insert<E> {

  private record Steps<E>(List<E> entities, Conflict conflict, List<Part> parts) {

    Steps {
      entities = List.copyOf(entities);
      parts = List.copyOf(parts);
    }
  }

  /** The result of one statement of a write. */
  private interface Result<E, R> {

    Future<R> of(ExecutionImpl<E> execution, RowSet<Row> rows);
  }

  private final CinderDatabase database;
  private final Table<E> table;
  private final Steps<E> steps;

  InsertImpl(CinderDatabase database, Table<E> table) {
    this(database, table, new Steps<>(List.of(), null, List.of()));
  }

  private InsertImpl(CinderDatabase database, Table<E> table, Steps<E> steps) {
    this.database = database;
    this.table = table;
    this.steps = steps;
  }

  @Override
  public Model<E> model() {
    return table.model();
  }

  @Override
  public Insert<E> with(Part part) {
    List<Part> parts = new ArrayList<>(steps.parts());
    parts.add(part);
    return new InsertImpl<>(database, table, new Steps<>(steps.entities(), steps.conflict(), parts));
  }

  @Override
  public <R> R run(Terminal<? super E, R> terminal) {
    ExecutionImpl<E> execution = execution();
    List<InsertQuery> inserts = inserts(execution);
    if (inserts.size() != 1) {
      throw new IllegalStateException("A terminal runs one statement, but the rows of the insert set "
          + inserts.size() + " different sets of fields. Insert them separately.");
    }
    return terminal.run(execution.of(statement(inserts.getFirst(), false)));
  }

  @Override
  public Insert<E> values(E entity) {
    return values(List.of(entity));
  }

  @Override
  public Insert<E> values(List<E> entities) {
    List<E> more = new ArrayList<>(steps.entities());
    more.addAll(entities);
    return new InsertImpl<>(database, table, new Steps<>(more, steps.conflict(), steps.parts()));
  }

  @Override
  public Insert<E> onConflict(Conflict conflict) {
    return new InsertImpl<>(database, table, new Steps<>(steps.entities(), conflict, steps.parts()));
  }

  @Override
  public Future<Long> execute() {
    return write(false, (execution, rows) -> Future.succeededFuture((long) rows.rowCount()))
        .map(counts -> counts.stream().mapToLong(Long::longValue).sum());
  }

  @Override
  public Future<List<E>> returning() {
    return write(true, ExecutionImpl::entities).map(parts -> {
      List<E> entities = new ArrayList<>();
      parts.forEach(entities::addAll);
      return entities;
    });
  }

  /**
   * Runs the statements of the insert, one for each set of fields that its rows set, all in one transaction when there
   * are more.
   */
  private <R> Future<List<R>> write(boolean returning, Result<E, R> result) {
    if (steps.entities().isEmpty()) {
      return Future.succeededFuture(List.of());
    }
    return Future.succeededFuture().compose(ignored -> {
      ExecutionImpl<E> execution = execution();
      List<InsertQuery> inserts = inserts(execution);
      Function<List<InsertQuery>, Future<List<R>>> statements = all -> {
        Future<List<R>> done = Future.succeededFuture(new ArrayList<>());
        for (InsertQuery insert : all) {
          done = done.compose(results -> execution.of(statement(insert, returning)).run(execution.statement())
              .compose(rows -> result.of(execution, rows)).map(one -> {
                results.add(one);
                return results;
              }));
        }
        return done;
      };
      return inserts.size() > 1 && database.current() == null ? database.transaction(transaction -> statements
          .apply(inserts)) : statements.apply(inserts);
    });
  }

  private ExecutionImpl<E> execution() {
    return new ExecutionImpl<>(database, table, Call.Kind.INSERT, steps.parts());
  }

  /** Gives an insert for each set of fields that the rows set after the parts, in the order of the rows. */
  private List<InsertQuery> inserts(ExecutionImpl<E> execution) {
    Map<List<String>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
    for (E entity : steps.entities()) {
      Assignments row = table.row(entity);
      for (Part part : execution.parts()) {
        row = part.write(row, execution);
      }
      Map<String, Object> values = table.values(row);
      groups.computeIfAbsent(List.copyOf(values.keySet()), ignored -> new ArrayList<>()).add(values);
    }
    return groups.values().stream().map(rows -> table.collection().insert(rows)).toList();
  }

  private Statement statement(InsertQuery insert, boolean returning) {
    InsertQuery written = returning ? insert.returning(table.columns()) : insert;
    if (steps.conflict() == null) {
      return written.statement();
    }
    String[] target = (steps.conflict().target().isEmpty() ? table.key().stream().map(Binding::column)
        : steps.conflict().target().stream().map(field -> table.binding(field).column())).toArray(String[]::new);
    Statement statement = switch (steps.conflict()) {
      case Conflict.Ignore ignore -> written.ignore(target).statement();
      case Conflict.Merge merge -> written.replace(target).statement();
    };
    if (steps.conflict() instanceof Conflict.Merge merge && !merge.fields().isEmpty()) {
      Clause.OnConflict conflict = statement.find(Clause.OnConflict.class).orElseThrow();
      return statement.replace(conflict, new Clause.OnConflict(conflict.key(), merge.fields().stream()
          .map(field -> table.binding(field).column()).toList()));
    }
    return statement;
  }
}

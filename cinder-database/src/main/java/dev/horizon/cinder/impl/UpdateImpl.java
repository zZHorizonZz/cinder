package dev.horizon.cinder.impl;

import dev.horizon.aquery.aip157.ReadMask;
import dev.horizon.basepoint.Clause;
import dev.horizon.basepoint.Expression;
import dev.horizon.basepoint.Filter;
import dev.horizon.basepoint.LockMode;
import dev.horizon.basepoint.Source;
import dev.horizon.basepoint.Statement;
import dev.horizon.basepoint.UpdateQuery;
import dev.horizon.cinder.Assignments;
import dev.horizon.cinder.Call;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.Part;
import dev.horizon.cinder.Terminal;
import dev.horizon.cinder.Update;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import java.util.ArrayList;
import java.util.List;

final class UpdateImpl<E> implements Update<E> {

  private record Steps(List<Filter> filters, boolean all, Assignments assignments, List<Part> parts) {

    Steps {
      filters = List.copyOf(filters);
      parts = List.copyOf(parts);
    }
  }

  private final CinderDatabase database;
  private final Table<E> table;
  private final Steps steps;

  UpdateImpl(CinderDatabase database, Table<E> table) {
    this(database, table, new Steps(List.of(), false, Assignments.none(), List.of()));
  }

  private UpdateImpl(CinderDatabase database, Table<E> table, Steps steps) {
    this.database = database;
    this.table = table;
    this.steps = steps;
  }

  @Override
  public Model<E> model() {
    return table.model();
  }

  @Override
  public Update<E> with(Part part) {
    List<Part> parts = new ArrayList<>(steps.parts());
    parts.add(part);
    return new UpdateImpl<>(database, table, new Steps(steps.filters(), steps.all(), steps.assignments(), parts));
  }

  @Override
  public <R> R run(Terminal<? super E, R> terminal) {
    ExecutionImpl<E> execution = execution();
    return terminal.run(execution.of(statement(execution, false)));
  }

  @Override
  public Update<E> where(Filter filter) {
    table.check(filter);
    List<Filter> filters = new ArrayList<>(steps.filters());
    filters.add(filter);
    return new UpdateImpl<>(database, table, new Steps(filters, steps.all(), steps.assignments(), steps.parts()));
  }

  @Override
  public Update<E> where(String filter) {
    return where(Filter.of(filter));
  }

  @Override
  public Update<E> all() {
    return new UpdateImpl<>(database, table, new Steps(steps.filters(), true, steps.assignments(), steps.parts()));
  }

  @Override
  public Update<E> set(Assignments assignments) {
    List<Assignments.Change> changes = new ArrayList<>(steps.assignments().changes());
    changes.addAll(assignments.changes());
    return new UpdateImpl<>(database, table, new Steps(steps.filters(), steps.all(), new Assignments(changes),
        steps.parts()));
  }

  @Override
  public Update<E> set(E entity, String updateMask) {
    ReadMask mask = ReadMask.parse(updateMask);
    List<Binding> fields = mask.allFields() ? table.bindings().stream().filter(binding -> !binding.field().key())
        .toList() : mask.paths().stream().map(path -> {
          Binding binding = table.binding(path, "An update mask");
          if (binding.field().key()) {
            throw new IllegalArgumentException("An update cannot change the key field " + binding.name() + ".");
          }
          return binding;
        }).toList();
    List<Assignments.Change> changes = new ArrayList<>(fields.size());
    for (Binding binding : fields) {
      changes.add(new Assignments.SetValue(binding.name(), binding.get(entity)));
    }
    return set(new Assignments(changes));
  }

  @Override
  public Future<Long> execute() {
    return Future.succeededFuture().compose(ignored -> {
      ExecutionImpl<E> execution = execution();
      return execution.of(statement(execution, false)).run(execution.statement())
          .map(rows -> (long) rows.rowCount());
    });
  }

  @Override
  public Future<List<E>> returning() {
    return Future.succeededFuture().compose(ignored -> {
      ExecutionImpl<E> execution = execution();
      if (!database.driver().returnsUpdatedRows()) {
        return reread(execution.of(statement(execution, false)));
      }
      return execution.of(statement(execution, true)).entities();
    });
  }

  /**
   * Updates the rows and reads them again, for a database that does not give the rows of an update. In a transaction,
   * it locks the rows of the filter and reads their keys, updates the rows of those keys, and reads them again.
   */
  private Future<List<E>> reread(ExecutionImpl<E> execution) {
    Statement update = execution.statement();
    Filter where = update.find(Clause.Where.class).map(Clause.Where::filter).orElse(Filter.and());
    List<Expression> keys = table.key().stream().map(key -> Expression.column(key.column())).toList();
    Statement locked = new Statement(Source.aliased(table.model().table()), List.of(new Clause.Select(false, keys),
        new Clause.From(), new Clause.Where(where), new Clause.Lock(LockMode.UPDATE)));
    return database.transaction(transaction -> execution.run(locked).compose(found -> {
      if (found.size() == 0) {
        return Future.succeededFuture(List.<E> of());
      }
      Filter byKeys = keys(found);
      Statement byKey = update.find(Clause.Where.class).map(old -> update.replace(old, new Clause.Where(byKeys)))
          .orElseGet(() -> update.where(byKeys));
      Statement read = new Statement(Source.aliased(table.model().table()), List.of(new Clause.Select(false,
          List.of()), new Clause.From(), new Clause.Where(byKeys)));
      return execution.run(byKey).compose(updated -> execution.run(read)).compose(execution::entities);
    }));
  }

  /** Gives the filter of the keys of the rows that the locking read found, by column and with bound values. */
  private Filter keys(Iterable<Row> rows) {
    List<Filter> found = new ArrayList<>();
    for (Row row : rows) {
      List<Filter> terms = new ArrayList<>();
      for (int i = 0; i < table.key().size(); i++) {
        terms.add(Filter.eq(Expression.column(table.key().get(i).column()), row.getValue(i)));
      }
      found.add(Filter.and(terms.toArray(Filter[]::new)));
    }
    return Filter.or(found.toArray(Filter[]::new));
  }

  private ExecutionImpl<E> execution() {
    return new ExecutionImpl<>(database, table, Call.Kind.UPDATE, steps.parts());
  }

  private Statement statement(ExecutionImpl<E> execution, boolean returning) {
    Assignments row = steps.assignments();
    for (Part part : execution.parts()) {
      row = part.write(row, execution);
    }
    UpdateQuery update = table.set(table.collection().update(), row);
    for (Filter filter : steps.filters()) {
      update = update.where(filter);
    }
    if (steps.all()) {
      update = update.all();
    }
    return (returning ? update.returning(table.columns()) : update).statement();
  }
}

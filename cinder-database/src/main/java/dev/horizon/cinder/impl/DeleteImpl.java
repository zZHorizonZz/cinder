package dev.horizon.cinder.impl;

import dev.horizon.basepoint.DeleteQuery;
import dev.horizon.basepoint.Filter;
import dev.horizon.basepoint.Statement;
import dev.horizon.cinder.Call;
import dev.horizon.cinder.Delete;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.Part;
import dev.horizon.cinder.Terminal;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;

final class DeleteImpl<E> implements Delete<E> {

  private record Steps(List<Filter> filters, boolean all, List<Part> parts) {

    Steps {
      filters = List.copyOf(filters);
      parts = List.copyOf(parts);
    }
  }

  private final CinderDatabase database;
  private final Table<E> table;
  private final Steps steps;

  DeleteImpl(CinderDatabase database, Table<E> table) {
    this(database, table, new Steps(List.of(), false, List.of()));
  }

  private DeleteImpl(CinderDatabase database, Table<E> table, Steps steps) {
    this.database = database;
    this.table = table;
    this.steps = steps;
  }

  @Override
  public Model<E> model() {
    return table.model();
  }

  @Override
  public Delete<E> with(Part part) {
    List<Part> parts = new ArrayList<>(steps.parts());
    parts.add(part);
    return new DeleteImpl<>(database, table, new Steps(steps.filters(), steps.all(), parts));
  }

  @Override
  public <R> R run(Terminal<? super E, R> terminal) {
    return terminal.run(execution().of(statement(false)));
  }

  @Override
  public Delete<E> where(Filter filter) {
    table.check(filter);
    List<Filter> filters = new ArrayList<>(steps.filters());
    filters.add(filter);
    return new DeleteImpl<>(database, table, new Steps(filters, steps.all(), steps.parts()));
  }

  @Override
  public Delete<E> where(String filter) {
    return where(Filter.of(filter));
  }

  @Override
  public Delete<E> all() {
    return new DeleteImpl<>(database, table, new Steps(steps.filters(), true, steps.parts()));
  }

  @Override
  public Future<Long> execute() {
    return Future.succeededFuture().compose(ignored -> {
      ExecutionImpl<E> execution = execution().of(statement(false));
      return execution.run(execution.statement()).map(rows -> (long) rows.rowCount());
    });
  }

  @Override
  public Future<List<E>> returning() {
    return Future.succeededFuture().compose(ignored -> execution().of(statement(true)).entities());
  }

  private ExecutionImpl<E> execution() {
    return new ExecutionImpl<>(database, table, Call.Kind.DELETE, steps.parts());
  }

  private Statement statement(boolean returning) {
    DeleteQuery delete = table.collection().delete();
    for (Filter filter : steps.filters()) {
      delete = delete.where(filter);
    }
    if (steps.all()) {
      delete = delete.all();
    }
    return (returning ? delete.returning(table.columns()) : delete).statement();
  }
}

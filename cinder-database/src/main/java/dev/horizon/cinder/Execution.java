package dev.horizon.cinder;

import dev.horizon.basepoint.statement.Statement;
import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * One operation of a table: its context and its parts, which shape its statements, write its rows and complete its
 * entities.
 *
 * @param <E> the type of the entities
 */
final class Execution<E> {

  private final Database database;
  private final Mapping<E> mapping;
  private final Context<E> context;
  private final List<Part> parts;

  Execution(Database database, Mapping<E> mapping, Invocation.Kind kind, List<Part> own) {
    this.database = database;
    this.mapping = mapping;
    this.context = new Context<>(database, mapping.model(), Optional.ofNullable(database.current()), kind,
        database.now());
    List<Part> all = new ArrayList<>(mapping.parts());
    all.addAll(own);
    this.parts = List.copyOf(all);
  }

  Context<E> context() {
    return context;
  }

  /**
   * Gives the fields that a write sends, after the parts.
   */
  Map<String, Object> write(Map<String, Object> row) {
    for (Part part : parts) {
      row = part.write(row, context);
    }
    return row;
  }

  /**
   * Gives the statement that runs: shaped by the parts, then mapped to the columns.
   */
  Statement shape(Statement statement) {
    mapping.requireTable(statement);
    for (Part part : parts) {
      statement = part.shape(statement, context);
    }
    return mapping.rewrite(statement);
  }

  Future<RowSet<Row>> run(Statement statement) {
    return database.run(mapping.model(), context.kind(), statement);
  }

  ReadStream<Row> stream(Statement statement, int fetch) {
    return database.stream(mapping.model(), statement, fetch);
  }

  Future<List<E>> entities(RowSet<Row> rows, Function<Row, E> reader) {
    List<E> entities = new ArrayList<>(rows.size());
    for (Row row : rows) {
      entities.add(reader.apply(row));
    }
    return complete(entities);
  }

  Future<List<E>> complete(List<E> entities) {
    Future<List<E>> future = Future.succeededFuture(entities);
    for (Part part : parts) {
      future = future.compose(done -> part.complete(done, context));
    }
    return future;
  }
}

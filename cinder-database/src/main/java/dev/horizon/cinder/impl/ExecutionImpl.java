package dev.horizon.cinder.impl;

import dev.horizon.basepoint.Statement;
import dev.horizon.cinder.Call;
import dev.horizon.cinder.Database;
import dev.horizon.cinder.Execution;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.Part;
import dev.horizon.cinder.Transaction;
import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The run of an operation: its context, the parts of its model and its own, and its statement after the parts and the
 * rewrite.
 */
final class ExecutionImpl<E> implements Execution<E> {

  private final CinderDatabase database;
  private final Table<E> table;
  private final Call.Kind kind;
  private final List<Part> parts;
  private final Function<Row, E> reader;
  private final Instant now;
  private final Optional<Transaction> transaction;
  private Statement statement;

  ExecutionImpl(CinderDatabase database, Table<E> table, Call.Kind kind, List<Part> own) {
    this(database, table, kind, own, table.reader());
  }

  ExecutionImpl(CinderDatabase database, Table<E> table, Call.Kind kind, List<Part> own, Function<Row, E> reader) {
    this.database = database;
    this.table = table;
    this.kind = kind;
    List<Part> all = new ArrayList<>(table.parts());
    all.addAll(own);
    this.parts = List.copyOf(all);
    this.reader = reader;
    this.now = database.now();
    this.transaction = Optional.ofNullable(database.current());
  }

  /** Gives the execution with its statement: the statement after the parts, with the fields named by columns. */
  ExecutionImpl<E> of(Statement written) {
    Statement shaped = written;
    for (Part part : parts) {
      shaped = part.shape(shaped, this);
    }
    statement = table.rewrite(shaped);
    return this;
  }

  List<Part> parts() {
    return parts;
  }

  /** Runs the statement of the execution, and reads its rows as the entities that the parts complete. */
  Future<List<E>> entities() {
    return run(statement).compose(this::entities);
  }

  /** Reads rows as the entities that the parts complete. */
  Future<List<E>> entities(RowSet<Row> rows) {
    List<E> entities = new ArrayList<>(rows.size());
    for (Row row : rows) {
      entities.add(reader.apply(row));
    }
    return complete(entities);
  }

  @Override
  public Database database() {
    return database;
  }

  @Override
  public Model<E> model() {
    return table.model();
  }

  @Override
  public Optional<Transaction> transaction() {
    return transaction;
  }

  @Override
  public Call.Kind kind() {
    return kind;
  }

  @Override
  public Instant now() {
    return now;
  }

  @Override
  public Statement statement() {
    return statement;
  }

  @Override
  public Future<RowSet<Row>> run(Statement statement) {
    return database.run(table.model(), kind, statement);
  }

  @Override
  public ReadStream<Row> stream(Statement statement, int fetch) {
    return database.stream(table.model(), statement, fetch);
  }

  @Override
  public E read(Row row) {
    return reader.apply(row);
  }

  @Override
  public Future<List<E>> complete(List<E> entities) {
    Future<List<E>> completed = Future.succeededFuture(entities);
    for (Part part : parts) {
      completed = completed.compose(done -> part.complete(done, this));
    }
    return completed;
  }
}

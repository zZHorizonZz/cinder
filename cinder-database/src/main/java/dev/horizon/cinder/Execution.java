package dev.horizon.cinder;

import dev.horizon.basepoint.statement.Clause;
import dev.horizon.basepoint.statement.Operand;
import dev.horizon.basepoint.statement.Statement;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * One operation of a table: its context and its parts, which shape its statements, write its rows and complete its
 * entities, and the running of its statements through the interceptors, in the transaction of the current context or
 * on the pool.
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
    this.context = new Context<>(database, mapping.model(), Optional.ofNullable(Transaction.current()), kind,
        database.now());
    List<Part> all = new ArrayList<>(mapping.parts());
    all.addAll(own);
    this.parts = List.copyOf(all);
  }

  Mapping<E> mapping() {
    return mapping;
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
    return mapping.rewrite(shapeByParts(statement));
  }

  private Statement shapeByParts(Statement statement) {
    if (Mapping.compound(statement)) {
      return new Statement(statement.source(),
          statement.clauses().stream()
              .map(
                  clause -> clause instanceof Operand(Statement operand) ? (Clause) new Operand(shapeByParts(operand)) : clause)
              .toList());
    }
    for (Part part : parts) {
      statement = part.shape(statement, context);
    }
    return statement;
  }

  Future<RowSet<Row>> run(Statement statement) {
    Model<E> model = mapping.model();
    return Future.<RowSet<Row>> future(promise -> {
      Transaction transaction = Transaction.current();
      SqlClient client = transaction == null ? database.pool() : transaction.connection();
      intercept(new Invocation<RowSet<Row>>(context.kind(), model, database.render(statement)), 0, invocation -> client
          .preparedQuery(invocation.statement().text()).execute(Tuple.from(invocation.statement().params())))
          .onComplete(promise);
    }).recover(failure -> Future.failedFuture(database.driver().duplicateKey(failure) ? new DuplicateKeyException(
        model, failure.getMessage(), failure) : failure));
  }

  /**
   * Streams the rows of a query: in the transaction of the current context, or in a read-only transaction of its own,
   * which ends with the stream.
   */
  ReadStream<Row> stream(Statement statement, int fetch) {
    if (fetch < 1) {
      throw new IllegalArgumentException("A stream fetches at least one row at a time, not " + fetch + ".");
    }
    return new DeferredRowStream(() -> Future.future(promise -> {
      Invocation<DeferredRowStream.Cursor> invocation = new Invocation<>(Invocation.Kind.QUERY, mapping.model(),
          database.render(statement));
      Transaction current = Transaction.current();
      if (current != null) {
        intercept(invocation, 0, done -> open(current.connection(), done, fetch))
            .map(cursor -> new DeferredRowStream.Source(cursor, failure -> Future.succeededFuture())).onComplete(promise);
        return;
      }
      TransactionOptions options = TransactionOptions.defaults().withReadOnly(true);
      database.pool().getConnection().compose(connection -> {
        Transaction transaction = new Transaction((ContextInternal) Vertx.currentContext(), connection, database
            .driver(), options, null);
        return transaction.start().compose(begin -> intercept(invocation, 0, done -> open(connection, done, fetch)))
            .map(cursor -> new DeferredRowStream.Source(cursor, failure -> transaction.end(failure == null ? Future
                .succeededFuture() : Future.failedFuture(failure)).<Void> mapEmpty().eventually(connection::close)))
            .recover(failure -> transaction.end(Future.failedFuture(failure)).eventually(connection::close)
                .transform(end -> Future.failedFuture(failure)));
      }).onComplete(promise);
    }));
  }

  private static Future<DeferredRowStream.Cursor> open(SqlConnection connection,
      Invocation<DeferredRowStream.Cursor> invocation, int fetch) {
    return connection.prepare(invocation.statement().text()).map(prepared -> new DeferredRowStream.Cursor(prepared,
        prepared.createStream(fetch, Tuple.from(invocation.statement().params()))));
  }

  private <R> Future<R> intercept(Invocation<R> invocation, int index, Function<Invocation<R>, Future<R>> last) {
    List<Interceptor> interceptors = database.interceptors();
    return Future.future(promise -> (index == interceptors.size() ? last.apply(invocation)
        : interceptors.get(index).intercept(invocation, next -> intercept(next, index + 1, last))).onComplete(promise));
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

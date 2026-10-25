package dev.horizon.cinder.impl;

import dev.horizon.aquery.aip158.PageTokenCodec;
import dev.horizon.basepoint.Decorator;
import dev.horizon.basepoint.Dialect;
import dev.horizon.basepoint.RenderedStatement;
import dev.horizon.basepoint.Statement;
import dev.horizon.basepoint.StatementWriter;
import dev.horizon.cinder.Call;
import dev.horizon.cinder.Database;
import dev.horizon.cinder.Delete;
import dev.horizon.cinder.Driver;
import dev.horizon.cinder.DuplicateKeyException;
import dev.horizon.cinder.Extension;
import dev.horizon.cinder.Insert;
import dev.horizon.cinder.Interceptor;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.ModelSource;
import dev.horizon.cinder.Query;
import dev.horizon.cinder.Transaction;
import dev.horizon.cinder.TransactionOptions;
import dev.horizon.cinder.TypeCodec;
import dev.horizon.cinder.Update;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.crypto.SecretKey;

final class CinderDatabase implements Database {

  private final Pool pool;
  private final Driver driver;
  private final Dialect dialect;
  private final List<Extension> extensions;
  private final List<TypeCodec> codecs;
  private final List<Interceptor> interceptors;
  private final Clock clock;
  private final PageTokenCodec tokens;
  private final int maxPageSize;
  private final Map<Class<?>, Model<?>> models = new ConcurrentHashMap<>();
  private final Map<Model<?>, Table<?>> tables = new ConcurrentHashMap<>();

  CinderDatabase(Pool pool, Driver driver, List<Extension> extensions, Clock clock, SecretKey pageTokenKey,
      int maxPageSize) {
    this.pool = pool;
    this.driver = driver;
    this.extensions = List.copyOf(extensions);
    this.codecs = this.extensions.stream().flatMap(extension -> extension.codecs().stream()).toList();
    this.interceptors = this.extensions.stream().flatMap(extension -> extension.interceptors().stream()).toList();
    Decorator decorator = this.extensions.stream().map(Extension::decorator).reduce(Decorator.none(),
        Decorator::then);
    this.dialect = driver.dialect().decorated(decorator);
    this.clock = clock;
    this.tokens = new PageTokenCodec(pageTokenKey);
    this.maxPageSize = maxPageSize;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <E> Model<E> model(E prototype) {
    return (Model<E>) models.computeIfAbsent(prototype.getClass(), type -> resolve(prototype));
  }

  @Override
  public <E> Query<E> query(Model<E> model) {
    return new QueryImpl<>(this, table(model));
  }

  @Override
  public <E> Insert<E> insert(Model<E> model) {
    return new InsertImpl<>(this, table(model));
  }

  @Override
  public <E> Update<E> update(Model<E> model) {
    return new UpdateImpl<>(this, table(model));
  }

  @Override
  public <E> Delete<E> delete(Model<E> model) {
    return new DeleteImpl<>(this, table(model));
  }

  @Override
  public <T> Future<T> transaction(Function<Transaction, Future<T>> work) {
    return transaction(TransactionOptions.defaults(), work);
  }

  @Override
  public <T> Future<T> transaction(TransactionOptions options, Function<Transaction, Future<T>> work) {
    ContextInternal context = (ContextInternal) Vertx.currentContext();
    if (context == null) {
      return pool.getConnection().compose(connection -> connection.close().compose(closed -> {
        ContextInternal own = (ContextInternal) Vertx.currentContext();
        return own == null ? Future.failedFuture(new IllegalStateException("The pool gives its connections on no"
            + " Vert.x context, so a transaction has no context to run on.")) : transaction(own, options, work);
      }));
    }
    return transaction(context, options, work);
  }

  /**
   * Runs work in a transaction on a context: in a new transaction, or in a savepoint of the transaction of the
   * context. A caller off every context runs on the context of the connections of the pool.
   */
  private <T> Future<T> transaction(ContextInternal context, TransactionOptions options,
      Function<Transaction, Future<T>> work) {
    TransactionImpl current = active(context);
    Future<T> result = current == null ? begin(context, options, work) : savepoint(context, current, work);
    Promise<T> promise = context.promise();
    result.onComplete(promise);
    return promise.future();
  }

  @Override
  public Future<Void> close() {
    return Future.succeededFuture();
  }

  @SuppressWarnings("unchecked")
  <E> Table<E> table(Model<E> model) {
    return (Table<E>) tables.computeIfAbsent(model, ignored -> new Table<>(model, driver, codecs,
        extensions.stream().flatMap(extension -> extension.parts(model).stream()).toList()));
  }

  Driver driver() {
    return driver;
  }

  PageTokenCodec tokens() {
    return tokens;
  }

  int maxPageSize() {
    return maxPageSize;
  }

  Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  TransactionImpl current() {
    ContextInternal context = (ContextInternal) Vertx.currentContext();
    return context == null ? null : active(context);
  }

  private static TransactionImpl active(ContextInternal context) {
    TransactionImpl transaction = context.getLocal(TransactionLocal.KEY);
    return transaction == null || transaction.ended() ? null : transaction;
  }

  Future<RowSet<Row>> run(Model<?> model, Call.Kind kind, Statement statement) {
    return Future.succeededFuture().compose(ignored -> {
      TransactionImpl transaction = current();
      SqlClient client = transaction == null ? pool : transaction.connection();
      return intercept(new Call<RowSet<Row>>(kind, model, render(statement)), 0, call -> client
          .preparedQuery(call.statement().text()).execute(Tuple.from(call.statement().params())));
    }).recover(failure -> Future.failedFuture(driver.duplicateKey(failure) ? new DuplicateKeyException(model,
        failure.getMessage(), failure) : failure));
  }

  /**
   * Reads the rows of a statement through a cursor. In the transaction of the context, the cursor uses its connection.
   * Without one, it takes a connection and a read-only transaction of its own, and ends them with the stream.
   */
  ReadStream<Row> stream(Model<?> model, Statement statement, int fetch) {
    if (fetch < 1) {
      throw new IllegalArgumentException("A stream fetches at least one row at a time, not " + fetch + ".");
    }
    return new DeferredRowStream(() -> Future.succeededFuture().compose(ignored -> {
      Call<DeferredRowStream.Cursor> call = new Call<>(Call.Kind.QUERY, model, render(statement));
      TransactionImpl current = current();
      if (current != null) {
        return intercept(call, 0, done -> open(current.connection(), done, fetch))
            .map(cursor -> new DeferredRowStream.Source(cursor, failure -> Future.succeededFuture()));
      }
      TransactionOptions options = TransactionOptions.defaults().withReadOnly(true);
      return pool.getConnection().compose(connection -> {
        TransactionImpl transaction = new TransactionImpl((ContextInternal) Vertx.currentContext(), connection,
            options, null);
        return sql(connection, driver.begin(options)).compose(begun -> intercept(call, 0, done -> open(connection,
            done, fetch))).map(cursor -> new DeferredRowStream.Source(cursor,
                failure -> end(transaction,
                    failure == null ? Future.succeededFuture() : Future.failedFuture(failure)).<Void> mapEmpty()
                    .eventually(connection::close)))
            .recover(failure -> end(transaction, Future.failedFuture(failure)).eventually(connection::close)
                .transform(ended -> Future.failedFuture(failure)));
      });
    }));
  }

  private Future<Void> sql(SqlConnection connection, List<String> statements) {
    Future<Void> done = Future.succeededFuture();
    for (String statement : statements) {
      done = done.compose(previous -> connection.query(statement).execute().mapEmpty());
    }
    return done;
  }

  private RenderedStatement render(Statement statement) {
    return new StatementWriter(dialect).render(statement).rendered();
  }

  private Future<DeferredRowStream.Cursor> open(SqlConnection connection, Call<DeferredRowStream.Cursor> call,
      int fetch) {
    return connection.prepare(call.statement().text()).map(prepared -> new DeferredRowStream.Cursor(prepared,
        prepared.createStream(fetch, Tuple.from(call.statement().params()))));
  }

  private <R> Future<R> intercept(Call<R> call, int index, Function<Call<R>, Future<R>> last) {
    return Future.succeededFuture().compose(ignored -> index == interceptors.size() ? last.apply(call)
        : interceptors.get(index).intercept(call, next -> intercept(next, index + 1, last)));
  }

  private <E> Model<E> resolve(E prototype) {
    for (Extension extension : extensions) {
      for (ModelSource source : extension.models()) {
        Optional<Model<E>> model = source.model(prototype);
        if (model.isPresent()) {
          return model.get();
        }
      }
    }
    throw new IllegalArgumentException("No model source of the database knows the type "
        + prototype.getClass().getName() + ". Add the extension of its models, such as the protobuf extension for"
        + " messages.");
  }

  /**
   * Begins a transaction on a duplicate of the context of the caller. The connection is taken on the duplicate, so
   * that the futures of its statements complete there, where the work finds its transaction.
   */
  private <T> Future<T> begin(ContextInternal context, TransactionOptions options,
      Function<Transaction, Future<T>> work) {
    ContextInternal duplicate = context.duplicate();
    Promise<T> promise = duplicate.promise();
    duplicate.runOnContext(ignored -> pool.getConnection().compose(connection -> {
      TransactionImpl transaction = new TransactionImpl(duplicate, connection, options, null);
      return sql(connection, driver.begin(options))
          .compose(begun -> within(transaction, work).transform(result -> end(transaction, result)))
          .eventually(connection::close);
    }).onComplete(promise));
    return promise.future();
  }

  private <T> Future<T> savepoint(ContextInternal context, TransactionImpl parent,
      Function<Transaction, Future<T>> work) {
    String name = parent.nextSavepoint();
    SqlConnection connection = parent.connection();
    TransactionImpl savepoint = new TransactionImpl(context.duplicate(), connection, parent.options(), parent);
    return sql(connection, List.of(driver.savepoint(name))).compose(started -> within(savepoint, work)
        .transform(result -> {
          if (result.succeeded() && !savepoint.isRollbackOnly()) {
            return sql(connection, driver.release(name).map(List::of).orElse(List.of())).map(released -> {
              savepoint.close();
              parent.adopt(savepoint);
              return result.result();
            });
          }
          return sql(connection, List.of(driver.rollbackTo(name))).transform(rolled -> {
            savepoint.close();
            savepoint.rolledBack();
            return rolled.failed() ? Future.failedFuture(rolled.cause()) : outcome(result);
          });
        }));
  }

  private <T> Future<T> within(TransactionImpl transaction, Function<Transaction, Future<T>> work) {
    ContextInternal context = transaction.context();
    context.putLocal(TransactionLocal.KEY, transaction);
    Promise<T> promise = context.promise();
    context.runOnContext(ignored -> {
      try {
        work.apply(transaction).onComplete(promise);
      } catch (RuntimeException failed) {
        promise.fail(failed);
      }
    });
    return promise.future();
  }

  private <T> Future<T> end(TransactionImpl transaction, AsyncResult<T> result) {
    SqlConnection connection = transaction.connection();
    if (result.succeeded() && !transaction.isRollbackOnly()) {
      return sql(connection, List.of(driver.commit())).transform(committed -> sql(connection,
          driver.end(transaction.options())).transform(ended -> {
            transaction.close();
            if (committed.failed()) {
              transaction.rolledBack();
              return Future.failedFuture(committed.cause());
            }
            transaction.committed();
            return Future.succeededFuture(result.result());
          }));
    }
    return sql(connection, List.of(driver.rollback())).transform(rolled -> sql(connection,
        driver.end(transaction.options()))).transform(ended -> {
          transaction.close();
          transaction.rolledBack();
          return outcome(result);
        });
  }

  private static <T> Future<T> outcome(AsyncResult<T> result) {
    return result.succeeded() ? Future.succeededFuture(result.result()) : Future.failedFuture(result.cause());
  }
}

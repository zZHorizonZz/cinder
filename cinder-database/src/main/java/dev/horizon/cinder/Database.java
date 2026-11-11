package dev.horizon.cinder;

import dev.horizon.aquery.aip158.PageTokenCodec;
import dev.horizon.basepoint.Dialect;
import dev.horizon.basepoint.RenderedStatement;
import dev.horizon.basepoint.statement.Decorator;
import dev.horizon.basepoint.statement.Statement;
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
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The tables that a pool reaches, through the SQL of a Basepoint dialect. The model sources of the extensions map
 * types of entities to the tables, such as the protobuf extension does for messages with a table option.
 *
 * <pre>{@code
 * Database database = Database.builder(pool, new PostgresDriver()).extension(new ProtobufExtension()).build();
 * Table<Order> orders = database.table(Order.getDefaultInstance());
 * }</pre>
 *
 * <p>
 * An operation runs in the transaction of the current Vert.x context, when {@link #transaction} started one.
 * Otherwise each of its statements commits as it runs. A transaction started off every Vert.x context runs on the
 * context of the connections of the pool.
 */
public final class Database {

  /** The most rows of a page, unless the builder sets another number. */
  public static final int MAX_PAGE_SIZE = 1000;

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
  private final Map<Model<?>, Mapping<?>> mappings = new ConcurrentHashMap<>();

  private Database(Builder builder) {
    this.pool = builder.pool;
    this.driver = builder.driver;
    this.extensions = List.copyOf(builder.extensions);
    this.codecs = extensions.stream().flatMap(extension -> extension.codecs().stream()).toList();
    this.interceptors = extensions.stream().flatMap(extension -> extension.interceptors().stream()).toList();
    Decorator decorator = extensions.stream().map(Extension::decorator).reduce(Decorator.none(), Decorator::then);
    this.dialect = driver.dialect().decorate(decorator);
    this.clock = builder.clock;
    this.tokens = new PageTokenCodec(builder.pageTokenKey == null ? Builder.randomKey() : builder.pageTokenKey);
    this.maxPageSize = builder.maxPageSize;
  }

  /**
   * Starts a database on a pool.
   *
   * @param pool the pool that runs the statements
   * @param driver the driver of the database of the pool, such as {@code new PostgresDriver()}
   * @return the builder of the database
   */
  public static Builder builder(Pool pool, Driver driver) {
    return new Builder(pool, driver);
  }

  /**
   * Gives the model of an entity's type, from the first model source that knows it.
   *
   * @param prototype an entity of the type, such as {@code Order.getDefaultInstance()}
   * @param <E> the type of the entities
   * @return the model
   * @throws IllegalArgumentException if no model source knows the type
   */
  @SuppressWarnings("unchecked")
  public <E> Model<E> model(E prototype) {
    return (Model<E>) models.computeIfAbsent(prototype.getClass(), type -> find(prototype));
  }

  /**
   * Gives the table of an entity's type.
   *
   * @param prototype an entity of the type, such as {@code Order.getDefaultInstance()}
   * @param <E> the type of the entities
   * @return the table, which runs the Basepoint statements of its model
   * @throws IllegalArgumentException if no model source knows the type
   */
  public <E> Table<E> table(E prototype) {
    return table(model(prototype));
  }

  /**
   * Gives the table of a model.
   *
   * @param model the model
   * @param <E> the type of the entities
   * @return the table, which runs the Basepoint statements of the model
   */
  public <E> Table<E> table(Model<E> model) {
    return new Table<>(this, mapping(model), List.of());
  }

  /**
   * Runs work in a transaction with the default options.
   *
   * @param work starts the operations of the work, and gives its future
   * @param <T> the type of the result of the work
   * @return the result of the work after the commit, or its failure after the rollback
   * @see #transaction(TransactionOptions, Function)
   */
  public <T> Future<T> transaction(Function<Transaction, Future<T>> work) {
    return transaction(TransactionOptions.defaults(), work);
  }

  /**
   * Runs work in a transaction. Every operation of the work, on any table of this database, runs in it. The
   * transaction commits when the future of the work succeeds, and rolls back when it fails or the work marks it
   * {@link Transaction#rollbackOnly() rollback only}.
   *
   * <p>
   * A transaction in the work of another runs as a savepoint of it: when the inner work fails, only its changes roll
   * back, and the outer work goes on if it handles the failure.
   *
   * @param options the isolation level and the access mode, which a savepoint keeps from its outer transaction
   * @param work starts the operations of the work, and gives its future
   * @param <T> the type of the result of the work
   * @return the result of the work after the commit, or its failure after the rollback. After a rollback that the
   * work asked for, the result of the work.
   */
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
   * Closes the database. The pool stays open, for the caller that made it to close.
   *
   * @return a future that completes when the database is closed
   */
  public Future<Void> close() {
    return Future.succeededFuture();
  }

  /**
   * Gives the future of work that can throw, with what it throws as the failure.
   */
  static <T> Future<T> attempt(Supplier<Future<T>> work) {
    try {
      return work.get();
    } catch (RuntimeException failed) {
      return Future.failedFuture(failed);
    }
  }

  @SuppressWarnings("unchecked")
  <E> Mapping<E> mapping(Model<E> model) {
    return (Mapping<E>) mappings.computeIfAbsent(model, ignored -> new Mapping<>(this, model, codecs,
        extensions.stream().flatMap(extension -> extension.parts(model).stream()).toList()));
  }

  <E> Mapping<E> mapping(E prototype) {
    return mapping(model(prototype));
  }

  Driver driver() {
    return driver;
  }

  Dialect dialect() {
    return dialect;
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

  Transaction current() {
    ContextInternal context = (ContextInternal) Vertx.currentContext();
    return context == null ? null : active(context);
  }

  RenderedStatement render(Statement statement) {
    return dialect.render(statement);
  }

  Future<RowSet<Row>> run(Model<?> model, Invocation.Kind kind, Statement statement) {
    return attempt(() -> {
      Transaction transaction = current();
      SqlClient client = transaction == null ? pool : transaction.connection();
      return intercept(new Invocation<RowSet<Row>>(kind, model, render(statement)), 0, invocation -> client
          .preparedQuery(invocation.statement().text()).execute(Tuple.from(invocation.statement().params())));
    }).recover(failure -> Future.failedFuture(driver.duplicateKey(failure) ? new DuplicateKeyException(model,
        failure.getMessage(), failure) : failure));
  }

  ReadStream<Row> stream(Model<?> model, Statement statement, int fetch) {
    if (fetch < 1) {
      throw new IllegalArgumentException("A stream fetches at least one row at a time, not " + fetch + ".");
    }
    return new DeferredRowStream(() -> attempt(() -> {
      Invocation<DeferredRowStream.Cursor> invocation = new Invocation<>(Invocation.Kind.QUERY, model,
          render(statement));
      Transaction current = current();
      if (current != null) {
        return intercept(invocation, 0, done -> open(current.connection(), done, fetch))
            .map(cursor -> new DeferredRowStream.Source(cursor, failure -> Future.succeededFuture()));
      }
      TransactionOptions options = TransactionOptions.defaults().withReadOnly(true);
      return pool.getConnection().compose(connection -> {
        Transaction transaction = new Transaction((ContextInternal) Vertx.currentContext(), connection, options,
            null);
        return sql(connection, driver.begin(options)).compose(begun -> intercept(invocation, 0, done -> open(
            connection, done, fetch))).map(cursor -> new DeferredRowStream.Source(cursor,
                failure -> end(transaction,
                    failure == null ? Future.succeededFuture() : Future.failedFuture(failure)).<Void> mapEmpty()
                    .eventually(connection::close)))
            .recover(failure -> end(transaction, Future.failedFuture(failure)).eventually(connection::close)
                .transform(ended -> Future.failedFuture(failure)));
      });
    }));
  }

  private <T> Future<T> transaction(ContextInternal context, TransactionOptions options,
      Function<Transaction, Future<T>> work) {
    Transaction current = active(context);
    Future<T> result = current == null ? begin(context, options, work) : savepoint(context, current, work);
    Promise<T> promise = context.promise();
    result.onComplete(promise);
    return promise.future();
  }

  private static Transaction active(ContextInternal context) {
    Transaction transaction = context.getLocal(TransactionLocal.KEY);
    return transaction == null || transaction.ended() ? null : transaction;
  }

  private Future<Void> sql(SqlConnection connection, List<String> statements) {
    Future<Void> done = Future.succeededFuture();
    for (String statement : statements) {
      done = done.compose(previous -> connection.query(statement).execute().mapEmpty());
    }
    return done;
  }

  private Future<DeferredRowStream.Cursor> open(SqlConnection connection,
      Invocation<DeferredRowStream.Cursor> invocation, int fetch) {
    return connection.prepare(invocation.statement().text()).map(prepared -> new DeferredRowStream.Cursor(prepared,
        prepared.createStream(fetch, Tuple.from(invocation.statement().params()))));
  }

  private <R> Future<R> intercept(Invocation<R> invocation, int index, Function<Invocation<R>, Future<R>> last) {
    return attempt(() -> index == interceptors.size() ? last.apply(invocation)
        : interceptors.get(index).intercept(invocation, next -> intercept(next, index + 1, last)));
  }

  private <E> Model<E> find(E prototype) {
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

  private <T> Future<T> begin(ContextInternal context, TransactionOptions options,
      Function<Transaction, Future<T>> work) {
    ContextInternal duplicate = context.duplicate();
    Promise<T> promise = duplicate.promise();
    duplicate.runOnContext(ignored -> pool.getConnection().compose(connection -> {
      Transaction transaction = new Transaction(duplicate, connection, options, null);
      return sql(connection, driver.begin(options))
          .compose(begun -> within(transaction, work).transform(result -> end(transaction, result)))
          .eventually(connection::close);
    }).onComplete(promise));
    return promise.future();
  }

  private <T> Future<T> savepoint(ContextInternal context, Transaction parent,
      Function<Transaction, Future<T>> work) {
    String name = parent.nextSavepoint();
    SqlConnection connection = parent.connection();
    Transaction savepoint = new Transaction(context.duplicate(), connection, parent.options(), parent);
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

  private <T> Future<T> within(Transaction transaction, Function<Transaction, Future<T>> work) {
    ContextInternal context = transaction.context();
    context.putLocal(TransactionLocal.KEY, transaction);
    Promise<T> promise = context.promise();
    context.runOnContext(ignored -> attempt(() -> work.apply(transaction)).onComplete(promise));
    return promise.future();
  }

  private <T> Future<T> end(Transaction transaction, AsyncResult<T> result) {
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

  /**
   * Sets up a database. Each setter gives this builder.
   */
  public static final class Builder {

    private static final int PAGE_TOKEN_KEY_BITS = 256;

    private final Pool pool;
    private final Driver driver;
    private final List<Extension> extensions = new ArrayList<>();
    private Clock clock = Clock.systemUTC();
    private SecretKey pageTokenKey;
    private int maxPageSize = MAX_PAGE_SIZE;

    private Builder(Pool pool, Driver driver) {
      this.pool = Objects.requireNonNull(pool, "pool");
      this.driver = Objects.requireNonNull(driver, "driver");
    }

    /**
     * Adds an extension. The database applies its extensions in the order they were added.
     *
     * @param extension the extension
     * @return this builder
     */
    public Builder extension(Extension extension) {
      extensions.add(Objects.requireNonNull(extension, "extension"));
      return this;
    }

    /**
     * Sets the clock of {@link Context#now()}, the time of each operation.
     *
     * @param clock the clock. The default is the system clock in UTC.
     * @return this builder
     */
    public Builder clock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock");
      return this;
    }

    /**
     * Sets the key of page tokens. Give every database that serves the same clients the same key, so that a token of
     * one is good at the others.
     *
     * @param key the AES key of 128, 192 or 256 bits. The default is a random key, so the tokens of a database do not
     * outlive it.
     * @return this builder
     */
    public Builder pageTokenKey(SecretKey key) {
      this.pageTokenKey = Objects.requireNonNull(key, "key");
      return this;
    }

    /**
     * Sets the most rows that a page holds. A page request for more gets this many, as AIP-158 asks, so that a client
     * cannot read a whole table at once.
     *
     * @param rows the most rows of a page, at least one. The default is {@value Database#MAX_PAGE_SIZE}.
     * @return this builder
     * @throws IllegalArgumentException if the number is less than one
     */
    public Builder maxPageSize(int rows) {
      if (rows < 1) {
        throw new IllegalArgumentException("A page holds at least one row, not " + rows + ".");
      }
      this.maxPageSize = rows;
      return this;
    }

    /**
     * Builds the database.
     *
     * @return the database
     */
    public Database build() {
      return new Database(this);
    }

    private static SecretKey randomKey() {
      try {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(PAGE_TOKEN_KEY_BITS);
        return generator.generateKey();
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException("Every Java platform has AES.", impossible);
      }
    }
  }
}

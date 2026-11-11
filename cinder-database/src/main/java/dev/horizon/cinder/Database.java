package dev.horizon.cinder;

import dev.horizon.aquery.aip158.PageTokenCodec;
import dev.horizon.basepoint.Dialect;
import dev.horizon.basepoint.RenderedStatement;
import dev.horizon.basepoint.statement.Decorator;
import dev.horizon.basepoint.statement.Statement;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.Pool;
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
 * Otherwise each of its statements commits as it runs. A transaction started outside a Vert.x context runs on the
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
    if (context != null) {
      return Transaction.run(context, pool, driver, options, work);
    }
    return pool.getConnection().compose(connection -> connection.close().compose(close -> {
      ContextInternal own = (ContextInternal) Vertx.currentContext();
      return own == null ? Future.failedFuture(new IllegalStateException("The pool gives its connections on no"
          + " Vert.x context, so a transaction has no context to run on."))
          : Transaction.run(own, pool, driver,
              options, work);
    }));
  }

  @SuppressWarnings("unchecked")
  <E> Mapping<E> mapping(Model<E> model) {
    return (Mapping<E>) mappings.computeIfAbsent(model, ignored -> new Mapping<>(this, model, codecs,
        extensions.stream().flatMap(extension -> extension.parts(model).stream()).toList()));
  }

  <E> Mapping<E> mapping(E prototype) {
    return mapping(model(prototype));
  }

  /**
   * Gives the mapping of the model of a table, for a statement inside another, which names its table and not its
   * model.
   */
  Mapping<?> mappingOf(String table) {
    List<Mapping<?>> found = mappings.values().stream().filter(mapping -> mapping.model().table().equals(table))
        .<Mapping<?>> map(mapping -> mapping).toList();
    if (found.size() == 1) {
      return found.getFirst();
    }
    throw new IllegalArgumentException(found.isEmpty() ? "No model of the database has the table " + table
        + ". Start the query of the table from the table of its model, such as database.table(prototype).query()."
        : "Several models of the database have the table " + table + ", so a statement of the table names none of"
            + " them.");
  }

  Pool pool() {
    return pool;
  }

  Driver driver() {
    return driver;
  }

  List<Interceptor> interceptors() {
    return interceptors;
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

  RenderedStatement render(Statement statement) {
    return dialect.render(statement);
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

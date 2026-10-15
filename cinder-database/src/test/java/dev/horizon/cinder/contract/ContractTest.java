package dev.horizon.cinder.contract;

import dev.horizon.basepoint.RenderedStatement;
import dev.horizon.cinder.Database;
import dev.horizon.cinder.Driver;
import dev.horizon.cinder.Extension;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ContractTest {

  protected static final Instant FIRST = Instant.parse("2026-01-01T10:00:00Z");

  private static final List<String> TABLES = List.of("files", "items", "orders", "customers");

  protected Driver driver;
  protected Vertx vertx;
  protected Pool pool;
  protected Database database;
  protected Shop.Orders orders;
  protected Shop.Customers customers;
  protected Shop.Items items;
  protected Shop.Files files;

  protected abstract TestDatabase testDatabase();

  protected List<Extension> extensions() {
    return List.of();
  }

  @BeforeAll
  void open() {
    TestDatabase tested = testDatabase();
    driver = tested.driver();
    vertx = Vertx.vertx();
    pool = tested.pool(vertx);
    for (String table : TABLES) {
      sql("DROP TABLE IF EXISTS " + table);
    }
    tested.schema().forEach(this::sql);
    Database.Builder builder = Database.builder(pool, driver).extension(new Shop());
    extensions().forEach(builder::extension);
    database = builder.build();
    orders = new Shop.Orders(database);
    customers = new Shop.Customers(database);
    files = new Shop.Files(database);
    items = new Shop.Items(database);
  }

  @AfterAll
  void close() {
    if (pool != null) {
      pool.close().await();
    }
    if (vertx != null) {
      vertx.close().await();
    }
  }

  @BeforeEach
  void load() {
    for (String table : TABLES) {
      sql("DELETE FROM " + table);
    }
    insert("customers", row("id", "anne", "email", "anne@example.com", "name", "Anne"),
        row("id", "beth", "email", "beth@example.com", "name", "Beth"),
        row("id", "carl", "email", "carl@example.com", "name", "Carl"));
    insert("orders", order(1, "anne", "OPEN", 15000, 0, day(0), null),
        order(2, "anne", "PAID", 8000, 1, day(1), null),
        order(3, "beth", "OPEN", 30000, 0, day(2), null),
        order(4, "beth", "CANCELLED", 5000, 2, day(3), day(4)),
        order(5, "carl", "OPEN", 22000, 0, day(4), null));
    insert("items", item(10, 1, "mug", 2, 2), item(11, 1, "cup", 1, 1), item(12, 3, "plate", 4, 1),
        item(13, 5, "mug", 1, 1));
  }

  protected static Instant day(int days) {
    return FIRST.plusSeconds(days * 86_400L);
  }

  protected void sql(String sql) {
    pool.query(sql).execute().await();
  }

  protected <T> T onContext(Supplier<Future<T>> work) {
    Promise<T> promise = Promise.promise();
    vertx.getOrCreateContext().runOnContext(ignored -> work.get().onComplete(promise));
    return promise.future().await();
  }

  protected Throwable failureOnContext(Supplier<Future<?>> work) {
    Promise<Object> promise = Promise.promise();
    vertx.getOrCreateContext().runOnContext(ignored -> work.get().onComplete(done -> {
      if (done.failed()) {
        promise.fail(done.cause());
      } else {
        promise.complete(done.result());
      }
    }));
    return failure(promise.future());
  }

  protected Throwable failure(Future<?> future) {
    Promise<Throwable> failure = Promise.promise();
    future.onComplete(done -> failure.complete(done.failed() ? done.cause() : null));
    Throwable cause = failure.future().await();
    if (cause == null) {
      throw new AssertionError("The future succeeded.");
    }
    return cause;
  }

  protected List<Long> ids(List<Shop.Order> found) {
    return found.stream().map(Shop.Order::id).toList();
  }

  private void insert(String table, Map<String, Object>... rows) {
    RenderedStatement statement = driver.dialect().collection(table).insert(List.of(rows)).build();
    List<Object> params = new ArrayList<>();
    statement.params().forEach(param -> params.add(driver.bind(param)));
    pool.preparedQuery(statement.text()).execute(Tuple.from(params)).await();
  }

  private static Map<String, Object> order(long id, String customer, String state, long total, int version,
      Instant created, Instant cancelled) {
    return row("id", id, "customer_id", customer, "state", state, "total_cents", total, "version", version,
        "created_at", created, "cancel_time", cancelled);
  }

  private static Map<String, Object> item(long id, long order, String product, int quantity, int position) {
    return row("id", id, "order_id", order, "product", product, "quantity", quantity, "position", position);
  }

  private static Map<String, Object> row(Object... pairs) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      row.put((String) pairs[i], pairs[i + 1]);
    }
    return row;
  }
}

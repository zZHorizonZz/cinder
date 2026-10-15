package dev.horizon.cinder.protobuf;

import static dev.horizon.basepoint.Filter.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import com.google.protobuf.Timestamp;
import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.cinder.Attribute;
import dev.horizon.cinder.Backend;
import dev.horizon.cinder.Database;
import dev.horizon.cinder.Extension;
import dev.horizon.cinder.Field;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.Relation;
import dev.horizon.cinder.TypeCodec;
import dev.horizon.cinder.protobuf.shop.Address;
import dev.horizon.cinder.protobuf.shop.Customer;
import dev.horizon.cinder.protobuf.shop.Ledger;
import dev.horizon.cinder.protobuf.shop.Order;
import dev.horizon.cinder.protobuf.shop.OrderLine;
import dev.horizon.cinder.protobuf.shop.Shop;
import dev.horizon.cinder.protobuf.shop.State;
import dev.horizon.cinder.protobuf.shop.Token;
import dev.horizon.cinder.sqlite.SqliteDriver;
import io.vertx.core.Vertx;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProtobufExtensionTest {

  private static final Attribute<Boolean> SECRET = ProtobufField.attribute(Shop.secret);

  private record Orders(Database database) implements Backend<Order, Long> {

    @Override
    public Class<Order> type() {
      return Order.class;
    }
  }

  private record Customers(Database database) implements Backend<Customer, String> {

    @Override
    public Class<Customer> type() {
      return Customer.class;
    }
  }

  private record Tokens(Database database) implements Backend<Token, ByteString> {

    @Override
    public Class<Token> type() {
      return Token.class;
    }
  }

  private record Lines(Database database) implements Backend<OrderLine, Long> {

    @Override
    public Class<OrderLine> type() {
      return OrderLine.class;
    }
  }

  private static final class Hidden implements Extension, TypeCodec {

    @Override
    public List<TypeCodec> codecs() {
      return List.of(this);
    }

    @Override
    public boolean applies(Field<?, ?> field) {
      return field.attribute(SECRET).orElse(false);
    }

    @Override
    public Object write(Field<?, ?> field, Object value) {
      return "hidden:" + value;
    }

    @Override
    public Object read(Field<?, ?> field, Object stored) {
      return ((String) stored).substring("hidden:".length());
    }
  }

  private Vertx vertx;
  private Pool pool;
  private Database database;
  private Orders orders;
  private Customers customers;
  private Lines lines;

  @BeforeAll
  void open() throws Exception {
    Path file = Files.createTempFile("cinder-protobuf", ".db");
    file.toFile().deleteOnExit();
    vertx = Vertx.vertx();
    pool = JDBCPool.pool(vertx, new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite:" + file),
        new PoolOptions().setMaxSize(1));
    for (String sql : List.of(
        "CREATE TABLE customers (id TEXT PRIMARY KEY, email TEXT NOT NULL)",
        "CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id TEXT NOT NULL, state TEXT NOT NULL,"
            + " created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')), discount INTEGER,"
            + " tags TEXT NOT NULL, address TEXT, counts TEXT NOT NULL, token BLOB NOT NULL)",
        "CREATE TABLE order_line (id INTEGER PRIMARY KEY, order_id INTEGER NOT NULL, product TEXT NOT NULL,"
            + " note TEXT NOT NULL)",
        "CREATE TABLE tokens (id BLOB PRIMARY KEY, label TEXT NOT NULL)")) {
      pool.query(sql).execute().await();
    }
    database = Database.builder(pool, new SqliteDriver()).extension(new ProtobufExtension()).extension(new Hidden())
        .build();
    orders = new Orders(database);
    customers = new Customers(database);
    lines = new Lines(database);
  }

  @AfterAll
  void close() {
    pool.close().await();
    vertx.close().await();
  }

  @BeforeEach
  void empty() {
    for (String table : List.of("order_line", "orders", "customers", "tokens")) {
      pool.query("DELETE FROM " + table).execute().await();
    }
    customers.insert(Customer.newBuilder().setId("anne").setEmail("anne@example.com").build()).await();
  }

  @Test
  void mapsAMessageToItsTable() {
    Model<Order> model = database.model(Order.class);
    assertThat(model.table()).isEqualTo("orders");
    assertThat(model.field("create_time").orElseThrow().column()).isEqualTo("created_at");
    assertThat(model.key()).extracting(Field::name).containsExactly("id");
    assertThat(model.relations()).containsExactly(
        new Relation<>("customer", Customer.class, Relation.Shape.ONE, List.of("customer_id"), List.of("id")),
        new Relation<>("lines", OrderLine.class, Relation.Shape.MANY, List.of("id"), List.of("order_id")));
    assertThat(database.model(OrderLine.class).table()).isEqualTo("order_line");
  }

  @Test
  void writesAndReadsEveryKindOfField() {
    Order order = Order.newBuilder().setCustomerId("anne").setState(State.PAID)
        .setCreateTime(Timestamp.newBuilder().setSeconds(1_767_261_600L).setNanos(123_456_000))
        .setDiscount(Int64Value.of(250)).addAllTags(List.of("gift", "rush"))
        .setAddress(Address.newBuilder().setCity("Brno").setStreet("Main")).putCounts("mug", 2)
        .setToken(ByteString.copyFromUtf8("token")).build();
    Order created = orders.insert(order).await();
    assertThat(created.getId()).isPositive();
    assertThat(created.toBuilder().clearId().build()).isEqualTo(order);
    assertThat(orders.findById(created.getId()).await()).contains(created);
  }

  @Test
  void leavesTheFieldsThatTheDatabaseFillsToIt() {
    Instant before = Instant.now().minusSeconds(1);
    Order created = orders.insert(Order.newBuilder().setCustomerId("anne").setState(State.OPEN).build()).await();
    assertThat(created.getId()).isPositive();
    assertThat(Instant.ofEpochSecond(created.getCreateTime().getSeconds())).isAfter(before);
    assertThat(created.hasDiscount()).isFalse();
  }

  @Test
  void filtersByTheValuesOfAField() {
    orders.insert(Order.newBuilder().setCustomerId("anne").setState(State.OPEN).build()).await();
    orders.insert(Order.newBuilder().setCustomerId("anne").setState(State.PAID).build()).await();
    assertThat(orders.find(eq("state", State.PAID)).count().await()).isEqualTo(1L);
    assertThat(orders.find("state = \"OPEN\"").count().await()).isEqualTo(1L);
    assertThatThrownBy(() -> orders.find("state = \"LOST\"").count().await())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsTheRelatedMessages() {
    Order created = orders.insert(Order.newBuilder().setCustomerId("anne").setState(State.OPEN).build()).await();
    lines.insertAll(List.of(OrderLine.newBuilder().setId(1).setOrderId(created.getId()).setProduct("mug")
        .setNote("fragile").build(),
        OrderLine.newBuilder().setId(2).setOrderId(created.getId()).setProduct("cup")
            .build()))
        .await();
    Order found = orders.find(eq("id", created.getId())).include("customer").include("lines", rows -> rows
        .orderBy("id")).single().await();
    assertThat(found.getCustomer().getEmail()).isEqualTo("anne@example.com");
    assertThat(found.getLinesList()).extracting(OrderLine::getProduct).containsExactly("mug", "cup");
    assertThat(found.getLines(0).getNote()).isEqualTo("fragile");
  }

  @Test
  void namesTheTableInItsSchema() {
    assertThat(database.model(Ledger.class).table()).isEqualTo("audit.ledger");
  }

  @Test
  void pagesByAKeyOfBytes() {
    Tokens tokens = new Tokens(database);
    for (int i = 1; i <= 3; i++) {
      tokens.insert(Token.newBuilder().setId(ByteString.copyFrom(new byte[] { (byte) i, 0 })).setLabel("t" + i)
          .build()).await();
    }
    List<String> labels = new ArrayList<>();
    String next = "";
    do {
      Page<Token> page = tokens.list(new PageRequest("", "", 1, next)).await();
      page.items().forEach(token -> labels.add(token.getLabel()));
      next = page.nextPageToken();
    } while (!next.isEmpty());
    assertThat(labels).containsExactly("t1", "t2", "t3");
  }

  @Test
  void givesTheCustomOptionsAsAttributes() {
    lines.insert(OrderLine.newBuilder().setId(3).setOrderId(1).setProduct("plate").setNote("glass").build()).await();
    Object stored = pool.query("SELECT note FROM order_line").execute().await().iterator().next().getValue(0);
    assertThat(stored).isEqualTo("hidden:glass");
    assertThat(lines.findById(3L).await().orElseThrow().getNote()).isEqualTo("glass");
  }
}

package dev.horizon.cinder.protobuf;

import static dev.horizon.basepoint.expression.Expression.key;
import static dev.horizon.basepoint.filter.Filter.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import com.google.protobuf.Timestamp;
import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.cinder.Attribute;
import dev.horizon.cinder.Database;
import dev.horizon.cinder.Extension;
import dev.horizon.cinder.Field;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.Relation;
import dev.horizon.cinder.Table;
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
import dev.horizon.leadseal.protobuf.BooleanColumn;
import dev.horizon.leadseal.protobuf.BytesColumn;
import dev.horizon.leadseal.protobuf.ColumnOptions;
import dev.horizon.leadseal.protobuf.DateColumn;
import dev.horizon.leadseal.protobuf.DecimalColumn;
import dev.horizon.leadseal.protobuf.IntegerColumn;
import dev.horizon.leadseal.protobuf.JsonColumn;
import dev.horizon.leadseal.protobuf.NumberColumn;
import dev.horizon.leadseal.protobuf.TextColumn;
import dev.horizon.leadseal.protobuf.TimeColumn;
import dev.horizon.leadseal.protobuf.TimestampColumn;
import dev.horizon.leadseal.protobuf.UuidColumn;
import io.vertx.core.Vertx;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProtobufExtensionTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private static final Attribute<Boolean> SECRET = ProtobufField.attribute(Shop.secret);

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
  private Table<Order> orders;
  private Table<Customer> customers;
  private Table<OrderLine> lines;

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
            + " created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now') || '000Z'), discount INTEGER,"
            + " tags TEXT NOT NULL, address TEXT, counts TEXT NOT NULL, token BLOB,"
            + " label TEXT GENERATED ALWAYS AS (upper(customer_id)) VIRTUAL)",
        "CREATE TABLE order_line (id INTEGER PRIMARY KEY, order_id INTEGER NOT NULL, product TEXT NOT NULL,"
            + " note TEXT NOT NULL)",
        "CREATE TABLE tokens (id BLOB PRIMARY KEY, label TEXT NOT NULL)")) {
      pool.query(sql).execute().await(TIMEOUT);
    }
    database = Database.builder(pool, new SqliteDriver()).extension(new ProtobufExtension()).extension(new Hidden())
        .build();
    orders = database.table(Order.getDefaultInstance());
    customers = database.table(Customer.getDefaultInstance());
    lines = database.table(OrderLine.getDefaultInstance());
  }

  @AfterAll
  void close() throws TimeoutException {
    pool.close().await(TIMEOUT);
    vertx.close().await(TIMEOUT);
  }

  @BeforeEach
  void empty() throws TimeoutException {
    for (String table : List.of("order_line", "orders", "customers", "tokens")) {
      pool.query("DELETE FROM " + table).execute().await(TIMEOUT);
    }
    customers.insert(Customer.newBuilder().setId("anne").setEmail("anne@example.com").build()).await(TIMEOUT);
  }

  @Test
  @DisplayName("Maps a message to its table, its fields and its key")
  void table() {
    Model<Order> model = database.model(Order.getDefaultInstance());
    assertThat(model.table()).isEqualTo("orders");
    assertThat(model.field("create_time").orElseThrow().column()).isEqualTo("created_at");
    assertThat(model.key()).extracting(Field::name).containsExactly("id");
    assertThat(model.relations()).containsExactly(
        new Relation<>("customer", Customer.getDefaultInstance(), Relation.Shape.ONE, List.of("customer_id"), List.of("id")),
        new Relation<>("lines", OrderLine.getDefaultInstance(), Relation.Shape.MANY, List.of("id"), List.of("order_id")));
    assertThat(database.model(OrderLine.getDefaultInstance()).table()).isEqualTo("order_line");
  }

  @Test
  @DisplayName("Writes and reads every kind of field")
  void everyKind() throws TimeoutException {
    Order order = Order.newBuilder().setCustomerId("anne").setState(State.PAID)
        .setCreateTime(Timestamp.newBuilder().setSeconds(1_767_261_600L).setNanos(123_456_000))
        .setDiscount(Int64Value.of(250)).addAllTags(List.of("gift", "rush"))
        .setAddress(Address.newBuilder().setCity("Brno").setStreet("Main")).putCounts("mug", 2)
        .setToken(ByteString.copyFromUtf8("token")).build();
    Order created = orders.insert(order).await(TIMEOUT);
    assertThat(created.getId()).isPositive();
    assertThat(created.toBuilder().clearId().clearLabel().build()).isEqualTo(order);
    assertThat(created.getLabel()).isEqualTo("ANNE");
    assertThat(orders.find(created.getId()).await(TIMEOUT)).contains(created);
  }

  @Test
  @DisplayName("Leaves the fields that the database fills to it")
  void databaseDefaults() throws TimeoutException {
    Instant before = Instant.now().minusSeconds(1);
    Order created = orders.insert(Order.newBuilder().setCustomerId("anne").setState(State.OPEN).build()).await(TIMEOUT);
    assertThat(created.getId()).isPositive();
    assertThat(Instant.ofEpochSecond(created.getCreateTime().getSeconds())).isAfter(before);
    assertThat(created.hasDiscount()).isFalse();
  }

  @Test
  @DisplayName("Filters by the values of a field, and refuses a value of no enum")
  void filter() throws TimeoutException {
    orders.insert(Order.newBuilder().setCustomerId("anne").setState(State.OPEN).build()).await(TIMEOUT);
    orders.insert(Order.newBuilder().setCustomerId("anne").setState(State.PAID).build()).await(TIMEOUT);
    assertThat(orders.count(orders.query().where(eq("state", State.PAID))).await(TIMEOUT)).isEqualTo(1L);
    assertThat(orders.count(orders.query().where("state = \"OPEN\"")).await(TIMEOUT)).isEqualTo(1L);
    assertThatThrownBy(() -> orders.count(orders.query().where("state = \"LOST\"")).await(TIMEOUT))
        .isInstanceOf(IllegalArgumentException.class).hasMessageEndingWith("has no value LOST.");
  }

  @Test
  @DisplayName("Filters by a key of a map or a message")
  void filterKey() throws TimeoutException {
    orders.insert(Order.newBuilder().setCustomerId("anne").setState(State.OPEN).putCounts("mug", 2)
        .setAddress(Address.newBuilder().setCity("Brno")).build()).await(TIMEOUT);
    orders.insert(Order.newBuilder().setCustomerId("anne").setState(State.OPEN).putCounts("cup", 1)
        .setAddress(Address.newBuilder().setCity("Prague")).build()).await(TIMEOUT);
    assertThat(orders.count(orders.query().where(eq(key("address", "city"), "Brno"))).await(TIMEOUT)).isEqualTo(1L);
    assertThat(orders.count(orders.query().where("address.city = Prague")).await(TIMEOUT)).isEqualTo(1L);
    assertThat(orders.count(orders.query().where("counts.mug = 2")).await(TIMEOUT)).isEqualTo(1L);
    assertThat(orders.count(orders.query().where("counts:cup")).await(TIMEOUT)).isEqualTo(1L);
    assertThatThrownBy(() -> orders.count(orders.query().where("state.name = OPEN")).await(TIMEOUT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageEndingWith("is not stored as a JSON object, so it has no key name.");
  }

  @Test
  @DisplayName("Reads the related messages of a relation")
  void include() throws TimeoutException {
    Order created = orders.insert(Order.newBuilder().setCustomerId("anne").setState(State.OPEN).build()).await(TIMEOUT);
    lines.insert(List.of(OrderLine.newBuilder().setId(1).setOrderId(created.getId()).setProduct("mug")
        .setNote("fragile").build(),
        OrderLine.newBuilder().setId(2).setOrderId(created.getId()).setProduct("cup")
            .build()))
        .await(TIMEOUT);
    Order found = orders.single(orders.query().where(eq("id", created.getId())).includeOne("customer", orders
        .related("customer")).include("lines", orders.related("lines").orderBy("id"))).await(TIMEOUT);
    assertThat(found.getCustomer().getEmail()).isEqualTo("anne@example.com");
    assertThat(found.getLinesList()).extracting(OrderLine::getProduct).containsExactly("mug", "cup");
    assertThat(found.getLines(0).getNote()).isEqualTo("fragile");
  }

  @Test
  @DisplayName("Names the table in its schema")
  void schema() {
    assertThat(database.model(Ledger.getDefaultInstance()).table()).isEqualTo("audit.ledger");
  }

  @Test
  @DisplayName("Pages by a key of bytes")
  void bytesKey() throws TimeoutException {
    Table<Token> tokens = database.table(Token.getDefaultInstance());
    for (int i = 1; i <= 3; i++) {
      tokens.insert(Token.newBuilder().setId(ByteString.copyFrom(new byte[] { (byte) i, 0 })).setLabel("t" + i)
          .build()).await(TIMEOUT);
    }
    List<String> labels = new ArrayList<>();
    String next = "";
    do {
      Page<Token> page = tokens.page(tokens.query(), new PageRequest("", "", 1, next)).await(TIMEOUT);
      page.items().forEach(token -> labels.add(token.getLabel()));
      next = page.nextPageToken();
    } while (!next.isEmpty());
    assertThat(labels).containsExactly("t1", "t2", "t3");
  }

  @Test
  @DisplayName("Gives the custom options of a field as its attributes")
  void attributes() throws TimeoutException {
    lines.insert(OrderLine.newBuilder().setId(3).setOrderId(1).setProduct("plate").setNote("glass").build()).await(TIMEOUT);
    Object stored = pool.query("SELECT note FROM order_line").execute().await(TIMEOUT).iterator().next().getValue(0);
    assertThat(stored).isEqualTo("hidden:glass");
    assertThat(lines.find(3L).await(TIMEOUT).orElseThrow().getNote()).isEqualTo("glass");
  }

  @Test
  @DisplayName("Leaves a generated column to the database in an insert, and reads its value back")
  void generatedInsert() throws TimeoutException {
    Order created = orders.insert(Order.newBuilder().setCustomerId("beth").setState(State.OPEN).setLabel("mine")
        .build()).await(TIMEOUT);

    assertThat(created.getLabel()).isEqualTo("BETH");
    assertThat(orders.find(created.getId()).await(TIMEOUT)).map(Order::getLabel).contains("BETH");
  }

  @Test
  @DisplayName("Leaves a generated column to the database in an update, and in an update of every field")
  void generatedUpdate() throws TimeoutException {
    Order created = orders.insert(Order.newBuilder().setCustomerId("anne").setState(State.OPEN).build())
        .await(TIMEOUT);

    orders.execute(orders.update().set("label", "mine").set("state", State.OPEN).where(eq("id", created.getId())))
        .await(TIMEOUT);
    Order patched = orders.update(created.toBuilder().setState(State.PAID).setLabel("mine").build(), "*")
        .await(TIMEOUT).orElseThrow();

    assertThat(patched.getState()).isEqualTo(State.PAID);
    assertThat(patched.getLabel()).isEqualTo("ANNE");
  }

  @Test
  @DisplayName("Writes a defaulted field that the message sets")
  void defaultSet() throws TimeoutException {
    Timestamp time = Timestamp.newBuilder().setSeconds(1_767_261_600L).build();

    Order created = orders.insert(Order.newBuilder().setCustomerId("anne").setState(State.OPEN).setCreateTime(time)
        .build()).await(TIMEOUT);

    assertThat(created.getCreateTime()).isEqualTo(time);
  }

  static Stream<Arguments> columns() {
    return Stream.of(
        Arguments.of("no type", ColumnOptions.getDefaultInstance(), false, false),
        Arguments.of("generated", ColumnOptions.newBuilder().setGenerated("upper(note)").build(), true, false),
        Arguments.of("boolean default", ColumnOptions.newBuilder().setBoolean(BooleanColumn.newBuilder()
            .setDefaultValue(false)).build(), false, true),
        Arguments.of("integer", ColumnOptions.newBuilder().setInteger(IntegerColumn.getDefaultInstance()).build(),
            false, false),
        Arguments.of("integer identity", ColumnOptions.newBuilder().setInteger(IntegerColumn.newBuilder()
            .setIdentity(true)).build(), false, true),
        Arguments.of("integer default", ColumnOptions.newBuilder().setInteger(IntegerColumn.newBuilder()
            .setDefaultValue(0)).build(), false, true),
        Arguments.of("number default", ColumnOptions.newBuilder().setNumber(NumberColumn.newBuilder()
            .setDefaultValue(1.5)).build(), false, true),
        Arguments.of("decimal default", ColumnOptions.newBuilder().setDecimal(DecimalColumn.newBuilder()
            .setDefaultValue("0.00")).build(), false, true),
        Arguments.of("text", ColumnOptions.newBuilder().setText(TextColumn.newBuilder().setLength(20)).build(),
            false, false),
        Arguments.of("text default", ColumnOptions.newBuilder().setText(TextColumn.newBuilder()
            .setDefaultValue("")).build(), false, true),
        Arguments.of("bytes default", ColumnOptions.newBuilder().setBytes(BytesColumn.newBuilder()
            .setDefaultValue(ByteString.EMPTY)).build(), false, true),
        Arguments.of("random uuid", ColumnOptions.newBuilder().setUuid(UuidColumn.newBuilder()
            .setDefaultRandom(true)).build(), false, true),
        Arguments.of("current time", ColumnOptions.newBuilder().setTimestamp(TimestampColumn.newBuilder()
            .setDefaultNow(true)).build(), false, true),
        Arguments.of("timestamp", ColumnOptions.newBuilder().setTimestamp(TimestampColumn.getDefaultInstance())
            .build(), false, false),
        Arguments.of("current date", ColumnOptions.newBuilder().setDate(DateColumn.newBuilder()
            .setDefaultToday(true)).build(), false, true),
        Arguments.of("time default", ColumnOptions.newBuilder().setTime(TimeColumn.newBuilder()
            .setDefaultValue("09:00")).build(), false, true),
        Arguments.of("json default", ColumnOptions.newBuilder().setJson(JsonColumn.newBuilder()
            .setDefaultValue("{}")).build(), false, true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("columns")
  @DisplayName("Tells the columns that the database fills by the typed Leadseal options")
  void filled(String name, ColumnOptions column, boolean generated, boolean defaulted) {
    assertThat(ProtobufLeadsealAdapter.isGenerated(column)).isEqualTo(generated);
    assertThat(ProtobufLeadsealAdapter.hasDefault(column)).isEqualTo(defaulted);
  }
}

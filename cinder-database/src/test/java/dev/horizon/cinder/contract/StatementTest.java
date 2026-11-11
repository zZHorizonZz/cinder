package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.Direction.DESC;
import static dev.horizon.basepoint.expression.Expression.column;
import static dev.horizon.basepoint.expression.Expression.sum;
import static dev.horizon.basepoint.filter.Filter.eq;
import static dev.horizon.basepoint.filter.Filter.gt;
import static org.assertj.core.api.Assertions.assertThat;

import dev.horizon.aquery.ParameterStyle;
import dev.horizon.basepoint.RenderedStatement;
import dev.horizon.basepoint.postgres.PostgresDialect;
import dev.horizon.basepoint.statement.Statement;
import dev.horizon.cinder.Conflict;
import dev.horizon.cinder.Context;
import dev.horizon.cinder.Database;
import dev.horizon.cinder.Driver;
import dev.horizon.cinder.Extension;
import dev.horizon.cinder.Interceptor;
import dev.horizon.cinder.Invocation;
import dev.horizon.cinder.Part;
import dev.horizon.cinder.Table;
import dev.horizon.cinder.TypeCodec;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StatementTest {

  private static final Instant DAY = Instant.parse("2026-01-01T10:00:00Z");

  private final List<Invocation<?>> invocations = new ArrayList<>();
  private Table<Shop.Order> orders;
  private Table<Shop.Customer> customers;
  private Table<Shop.Item> items;

  private static final class Kept extends RuntimeException {

    private final transient RenderedStatement statement;

    Kept(RenderedStatement statement) {
      super(statement.text(), null, false, false);
      this.statement = statement;
    }
  }

  private final class Keeper implements Extension, Interceptor {

    @Override
    public List<TypeCodec> codecs() {
      return List.of(new Reversed());
    }

    @Override
    public List<Interceptor> interceptors() {
      return List.of(this);
    }

    @Override
    public <R> Future<R> intercept(Invocation<R> invocation, Chain<R> next) {
      invocations.add(invocation);
      return Future.failedFuture(new Kept(invocation.statement()));
    }
  }

  @BeforeEach
  void database() {
    Pool pool = (Pool) Proxy.newProxyInstance(Pool.class.getClassLoader(), new Class<?>[] { Pool.class },
        (proxy, method, arguments) -> {
          throw new UnsupportedOperationException("The test has no database, so its pool does not " + method
              .getName() + ".");
        });
    Driver driver = () -> new PostgresDialect(ParameterStyle.DOLLAR);
    Database database = Database.builder(pool, driver).extension(new Shop()).extension(new Keeper()).build();
    orders = database.table(Shop.ORDER);
    customers = database.table(Shop.CUSTOMER);
    items = database.table(Shop.ITEM);
  }

  @Test
  @DisplayName("Maps each field to its column")
  void fieldColumn() {
    RenderedStatement statement = sql(orders.list(orders.query().where(gt("create_time", DAY))
        .orderBy("create_time", DESC)));
    assertThat(statement.text()).contains("\"created_at\" > $1").contains("ORDER BY \"t0\".\"created_at\" DESC")
        .doesNotContain("create_time");
  }

  @Test
  @DisplayName("Reads every field when a query names none")
  void everyField() {
    assertThat(sql(orders.list(orders.query())).text()).startsWith("SELECT \"t0\".\"id\", \"t0\".\"customer_id\"")
        .contains("\"t0\".\"created_at\"").contains("\"t0\".\"cancel_time\"").doesNotContain("*");
  }

  @Test
  @DisplayName("Reads only the fields that a query names")
  void namedFields() {
    assertThat(sql(orders.list(orders.query().columns("id", "state"))).text())
        .startsWith("SELECT \"t0\".\"id\", \"t0\".\"state\" FROM");
  }

  @Test
  @DisplayName("Sends each value through the codecs of its field")
  void codec() {
    assertThat(sql(customers.list(customers.query().where(eq("name", "Dora")))).params()).containsExactly("aroD");
    assertThat(sql(customers.execute(customers.update().set("name", "Dora").where(eq("id", "dora")))).params())
        .containsExactly("aroD", "dora");
    assertThat(sql(customers.insert(new Shop.Customer("dora", "dora@example.com", "Dora"))).params())
        .containsExactly("dora", "dora@example.com", "aroD");
  }

  @Test
  @DisplayName("Writes null for an unset field")
  void unset() {
    assertThat(sql(orders.execute(orders.update().unset("cancel_time").where(eq("id", 1L)))).text())
        .contains("SET \"cancel_time\" = NULL").doesNotContain("DEFAULT");
  }

  @Test
  @DisplayName("Increments a column by its own value")
  void increment() {
    assertThat(sql(orders.execute(orders.update().increment("version", 1).all())).text())
        .contains("\"version\" = (\"orders\".\"version\" + $1)");
  }

  @Test
  @DisplayName("Merges only the fields of a conflict")
  void mergeFields() {
    String text = sql(customers.insert(List.of(new Shop.Customer("dora", "anne@example.com", "Anne")),
        Conflict.merge("name").on("email"))).text();
    assertThat(text).contains("ON CONFLICT (\"email\") DO UPDATE SET \"name\" = EXCLUDED.\"name\"")
        .doesNotContain("\"id\" = EXCLUDED");
  }

  @Test
  @DisplayName("Returns the column of each field")
  void returning() {
    assertThat(sql(orders.returning(orders.delete().where(eq("id", 1L)))).text())
        .endsWith("RETURNING \"id\", \"customer_id\", \"state\", \"total_cents\", \"version\", \"created_at\","
            + " \"cancel_time\"");
  }

  @Test
  @DisplayName("Joins the related rows by the columns of their relation")
  void include() {
    String text = sql(orders.list(orders.query().include("items", orders.related("items").columns("product"))))
        .text();
    assertThat(text).contains("SELECT \"t2\".\"product\" FROM \"items\" AS \"t2\" WHERE (\"t2\".\"order_id\" = \"t0\".\"id\")");
  }

  @Test
  @DisplayName("Keeps an order by a label of the query")
  void label() {
    String text = sql(orders.rows(orders.query().select("customer_id", column("customer_id"))
        .select("total", sum("total_cents")).groupBy("customer_id").orderBy("total", DESC))).text();
    assertThat(text).contains("GROUP BY \"t0\".\"customer_id\"").endsWith("ORDER BY \"total\" DESC");
  }

  @Test
  @DisplayName("Runs the parts of a table before the mapping")
  void part() {
    Part recent = new Part() {
      @Override
      public Statement shape(Statement statement, Context<?> context) {
        return statement.where(gt("create_time", DAY));
      }
    };
    assertThat(sql(orders.with(recent).count(orders.query())).text()).contains("\"created_at\" > $1");
  }

  @Test
  @DisplayName("Gives each statement the kind of its operation")
  void kind() {
    sql(orders.list(orders.query()));
    sql(orders.insert(new Shop.Order(6L, "carl", "OPEN", 1L, DAY)));
    sql(orders.execute(orders.update().set("state", "PAID").all()));
    sql(orders.execute(orders.delete().all()));
    assertThat(invocations).extracting(Invocation::kind).containsExactly(Invocation.Kind.QUERY,
        Invocation.Kind.INSERT, Invocation.Kind.UPDATE, Invocation.Kind.DELETE);
    assertThat(invocations).allSatisfy(invocation -> assertThat(invocation.model().table()).isEqualTo("orders"));
  }

  static Stream<Arguments> unknownFields() {
    return Stream.of(
        Arguments.of("filter", (Function<Table<Shop.Order>, Future<?>>) table -> table.list(table.query()
            .where(eq("colour", "red")))),
        Arguments.of("text filter", (Function<Table<Shop.Order>, Future<?>>) table -> table.list(table.query()
            .where("colour = \"red\""))),
        Arguments.of("order", (Function<Table<Shop.Order>, Future<?>>) table -> table.list(table.query()
            .orderBy("colour"))),
        Arguments.of("column", (Function<Table<Shop.Order>, Future<?>>) table -> table.list(table.query()
            .columns("colour"))),
        Arguments.of("group", (Function<Table<Shop.Order>, Future<?>>) table -> table.rows(table.query()
            .select("n", column("id")).groupBy("colour"))),
        Arguments.of("aggregate", (Function<Table<Shop.Order>, Future<?>>) table -> table.rows(table.query()
            .select("total", sum("colour")))),
        Arguments.of("count", (Function<Table<Shop.Order>, Future<?>>) table -> table.count(table.query()
            .where(eq("colour", "red")))),
        Arguments.of("set", (Function<Table<Shop.Order>, Future<?>>) table -> table.execute(table.update()
            .set("colour", "red").all())),
        Arguments.of("update filter", (Function<Table<Shop.Order>, Future<?>>) table -> table.execute(table.update()
            .set("state", "PAID").where("colour = \"red\""))),
        Arguments.of("delete filter", (Function<Table<Shop.Order>, Future<?>>) table -> table.execute(table.delete()
            .where(eq("colour", "red")))),
        Arguments.of("include filter", (Function<Table<Shop.Order>, Future<?>>) table -> table.list(table.query()
            .include("items", table.related("items").where(eq("colour", "red"))))),
        Arguments.of("conflict", (Function<Table<Shop.Order>, Future<?>>) table -> table.insert(List.of(
            new Shop.Order(6L, "carl", "OPEN", 1L, DAY)), Conflict.ignore().on("colour"))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unknownFields")
  @DisplayName("Refuses a field that the model does not have, before anything runs")
  void refuseField(String place, Function<Table<Shop.Order>, Future<?>> run) {
    assertThat(failure(run.apply(orders))).isInstanceOf(IllegalArgumentException.class)
        .hasMessageEndingWith("has no field colour.");
    assertThat(invocations).isEmpty();
  }

  @Test
  @DisplayName("Refuses a statement of another table, before it runs")
  void refuseTable() {
    assertThat(failure(orders.list(customers.query())))
        .hasMessage("The statement is of the table customers, not of the table orders of the model.");
    assertThat(failure(orders.count(items.query())))
        .hasMessage("The statement is of the table items, not of the table orders of the model.");
    assertThat(failure(orders.execute(customers.update().set("name", "x").all())))
        .hasMessage("The statement is of the table customers, not of the table orders of the model.");
    assertThat(failure(orders.returning(items.delete().all())))
        .hasMessage("The statement is of the table items, not of the table orders of the model.");
    assertThat(invocations).isEmpty();
  }

  private static RenderedStatement sql(Future<?> run) {
    Throwable failure = failure(run);
    assertThat(failure).isInstanceOf(Kept.class);
    return ((Kept) failure).statement;
  }

  private static Throwable failure(Future<?> run) {
    assertThat(run.isComplete()).as("the run completes without a database").isTrue();
    assertThat(run.failed()).as("the run fails").isTrue();
    return run.cause();
  }
}

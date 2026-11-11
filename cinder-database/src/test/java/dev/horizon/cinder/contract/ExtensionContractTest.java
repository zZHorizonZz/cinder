package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.filter.Filter.eq;
import static dev.horizon.basepoint.filter.Filter.exists;
import static dev.horizon.basepoint.filter.Filter.ne;
import static dev.horizon.basepoint.filter.Filter.startsWith;
import static org.assertj.core.api.Assertions.assertThat;

import dev.horizon.basepoint.expression.Expression;
import dev.horizon.basepoint.expression.Literal;
import dev.horizon.basepoint.statement.Statement;
import dev.horizon.cinder.Context;
import dev.horizon.cinder.Extension;
import dev.horizon.cinder.Interceptor;
import dev.horizon.cinder.Invocation;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.Part;
import dev.horizon.cinder.TypeCodec;
import io.vertx.core.Future;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public abstract class ExtensionContractTest extends ContractTest {

  private final List<String> calls = new CopyOnWriteArrayList<>();

  private static final class Hidden implements Part {

    @Override
    public Statement shape(Statement statement, Context<?> context) {
      return context.kind() == Invocation.Kind.INSERT ? statement : statement.where(ne("state", "CANCELLED"));
    }

    @Override
    public Map<String, Object> write(Map<String, Object> row, Context<?> context) {
      if (context.kind() != Invocation.Kind.UPDATE) {
        return row;
      }
      Map<String, Object> versioned = new LinkedHashMap<>(row);
      versioned.put("version", Expression.add(Expression.column("version"), new Literal(1)));
      return versioned;
    }
  }

  private final class Recorder implements Interceptor {

    @Override
    public <R> Future<R> intercept(Invocation<R> invocation, Chain<R> next) {
      calls.add(invocation.kind() + " " + invocation.model().table());
      return next.proceed(invocation);
    }
  }

  private final class Fixture implements Extension {

    @Override
    public List<TypeCodec> codecs() {
      return List.of(new Reversed());
    }

    @Override
    public List<Part> parts(Model<?> model) {
      return model.table().equals("orders") ? List.of(new Hidden()) : List.of();
    }

    @Override
    public List<Interceptor> interceptors() {
      return List.of(new Recorder());
    }
  }

  @Override
  protected List<Extension> extensions() {
    return List.of(new Fixture());
  }

  @Test
  @DisplayName("Stores the value of a field as its codec writes it, and reads it back")
  void codec() throws TimeoutException {
    await(customers.insert(new Shop.Customer("dora", "dora@example.com", "Dora")));
    await(customers.execute(customers.update().set("name", "Dorothy").where(eq("id", "anne"))));

    assertThat(await(pool.query("SELECT name FROM customers WHERE id IN ('anne', 'dora') ORDER BY id").execute()))
        .extracting(row -> row.getString(0)).containsExactly("yhtoroD", "aroD");
    assertThat(await(customers.find("dora"))).map(Shop.Customer::name).contains("Dora");
    assertThat(await(customers.list(customers.query().where(eq("name", "Dorothy"))))).extracting(Shop.Customer::id)
        .containsExactly("anne");
  }

  @Test
  @DisplayName("Shapes every query, update and delete of a model")
  void shape() throws TimeoutException {
    assertThat(await(orders.count(orders.query()))).isEqualTo(4L);
    assertThat(await(orders.find(4L))).isEmpty();
    assertThat(await(orders.rows(orders.query().columns("id"))).size()).isEqualTo(4);
    assertThat(await(orders.execute(orders.delete().all()))).isEqualTo(4L);
    assertThat(await(pool.query("SELECT id FROM orders").execute()).size()).isEqualTo(1);
  }

  @Test
  @DisplayName("Writes the fields of every update of a model")
  void write() throws TimeoutException {
    await(orders.execute(orders.update().set("state", "PAID").where(eq("id", 1L))));

    assertThat(await(orders.find(1L))).map(order -> order.state() + "/" + order.version()).contains("PAID/1");
  }

  @Test
  @DisplayName("Intercepts every statement, with its kind and its model")
  void intercept() throws TimeoutException {
    calls.clear();

    await(orders.count(orders.query()));
    await(customers.find("anne"));
    await(customers.execute(customers.delete().where(eq("id", "dora"))));

    assertThat(calls).containsExactly("QUERY orders", "QUERY customers", "DELETE customers");
  }

  @Test
  @DisplayName("Sends the values of a statement inside a statement through the codecs of its table")
  void innerCodec() throws TimeoutException {
    await(customers.insert(new Shop.Customer("dora", "dora@example.com", "Dora")));
    await(orders.insert(new Shop.Order(6L, "dora", "OPEN", 1L, day(5))));

    assertThat(ids(orders.query().where(exists(customers.query().on("id", "customer_id").where(eq("name",
        "Dora")))))).containsExactly(6L);
  }

  @Test
  @DisplayName("Refuses a pattern of a field whose codecs change its text")
  void refusePattern() throws TimeoutException {
    assertThat(failure(customers.list(customers.query().where(startsWith("name", "An")))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The field name has codecs that change its text,"
            + " so a pattern does not match what its column holds.");
  }

  @Test
  @DisplayName("Runs a part of one table, after the parts of the model")
  void tablePart() throws TimeoutException {
    Part mugs = new Part() {

      @Override
      public Statement shape(Statement statement, Context<?> context) {
        return statement.where(eq("product", "mug"));
      }
    };

    assertThat(await(items.with(mugs).count(items.query()))).isEqualTo(2L);
    assertThat(await(items.count(items.query()))).isEqualTo(4L);
  }

  @Test
  @DisplayName("Gives the parts of an insert of several entities the transaction that the insert runs in")
  void partTransaction() throws TimeoutException {
    List<Boolean> inTransaction = new CopyOnWriteArrayList<>();
    Part watch = new Part() {

      @Override
      public Map<String, Object> write(Map<String, Object> row, Context<?> context) {
        inTransaction.add(context.transaction().isPresent());
        return row;
      }
    };

    await(database.table(Shop.NOTE).with(watch).insert(List.of(new Shop.Note(null, "One", null), new Shop.Note(
        null, "Two", null))));

    assertThat(inTransaction).containsExactly(true, true);
  }
}

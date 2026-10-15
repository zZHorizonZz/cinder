package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.Filter.eq;
import static org.assertj.core.api.Assertions.assertThat;

import dev.horizon.basepoint.Expression;
import dev.horizon.basepoint.Statement;
import dev.horizon.cinder.Assignments;
import dev.horizon.cinder.Call;
import dev.horizon.cinder.Context;
import dev.horizon.cinder.Execution;
import dev.horizon.cinder.Extension;
import dev.horizon.cinder.Field;
import dev.horizon.cinder.Interceptor;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.Part;
import dev.horizon.cinder.Terminal;
import dev.horizon.cinder.TypeCodec;
import io.vertx.core.Future;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

public abstract class ExtensionContractTest extends ContractTest {

  private final List<String> calls = new CopyOnWriteArrayList<>();

  private static final class Reversed implements TypeCodec {

    @Override
    public boolean applies(Field<?, ?> field) {
      return field.name().equals("name");
    }

    @Override
    public Object write(Field<?, ?> field, Object value) {
      return new StringBuilder((String) value).reverse().toString();
    }

    @Override
    public Object read(Field<?, ?> field, Object stored) {
      return new StringBuilder((String) stored).reverse().toString();
    }
  }

  private static final class Hidden implements Part {

    @Override
    public Statement shape(Statement statement, Context<?> context) {
      return context.kind() == Call.Kind.INSERT ? statement
          : statement.where(dev.horizon.basepoint.Filter.ne("state", "CANCELLED"));
    }

    @Override
    public Assignments write(Assignments row, Context<?> context) {
      return context.kind() == Call.Kind.UPDATE ? row.set("version", new Expression.Plus(Expression.column(
          "version"), new Expression.Literal(1))) : row;
    }
  }

  private final class Recorder implements Interceptor {

    @Override
    public <R> Future<R> intercept(Call<R> call, Chain<R> next) {
      calls.add(call.kind() + " " + call.model().table());
      return next.proceed(call);
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
  void codesTheValuesOfAField() {
    customers.insert(new Shop.Customer("dora", "dora@example.com", "Dora")).await();
    assertThat(customers.findById("dora").await().orElseThrow().name()).isEqualTo("Dora");
    assertThat(customers.find(eq("name", "Dora")).list().await()).extracting(Shop.Customer::id)
        .containsExactly("dora");
  }

  @Test
  void shapesEveryOperationOfAModel() {
    assertThat(orders.count().await()).isEqualTo(4L);
    assertThat(orders.findById(4L).await()).isEmpty();
    assertThat(orders.delete().all().execute().await()).isEqualTo(4L);
  }

  @Test
  void writesTheRowsOfEveryWriteOfAModel() {
    orders.updateById(1L, Assignments.none().set("state", "PAID")).await();
    assertThat(orders.findById(1L).await().orElseThrow().version()).isEqualTo(1);
  }

  @Test
  void interceptsEveryStatement() {
    calls.clear();
    orders.count().await();
    customers.findById("anne").await();
    assertThat(calls).containsExactly("QUERY orders", "QUERY customers");
  }

  @Test
  void runsAPartOfOneOperation() {
    Part mugs = new Part() {
      @Override
      public Statement shape(Statement statement, Context<?> context) {
        return statement.where(eq("product", "mug"));
      }
    };
    assertThat(items.findAll().with(mugs).count().await()).isEqualTo(2L);
  }

  @Test
  void runsATerminalOfItsOwn() {
    Terminal<Object, Future<String>> sql = new Terminal<>() {
      @Override
      public Future<String> run(Execution<?> execution) {
        return execution.run(execution.statement()).map(rows -> execution.model().table() + ":" + rows.size());
      }
    };
    assertThat(orders.find(eq("customer_id", "anne")).run(sql).await()).isEqualTo("orders:2");
  }
}

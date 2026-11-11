package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.filter.Filter.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.streams.ReadStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public abstract class StreamContractTest extends ContractTest {

  @ParameterizedTest(name = "fetch {0}")
  @ValueSource(ints = { 1, 2, 5, 100 })
  @DisplayName("Streams every row of a query, in its order")
  void stream(int fetch) throws TimeoutException {
    assertThat(onContext(() -> collect(orders.stream(orders.query().orderBy("id"), fetch), order -> order.id())))
        .containsExactly(1L, 2L, 3L, 4L, 5L);
  }

  @Test
  @DisplayName("Streams in the transaction of the context")
  void transaction() throws TimeoutException {
    List<Long> streamed = onContext(() -> database.transaction(tx -> orders.insert(new Shop.Order(6L, "carl",
        "OPEN", 1L, day(5))).compose(
            created -> collect(orders.stream(orders.query().where(eq("state", "OPEN"))
                .orderBy("id"), 2), order -> order.id()))));

    assertThat(streamed).containsExactly(1L, 3L, 5L, 6L);
  }

  @Test
  @DisplayName("Streams as many rows as the handler asks for")
  void demand() throws TimeoutException {
    List<Long> streamed = onContext(() -> {
      Promise<List<Long>> done = Promise.promise();
      List<Long> ids = new ArrayList<>();
      ReadStream<Shop.Order> stream = orders.stream(orders.query().orderBy("id"), 2);
      stream.pause();
      stream.handler(order -> {
        ids.add(order.id());
        stream.fetch(1);
      });
      stream.endHandler(ignored -> done.complete(ids));
      stream.exceptionHandler(done::fail);
      stream.fetch(1);
      return done.future();
    });

    assertThat(streamed).containsExactly(1L, 2L, 3L, 4L, 5L);
  }

  @Test
  @DisplayName("Streams the related rows of each row")
  void include() throws TimeoutException {
    List<Integer> sizes = onContext(() -> collect(orders.stream(orders.query().orderBy("id").include("items",
        orders.related("items")), 2), order -> order.items().size()));

    assertThat(sizes).containsExactly(2, 0, 1, 0, 1);
  }

  @Test
  @DisplayName("Refuses a stream of a field that the model does not have, or of no rows at a time")
  void refuse() {
    assertThatThrownBy(() -> orders.stream(orders.query().where(eq("colour", "red"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The model of the table orders has no field colour.");
    assertThatThrownBy(() -> orders.stream(orders.query(), 0)).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A stream fetches at least one row at a time, not 0.");
  }

  private <T> Future<List<T>> collect(ReadStream<Shop.Order> stream, Function<Shop.Order, T> value) {
    Promise<List<T>> done = Promise.promise();
    List<T> values = new ArrayList<>();
    stream.handler(order -> values.add(value.apply(order)));
    stream.endHandler(ignored -> done.complete(values));
    stream.exceptionHandler(done::fail);
    return done.future();
  }
}

package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.Filter.eq;
import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.streams.ReadStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public abstract class StreamContractTest extends ContractTest {

  @ParameterizedTest
  @ValueSource(ints = { 1, 2, 5, 100 })
  void streamsEveryRow(int fetch) throws TimeoutException {
    List<Long> streamed = onContext(() -> collect(orders.findAll().orderBy("id").stream(fetch)));
    assertThat(streamed).containsExactly(1L, 2L, 3L, 4L, 5L);
  }

  @Test
  void streamsInATransaction() throws TimeoutException {
    List<Long> streamed = onContext(() -> database.transaction(tx -> collect(orders.find(eq("state", "OPEN"))
        .orderBy("id").stream(2))));
    assertThat(streamed).containsExactly(1L, 3L, 5L);
  }

  @Test
  void streamsAsTheHandlerAsks() throws TimeoutException {
    List<Long> streamed = onContext(() -> {
      Promise<List<Long>> done = Promise.promise();
      List<Long> ids = new ArrayList<>();
      ReadStream<Shop.Order> stream = orders.findAll().orderBy("id").stream(2);
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
  void streamsTheRelatedRows() throws TimeoutException {
    List<Integer> counts = onContext(() -> {
      Promise<List<Integer>> done = Promise.promise();
      List<Integer> sizes = new ArrayList<>();
      ReadStream<Shop.Order> stream = orders.findAll().orderBy("id").include("items").stream(2);
      stream.handler(order -> sizes.add(order.items().size()));
      stream.endHandler(ignored -> done.complete(sizes));
      stream.exceptionHandler(done::fail);
      return done.future();
    });
    assertThat(counts).containsExactly(2, 0, 1, 0, 1);
  }

  private Future<List<Long>> collect(ReadStream<Shop.Order> stream) {
    Promise<List<Long>> done = Promise.promise();
    List<Long> ids = new ArrayList<>();
    stream.handler(order -> ids.add(order.id()));
    stream.endHandler(ignored -> done.complete(ids));
    stream.exceptionHandler(done::fail);
    return done.future();
  }
}

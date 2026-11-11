package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.filter.Filter.and;
import static dev.horizon.basepoint.filter.Filter.eq;
import static org.assertj.core.api.Assertions.assertThat;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.cinder.Backend;
import dev.horizon.cinder.Database;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public abstract class BackendContractTest extends ContractTest {

  private Orders backend;
  private Customers people;

  interface Counting<E, K> extends Backend<E, K> {

    default Future<Long> countWhere(String field, Object value) {
      return count(query().where(eq(field, value)));
    }
  }

  record Orders(Database database) implements Counting<Shop.Order, Long> {

    @Override
    public Shop.Order prototype() {
      return Shop.ORDER;
    }

    Future<Boolean> cancel(long id, Instant now) {
      return execute(update()
          .set("state", "CANCELLED")
          .set("cancel_time", now)
          .increment("version", 1)
          .where(and(eq("id", id), eq("state", "OPEN"))))
          .map(updated -> updated > 0);
    }
  }

  record Customers(Database database) implements Backend<Shop.Customer, String> {

    @Override
    public Shop.Customer prototype() {
      return Shop.CUSTOMER;
    }
  }

  @BeforeEach
  void backends() {
    backend = new Orders(database);
    people = new Customers(database);
  }

  @Test
  @DisplayName("Finds the rows of keys of each type")
  void findById() throws TimeoutException {
    assertThat(await(backend.findById(4L))).map(Shop.Order::state).contains("CANCELLED");
    assertThat(await(backend.findById(9L))).isEmpty();
    assertThat(await(people.findById("beth"))).map(Shop.Customer::email).contains("beth@example.com");
  }

  @Test
  @DisplayName("Finds the rows of several keys, and none of no keys")
  void findAllById() throws TimeoutException {
    assertThat(ids(await(backend.findAllById(List.of(1L, 5L, 9L))))).containsExactlyInAnyOrder(1L, 5L);
    assertThat(await(backend.findAllById(List.of()))).isEmpty();
  }

  @Test
  @DisplayName("Tells whether a row has a key")
  void existsById() throws TimeoutException {
    assertThat(await(backend.existsById(3L))).isTrue();
    assertThat(await(backend.existsById(9L))).isFalse();
  }

  @Test
  @DisplayName("Lists the pages of a request as the List method of a resource")
  void list() throws TimeoutException {
    Page<Shop.Order> first = await(backend.list(new PageRequest("state = \"OPEN\"", "total_cents desc", 2, "")));
    Page<Shop.Order> second = await(backend.list(new PageRequest("state = \"OPEN\"", "total_cents desc", 2,
        first.nextPageToken())));

    assertThat(ids(first.items())).containsExactly(3L, 5L);
    assertThat(ids(second.items())).containsExactly(1L);
    assertThat(second.nextPageToken()).isEmpty();
  }

  @Test
  @DisplayName("Reads the query of the filter and the order of a request")
  void request() throws TimeoutException {
    assertThat(ids(await(backend.list(backend.query("customer_id = \"anne\"", "id desc"))))).containsExactly(2L, 1L);
    assertThat(await(backend.count())).isEqualTo(5L);
  }

  @Test
  @DisplayName("Inserts, upserts and updates the fields of an update mask")
  void write() throws TimeoutException {
    assertThat(await(backend.insert(new Shop.Order(6L, "carl", "OPEN", 1200L, day(5)))).id()).isEqualTo(6L);
    assertThat(ids(await(backend.insertAll(List.of(new Shop.Order(7L, "carl", "OPEN", 1L, day(5)))))))
        .containsExactly(7L);
    assertThat(await(backend.upsert(new Shop.Order(7L, "anne", "PAID", 2L, day(6)))).customerId()).isEqualTo("anne");
    assertThat(await(backend.update(new Shop.Order(6L, "dora", "SHIPPED", 1L, day(9)), "state")))
        .map(order -> order.customerId() + "/" + order.state()).contains("carl/SHIPPED");
  }

  @Test
  @DisplayName("Updates and deletes the row of a key, and tells whether a row had it")
  void writeById() throws TimeoutException {
    assertThat(await(backend.updateById(1L, update -> update.set("state", "PAID")))).isTrue();
    assertThat(await(backend.updateById(9L, update -> update.set("state", "PAID")))).isFalse();
    assertThat(await(backend.findById(1L))).map(Shop.Order::state).contains("PAID");
    assertThat(await(backend.deleteById(1L))).isTrue();
    assertThat(await(backend.deleteById(1L))).isFalse();
  }

  @Test
  @DisplayName("Runs the operations of its own domain")
  void domain() throws TimeoutException {
    assertThat(await(backend.cancel(1L, day(9)))).isTrue();
    assertThat(await(backend.cancel(1L, day(10)))).isFalse();

    assertThat(await(backend.findById(1L))).map(Shop.Order::cancelTime).contains(day(9));
  }

  @Test
  @DisplayName("Runs the operations of an interface that it implements")
  void extended() throws TimeoutException {
    assertThat(await(backend.countWhere("customer_id", "beth"))).isEqualTo(2L);
  }

  @Test
  @DisplayName("Refuses an update by key that sets nothing, or a field that the model does not have")
  void refuseById() throws TimeoutException {
    assertThat(failure(backend.updateById(1L, update -> update.set("colour", "red"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The model of the table orders has no field colour.");
    assertThat(failure(backend.updateById(1L, update -> update))).isInstanceOf(IllegalStateException.class)
        .hasMessage("The update sets no columns. Call set(), increment() or unset().");
  }
}

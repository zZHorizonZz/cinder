package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.filter.Filter.and;
import static dev.horizon.basepoint.filter.Filter.eq;
import static dev.horizon.basepoint.filter.Filter.lt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.horizon.basepoint.UnfilteredWriteException;
import dev.horizon.cinder.Conflict;
import dev.horizon.cinder.DuplicateKeyException;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public abstract class WriteContractTest extends ContractTest {

  @Test
  @DisplayName("Inserts an entity and gives it as stored")
  void insert() throws TimeoutException {
    Shop.Order created = await(orders.insert(new Shop.Order(6L, "carl", "OPEN", 1200L, day(5))));

    assertThat(created).isEqualTo(new Shop.Order(6L, "carl", "OPEN", 1200L, 0, day(5), null, null, List.of()));
    assertThat(await(orders.find(6L))).contains(created);
  }

  @Test
  @DisplayName("Inserts entities in their order")
  void insertMany() throws TimeoutException {
    List<Shop.Order> created = await(orders.insert(List.of(new Shop.Order(7L, "anne", "OPEN", 100L, day(6)),
        new Shop.Order(6L, "beth", "OPEN", 200L, day(5)))));

    assertThat(ids(created)).containsExactly(7L, 6L);
    assertThat(await(orders.count(orders.query()))).isEqualTo(7L);
  }

  @Test
  @DisplayName("Inserts no entities without a statement")
  void insertNone() throws TimeoutException {
    assertThat(await(orders.insert(List.of()))).isEmpty();
    assertThat(await(orders.execute(List.of(), null))).isZero();
  }

  @Test
  @DisplayName("Refuses a row whose key or unique field another row has")
  void duplicate() throws TimeoutException {
    assertThat(failure(orders.insert(new Shop.Order(1L, "carl", "OPEN", 1L, day(5)))))
        .isInstanceOfSatisfying(DuplicateKeyException.class, failed -> assertThat(failed.model().table())
            .isEqualTo("orders"));
    assertThat(failure(customers.insert(new Shop.Customer("dora", "anne@example.com", "Dora"))))
        .isInstanceOfSatisfying(DuplicateKeyException.class, failed -> assertThat(failed.model().table())
            .isEqualTo("customers"));
    assertThat(await(customers.find("dora"))).isEmpty();
  }

  @Test
  @DisplayName("Skips the rows whose key exists and inserts the others")
  void ignore() throws TimeoutException {
    List<Shop.Order> added = await(orders.insert(List.of(new Shop.Order(1L, "carl", "OPEN", 1L, day(5)),
        new Shop.Order(6L, "carl", "OPEN", 2L, day(5))), Conflict.ignore()));

    assertThat(ids(added)).contains(6L);
    assertThat(added).filteredOn(order -> order.id() == 1L).allSatisfy(kept -> assertThat(kept.customerId())
        .isEqualTo("anne"));
    assertThat(await(orders.find(1L))).map(Shop.Order::customerId).contains("anne");
    assertThat(await(orders.find(6L))).map(Shop.Order::customerId).contains("carl");
  }

  @Test
  @DisplayName("Counts the rows of an insert without reading them back")
  void insertCount() throws TimeoutException {
    assertThat(await(orders.execute(List.of(new Shop.Order(1L, "carl", "OPEN", 1L, day(5)), new Shop.Order(6L,
        "carl", "OPEN", 2L, day(5))), Conflict.ignore()))).isEqualTo(1L);
    assertThat(failure(orders.execute(List.of(new Shop.Order(1L, "carl", "OPEN", 1L, day(5))), null)))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(await(orders.count(orders.query()))).isEqualTo(6L);
  }

  @Test
  @DisplayName("Updates every field of the rows whose key exists")
  void merge() throws TimeoutException {
    List<Shop.Order> saved = await(orders.insert(List.of(new Shop.Order(1L, "carl", "PAID", 99L, day(5))),
        Conflict.merge()));

    assertThat(saved).containsExactly(new Shop.Order(1L, "carl", "PAID", 99L, 0, day(5), null, null, List.of()));
  }

  @Test
  @DisplayName("Updates only the merged fields of the row whose unique field exists")
  void mergeFields() throws TimeoutException {
    await(customers.insert(List.of(new Shop.Customer("dora", "anne@example.com", "Anne Smith")), Conflict.merge(
        "name").on("email")));

    assertThat(await(customers.find("anne"))).contains(new Shop.Customer("anne", "anne@example.com", "Anne Smith"));
    assertThat(await(customers.find("dora"))).isEmpty();
  }

  @Test
  @DisplayName("Refuses a conflict of a field that the model does not have")
  void refuseConflict() throws TimeoutException {
    assertThat(failure(customers.insert(List.of(new Shop.Customer("dora", "dora@example.com", "Dora")),
        Conflict.merge("nickname").on("email")))).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The model of the table customers has no field nickname.");
    assertThat(await(customers.find("dora"))).isEmpty();
  }

  @Test
  @DisplayName("Updates the rows of a filter")
  void update() throws TimeoutException {
    long updated = await(orders.execute(orders.update().set("state", "SHIPPED").increment("version", 1)
        .where(eq("state", "OPEN"))));

    assertThat(updated).isEqualTo(3L);
    assertThat(await(orders.find(3L))).map(order -> order.state() + "/" + order.version()).contains("SHIPPED/1");
    assertThat(await(orders.find(2L))).map(order -> order.state() + "/" + order.version()).contains("PAID/1");
  }

  @Test
  @DisplayName("Updates the rows of a filter text")
  void updateText() throws TimeoutException {
    assertThat(await(orders.execute(orders.update().set("state", "SHIPPED").where("total_cents > 20000"))))
        .isEqualTo(2L);
    assertThat(ids(orders.query().where(eq("state", "SHIPPED")))).containsExactly(3L, 5L);
  }

  @Test
  @DisplayName("Updates every row with all()")
  void updateAll() throws TimeoutException {
    assertThat(await(orders.execute(orders.update().set("state", "ARCHIVED").all()))).isEqualTo(5L);
    assertThat(await(orders.count(orders.query().where(eq("state", "ARCHIVED"))))).isEqualTo(5L);
  }

  @Test
  @DisplayName("Gives the rows that an update changed, as stored")
  void returnUpdated() throws TimeoutException {
    List<Shop.Order> updated = await(orders.returning(orders.update().set("cancel_time", day(9)).set("state",
        "CLOSED").where(lt("total_cents", 10000L))));

    assertThat(updated).extracting(Shop.Order::id, Shop.Order::state, Shop.Order::cancelTime)
        .containsExactlyInAnyOrder(tuple(2L, "CLOSED", day(9)), tuple(4L, "CLOSED", day(9)));
  }

  @Test
  @DisplayName("Cancels an open order once")
  void cancel() throws TimeoutException {
    assertThat(await(cancel(1L, day(9)))).isTrue();
    assertThat(await(cancel(1L, day(10)))).isFalse();

    assertThat(await(orders.find(1L))).contains(new Shop.Order(1L, "anne", "CANCELLED", 15000L, 1, day(0), day(9),
        null, List.of()));
  }

  @Test
  @DisplayName("Sets an unset field to null")
  void unset() throws TimeoutException {
    await(orders.execute(orders.update().unset("cancel_time").where(eq("id", 4L))));

    assertThat(await(orders.find(4L)).orElseThrow().cancelTime()).isNull();
  }

  @Test
  @DisplayName("Refuses to unset a field whose column cannot be null")
  void refuseUnset() throws TimeoutException {
    assertThat(failure(orders.execute(orders.update().unset("state").where(eq("id", 4L)))))
        .hasMessageContaining("state");
    assertThat(await(orders.find(4L))).map(Shop.Order::state).contains("CANCELLED");
  }

  @Test
  @DisplayName("Writes null to a binary column, and an empty binary value as null")
  void binaryNull() throws TimeoutException {
    await(files.insert(List.of(new Shop.File(1L, null), new Shop.File(2L, new byte[] { 1, 2 }),
        new Shop.File(3L, new byte[] { 3 }), new Shop.File(4L, new byte[0]), new Shop.File(5L, new byte[] { 5 }))));
    await(files.execute(files.update().set("content", null).where(eq("id", 2L))));
    await(files.execute(files.update().unset("content").where(eq("id", 3L))));
    await(files.execute(files.update().set("content", new byte[0]).where(eq("id", 5L))));

    assertThat(await(files.list(files.query().orderBy("id")))).extracting(Shop.File::content).containsOnlyNulls()
        .hasSize(5);
  }

  @Test
  @DisplayName("Updates the fields of an update mask in the row of a key")
  void updateMask() throws TimeoutException {
    Shop.Order patched = await(orders.update(new Shop.Order(2L, "dora", "SHIPPED", 1L, day(9)), "state,total_cents"))
        .orElseThrow();

    assertThat(patched).isEqualTo(new Shop.Order(2L, "anne", "SHIPPED", 1L, 1, day(1), null, null, List.of()));
    assertThat(await(orders.update(new Shop.Order(9L, "dora", "SHIPPED", 1L, day(9)), "state"))).isEmpty();
  }

  @Test
  @DisplayName("Refuses an update mask of a key field or of a field that the model does not have")
  void refuseMask() throws TimeoutException {
    assertThat(failure(orders.update(new Shop.Order(2L, "dora", "SHIPPED", 1L, day(9)), "id")))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("An update cannot change the key field id.");
    assertThat(failure(orders.update(new Shop.Order(2L, "dora", "SHIPPED", 1L, day(9)), "colour")))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The model of the table orders has no field colour.");
  }

  @Test
  @DisplayName("Refuses an update or a delete without a filter")
  void refuseUnfiltered() throws TimeoutException {
    assertThat(failure(orders.execute(orders.update().set("state", "LOST"))))
        .isInstanceOf(UnfilteredWriteException.class).hasMessageStartingWith("The update has no filter.");
    assertThat(failure(orders.execute(orders.delete()))).isInstanceOf(UnfilteredWriteException.class)
        .hasMessageStartingWith("The delete has no filter.");
    assertThat(await(orders.count(orders.query()))).isEqualTo(5L);
  }

  @Test
  @DisplayName("Refuses a write of another table or of a field that the model does not have")
  void refuseWrite() throws TimeoutException {
    assertThat(failure(orders.execute(customers.update().set("name", "x").all())))
        .hasMessage("The statement is of the table customers, not of the table orders of the model.");
    assertThat(failure(orders.execute(customers.delete().all())))
        .hasMessage("The statement is of the table customers, not of the table orders of the model.");
    assertThat(failure(orders.execute(orders.update().set("colour", "red").all())))
        .hasMessage("The model of the table orders has no field colour.");
    assertThat(await(customers.count(customers.query()))).isEqualTo(3L);
  }

  @Test
  @DisplayName("Deletes the rows of a filter and gives them")
  void delete() throws TimeoutException {
    List<Shop.Order> deleted = await(orders.returning(orders.delete().where(eq("customer_id", "anne"))));

    assertThat(ids(deleted)).containsExactlyInAnyOrder(1L, 2L);
    assertThat(await(orders.execute(orders.delete().where(eq("id", 3L))))).isEqualTo(1L);
    assertThat(await(orders.execute(orders.delete().where(eq("id", 3L))))).isZero();
    assertThat(await(orders.execute(orders.delete().all()))).isEqualTo(2L);
  }

  private Future<Boolean> cancel(long id, Instant now) {
    return orders.execute(orders.update()
        .set("state", "CANCELLED")
        .set("cancel_time", now)
        .increment("version", 1)
        .where(and(eq("id", id), eq("state", "OPEN"))))
        .map(updated -> updated > 0);
  }
}

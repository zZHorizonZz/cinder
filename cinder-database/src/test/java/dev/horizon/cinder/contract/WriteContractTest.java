package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.Filter.eq;
import static dev.horizon.basepoint.Filter.lt;
import static org.assertj.core.api.Assertions.assertThat;

import dev.horizon.cinder.Assignments;
import dev.horizon.cinder.Conflict;
import dev.horizon.cinder.DuplicateKeyException;
import java.util.List;
import org.junit.jupiter.api.Test;

public abstract class WriteContractTest extends ContractTest {

  @Test
  void writesANullToABinaryColumn() {
    files.insertAll(List.of(new Shop.File(1L, null), new Shop.File(2L, new byte[] { 1, 2 }),
        new Shop.File(3L, new byte[] { 3 }))).await();
    files.updateById(2L, Assignments.none().set("content", (Object) null)).await();
    files.updateById(3L, Assignments.none().unset("content")).await();
    assertThat(files.findAll().orderBy("id").list().await()).extracting(Shop.File::content).containsOnlyNulls()
        .hasSize(3);
  }

  @Test
  void insertsAnEntityAndGivesItAsStored() {
    Shop.Order created = orders.insert(new Shop.Order(6L, "carl", "OPEN", 1200L, day(5))).await();
    assertThat(created).isEqualTo(new Shop.Order(6L, "carl", "OPEN", 1200L, 0, day(5), null, null, List.of()));
    assertThat(orders.findById(6L).await()).contains(created);
  }

  @Test
  void insertsEntitiesInOrder() {
    List<Shop.Order> created = orders.insertAll(List.of(new Shop.Order(7L, "anne", "OPEN", 100L, day(6)),
        new Shop.Order(6L, "beth", "OPEN", 200L, day(5)))).await();
    assertThat(ids(created)).containsExactly(7L, 6L);
    assertThat(orders.count().await()).isEqualTo(7L);
  }

  @Test
  void refusesARowWhoseKeyAnotherRowHas() {
    assertThat(failure(orders.insert(new Shop.Order(1L, "carl", "OPEN", 1L, day(5)))))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(failure(customers.insert(new Shop.Customer("dora", "anne@example.com", "Dora"))))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void leavesOutTheRowsThatConflict() {
    long added = orders.insert().values(List.of(new Shop.Order(1L, "carl", "OPEN", 1L, day(5)),
        new Shop.Order(6L, "carl", "OPEN", 2L, day(5)))).onConflict(Conflict.ignore()).execute().await();
    assertThat(added).isEqualTo(1L);
    assertThat(orders.findById(1L).await().orElseThrow().customerId()).isEqualTo("anne");
    assertThat(orders.findById(6L).await()).isPresent();
  }

  @Test
  void mergesTheRowsThatConflict() {
    Shop.Order saved = orders.upsert(new Shop.Order(1L, "carl", "PAID", 99L, day(5))).await();
    assertThat(saved).isEqualTo(new Shop.Order(1L, "carl", "PAID", 99L, 0, day(5), null, null, List.of()));
    customers.insert().values(new Shop.Customer("dora", "anne@example.com", "Anne Smith"))
        .onConflict(Conflict.merge("name").on("email")).execute().await();
    assertThat(customers.findById("anne").await().orElseThrow().name()).isEqualTo("Anne Smith");
    assertThat(customers.findById("dora").await()).isEmpty();
  }

  @Test
  void updatesTheRowsOfAFilter() {
    long updated = orders.update().where(eq("state", "OPEN"))
        .set(Assignments.none().set("state", "SHIPPED").increment("version", 1)).execute().await();
    assertThat(updated).isEqualTo(3L);
    Shop.Order order = orders.findById(3L).await().orElseThrow();
    assertThat(order.state()).isEqualTo("SHIPPED");
    assertThat(order.version()).isEqualTo(1);
  }

  @Test
  void givesTheRowsThatAnUpdateChanged() {
    List<Shop.Order> updated = orders.update().where(lt("total_cents", 10000L))
        .set(Assignments.none().set("cancel_time", day(9)).set("state", "CLOSED")).returning().await();
    assertThat(updated).extracting(Shop.Order::id).containsExactlyInAnyOrder(2L, 4L);
    assertThat(updated).allSatisfy(order -> {
      assertThat(order.state()).isEqualTo("CLOSED");
      assertThat(order.cancelTime()).isEqualTo(day(9));
    });
  }

  @Test
  void unsetsAField() {
    orders.updateById(4L, Assignments.none().unset("cancel_time")).await();
    assertThat(orders.findById(4L).await().orElseThrow().cancelTime()).isNull();
  }

  @Test
  void updatesTheFieldsOfAMask() {
    Shop.Order patched = orders.update(new Shop.Order(2L, "dora", "SHIPPED", 1L, day(9)), "state,total_cents").await()
        .orElseThrow();
    assertThat(patched.state()).isEqualTo("SHIPPED");
    assertThat(patched.totalCents()).isEqualTo(1L);
    assertThat(patched.customerId()).isEqualTo("anne");
    assertThat(orders.update(new Shop.Order(9L, "dora", "SHIPPED", 1L, day(9)), "state").await()).isEmpty();
  }

  @Test
  void refusesAnUpdateOrADeleteWithoutAFilter() {
    assertThat(failure(orders.update().set(Assignments.none().set("state", "LOST")).execute()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(failure(orders.delete().execute())).isInstanceOf(IllegalStateException.class);
    assertThat(orders.count().await()).isEqualTo(5L);
  }

  @Test
  void updatesEveryRowWithAll() {
    assertThat(orders.update().all().set(Assignments.none().set("state", "ARCHIVED")).execute().await())
        .isEqualTo(5L);
  }

  @Test
  void deletesRowsAndGivesThem() {
    List<Shop.Order> deleted = orders.delete().where(eq("customer_id", "anne")).returning().await();
    assertThat(deleted).extracting(Shop.Order::id).containsExactlyInAnyOrder(1L, 2L);
    assertThat(orders.deleteById(3L).await()).isTrue();
    assertThat(orders.deleteById(3L).await()).isFalse();
    assertThat(orders.delete().all().execute().await()).isEqualTo(2L);
  }
}

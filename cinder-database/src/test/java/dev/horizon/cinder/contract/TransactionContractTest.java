package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.filter.Filter.eq;
import static dev.horizon.basepoint.filter.Filter.in;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.horizon.basepoint.LockMode;
import dev.horizon.cinder.Transaction;
import dev.horizon.cinder.TransactionOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public abstract class TransactionContractTest extends ContractTest {

  @Test
  @DisplayName("Commits when the work succeeds")
  void commit() throws TimeoutException {
    onContext(() -> database.transaction(tx -> orders.insert(order(6L))
        .compose(created -> orders.execute(orders.update().set("state", "PAID").where(eq("id", 1L))))));

    assertThat(await(orders.find(6L))).isPresent();
    assertThat(await(orders.find(1L))).map(Shop.Order::state).contains("PAID");
  }

  @Test
  @DisplayName("Rolls back when the work fails, and gives its failure")
  void rollback() throws TimeoutException {
    Throwable failure = failureOnContext(() -> database.transaction(tx -> orders.insert(order(6L))
        .compose(created -> Future.failedFuture(new IllegalStateException("refused")))));

    assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage("refused");
    assertThat(await(orders.find(6L))).isEmpty();
  }

  @Test
  @DisplayName("Rolls back when the work asks for it, and gives its result")
  void rollbackOnly() throws TimeoutException {
    Long created = onContext(() -> database.transaction(tx -> {
      tx.rollbackOnly();
      return orders.insert(order(6L)).map(Shop.Order::id);
    }));

    assertThat(created).isEqualTo(6L);
    assertThat(await(orders.find(6L))).isEmpty();
  }

  @Test
  @DisplayName("Rolls back only the savepoint of failed inner work")
  void savepoint() throws TimeoutException {
    onContext(() -> database.transaction(tx -> orders.insert(order(6L))
        .compose(created -> database.transaction(inner -> orders.insert(order(7L))
            .compose(again -> orders.insert(order(1L))))
            .<Object> map(done -> done).recover(failed -> Future.succeededFuture()))));

    assertThat(await(orders.find(6L))).isPresent();
    assertThat(await(orders.find(7L))).isEmpty();
  }

  @Test
  @DisplayName("Runs the actions of a commit after it, those of savepoints too")
  void commitActions() throws TimeoutException {
    List<String> events = new CopyOnWriteArrayList<>();

    onContext(() -> database.transaction(tx -> {
      tx.onCommit(() -> events.add("committed"));
      tx.onRollback(() -> events.add("rolled back"));
      return database.transaction(inner -> {
        inner.onCommit(() -> events.add("inner committed"));
        return orders.insert(order(6L));
      });
    }));
    await(vertx.timer(100));

    assertThat(events).containsExactly("committed", "inner committed");
  }

  @Test
  @DisplayName("Runs the actions of a rollback after it")
  void rollbackActions() throws TimeoutException {
    List<String> events = new CopyOnWriteArrayList<>();

    onContext(() -> database.transaction(tx -> {
      tx.onCommit(() -> events.add("committed"));
      tx.onRollback(() -> events.add("rolled back"));
      tx.rollbackOnly();
      return Future.succeededFuture();
    }));
    await(vertx.timer(100));

    assertThat(events).containsExactly("rolled back");
  }

  @Test
  @DisplayName("Runs the statements of an action outside the transaction")
  void actionStatements() throws TimeoutException {
    Promise<Void> inserted = Promise.promise();

    onContext(() -> database.transaction(tx -> {
      tx.onCommit(() -> vertx.setTimer(100, id -> database.transaction(after -> orders.insert(order(6L)))
          .<Void> mapEmpty().onComplete(inserted)));
      return orders.count(orders.query());
    }));
    onContext(() -> database.transaction(tx -> {
      tx.rollbackOnly();
      return vertx.timer(300);
    }));
    await(inserted.future());

    assertThat(await(orders.find(6L))).isPresent();
  }

  @Test
  @DisplayName("Refuses actions once the transaction ends")
  void refuseEnded() throws TimeoutException {
    List<Transaction> ended = new CopyOnWriteArrayList<>();
    onContext(() -> database.transaction(tx -> {
      ended.add(tx);
      return Future.succeededFuture();
    }));
    Transaction tx = ended.getFirst();

    assertThatThrownBy(() -> tx.onCommit(() -> {
    })).isInstanceOf(IllegalStateException.class).hasMessage("The transaction has ended.");
    assertThatThrownBy(() -> tx.onRollback(() -> {
    })).isInstanceOf(IllegalStateException.class).hasMessage("The transaction has ended.");
    assertThatThrownBy(tx::rollbackOnly).isInstanceOf(IllegalStateException.class)
        .hasMessage("The transaction has ended.");
  }

  @Test
  @DisplayName("Runs with an isolation level and a read-only mode")
  void options() throws TimeoutException {
    Long serializable = onContext(() -> database.transaction(TransactionOptions.defaults()
        .withIsolation(TransactionOptions.Isolation.SERIALIZABLE), tx -> orders.count(orders.query())));
    Long readOnly = onContext(() -> database.transaction(TransactionOptions.defaults().withReadOnly(true),
        tx -> orders.count(orders.query())));

    assertThat(serializable).isEqualTo(5L);
    assertThat(readOnly).isEqualTo(5L);
  }

  @Test
  @DisplayName("Locks the rows that it reads, so that another transaction skips them")
  void lock() throws TimeoutException {
    assumeTrue(testDatabase().locksRows());
    Promise<Void> locked = Promise.promise();
    Promise<Void> release = Promise.promise();
    Promise<Object> held = Promise.promise();
    vertx.getOrCreateContext().runOnContext(ignored -> database.transaction(tx -> orders.single(orders.query()
        .where(eq("id", 1L)).lock(LockMode.UPDATE)).compose(order -> {
          locked.complete();
          return release.future();
        })).onComplete(held));
    await(locked.future());

    List<Long> free = onContext(() -> database.transaction(tx -> orders.list(orders.query().where(in("id", List.of(
        1L, 2L))).orderBy("id").lock(LockMode.UPDATE_SKIP_LOCKED)).map(this::ids)));
    release.complete();
    await(held.future());

    assertThat(free).containsExactly(2L);
  }

  @Test
  @DisplayName("Runs off every Vert.x context, on that of the pool")
  void offContext() throws TimeoutException {
    Long count = await(database.transaction(tx -> orders.insert(order(6L)).compose(created -> orders.count(orders
        .query()))));

    assertThat(count).isEqualTo(6L);
    assertThat(await(orders.find(6L))).isPresent();
  }

  private static Shop.Order order(long id) {
    return new Shop.Order(id, "carl", "OPEN", 1L, day(5));
  }
}

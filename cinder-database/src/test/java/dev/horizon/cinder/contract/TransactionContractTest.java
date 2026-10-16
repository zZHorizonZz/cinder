package dev.horizon.cinder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.horizon.basepoint.LockMode;
import dev.horizon.cinder.Assignments;
import dev.horizon.cinder.Transaction;
import dev.horizon.cinder.TransactionOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

public abstract class TransactionContractTest extends ContractTest {

  @Test
  void commitsWhenTheWorkSucceeds() throws TimeoutException {
    onContext(() -> database.transaction(tx -> orders.insert(new Shop.Order(6L, "carl", "OPEN", 1L, day(5)))
        .compose(created -> orders.updateById(1L, Assignments.none().set("state", "PAID")))));
    assertThat(orders.existsById(6L).await(TIMEOUT)).isTrue();
    assertThat(orders.findById(1L).await(TIMEOUT).orElseThrow().state()).isEqualTo("PAID");
  }

  @Test
  void rollsBackWhenTheWorkFails() throws TimeoutException {
    Throwable failure = failureOnContext(() -> database.transaction(tx -> orders.insert(new Shop.Order(6L, "carl",
        "OPEN", 1L, day(5))).compose(created -> Future.failedFuture(new IllegalStateException("refused")))));
    assertThat(failure).hasMessageContaining("refused");
    assertThat(orders.existsById(6L).await(TIMEOUT)).isFalse();
  }

  @Test
  void rollsBackWhenTheWorkAsksFor() throws TimeoutException {
    Long created = onContext(() -> database.transaction(tx -> {
      tx.rollbackOnly();
      return orders.insert(new Shop.Order(6L, "carl", "OPEN", 1L, day(5))).map(Shop.Order::id);
    }));
    assertThat(created).isEqualTo(6L);
    assertThat(orders.existsById(6L).await(TIMEOUT)).isFalse();
  }

  @Test
  void rollsBackOnlyTheSavepointOfAFailedInnerWork() throws TimeoutException {
    onContext(() -> database.transaction(tx -> orders.insert(new Shop.Order(6L, "carl", "OPEN", 1L, day(5)))
        .compose(created -> database.transaction(inner -> orders.insert(new Shop.Order(7L, "carl", "OPEN", 1L,
            day(5))).compose(again -> orders.insert(new Shop.Order(1L, "carl", "OPEN", 1L, day(5)))))
            .<Object> map(done -> done).recover(failed -> Future.succeededFuture()))));
    assertThat(orders.existsById(6L).await(TIMEOUT)).isTrue();
    assertThat(orders.existsById(7L).await(TIMEOUT)).isFalse();
  }

  @Test
  void runsTheActionsOfACommitAfterIt() throws TimeoutException {
    List<String> events = new CopyOnWriteArrayList<>();
    onContext(() -> database.transaction(tx -> {
      tx.onCommit(() -> events.add("committed"));
      tx.onRollback(() -> events.add("rolled back"));
      return database.transaction(inner -> {
        inner.onCommit(() -> events.add("inner committed"));
        return orders.insert(new Shop.Order(6L, "carl", "OPEN", 1L, day(5)));
      });
    }));
    vertx.timer(100).await(TIMEOUT);
    assertThat(events).containsExactly("committed", "inner committed");
  }

  @Test
  void runsTheActionsOfARollback() throws TimeoutException {
    List<String> events = new CopyOnWriteArrayList<>();
    onContext(() -> database.transaction(tx -> {
      tx.onCommit(() -> events.add("committed"));
      tx.onRollback(() -> events.add("rolled back"));
      tx.rollbackOnly();
      return Future.succeededFuture();
    }));
    vertx.timer(100).await(TIMEOUT);
    assertThat(events).containsExactly("rolled back");
  }

  @Test
  void runsTheStatementsOfAnActionOutsideTheTransaction() throws TimeoutException {
    Promise<Void> inserted = Promise.promise();
    onContext(() -> database.transaction(tx -> {
      tx.onCommit(() -> vertx.setTimer(100, id -> database.transaction(after -> orders.insert(new Shop.Order(6L,
          "carl", "OPEN", 1L, day(5)))).<Void> mapEmpty().onComplete(inserted)));
      return orders.count();
    }));
    onContext(() -> database.transaction(tx -> {
      tx.rollbackOnly();
      return vertx.timer(300);
    }));
    inserted.future().await(TIMEOUT);
    assertThat(orders.existsById(6L).await(TIMEOUT)).isTrue();
  }

  @Test
  void refusesActionsOnceItEnds() throws TimeoutException {
    List<Transaction> ended = new CopyOnWriteArrayList<>();
    onContext(() -> database.transaction(tx -> {
      ended.add(tx);
      return Future.succeededFuture();
    }));
    Transaction tx = ended.getFirst();
    assertThatThrownBy(() -> tx.onCommit(() -> {
    })).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(tx::rollbackOnly).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void runsWithOptions() throws TimeoutException {
    Long count = onContext(() -> database.transaction(TransactionOptions.defaults()
        .withIsolation(TransactionOptions.Isolation.SERIALIZABLE), tx -> orders.count()));
    assertThat(count).isEqualTo(5L);
    Long read = onContext(() -> database.transaction(TransactionOptions.defaults().withReadOnly(true),
        tx -> orders.count()));
    assertThat(read).isEqualTo(5L);
  }

  @Test
  void locksTheRowsThatItReads() throws TimeoutException {
    Shop.Order locked = onContext(() -> database.transaction(tx -> orders.findAll().where("id = 1")
        .lock(LockMode.UPDATE).single()));
    assertThat(locked.id()).isEqualTo(1L);
  }

  @Test
  void runsOutsideAContext() throws TimeoutException {
    Long count = database.transaction(tx -> orders.insert(new Shop.Order(6L, "carl", "OPEN", 1L, day(5)))
        .compose(created -> orders.count())).await(TIMEOUT);
    assertThat(count).isEqualTo(6L);
    assertThat(orders.existsById(6L).await(TIMEOUT)).isTrue();
  }
}

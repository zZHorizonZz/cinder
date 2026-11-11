package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.expression.Expression.column;
import static dev.horizon.basepoint.expression.Expression.subquery;
import static dev.horizon.basepoint.expression.Expression.sum;
import static dev.horizon.basepoint.filter.Filter.eq;
import static dev.horizon.basepoint.filter.Filter.exists;
import static dev.horizon.basepoint.filter.Filter.gt;
import static org.assertj.core.api.Assertions.assertThat;

import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.basepoint.Query;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public abstract class InnerContractTest extends ContractTest {

  private Query ordersAfter(int days) {
    return orders.query().on("customer_id", "id").where(gt("create_time", day(days)));
  }

  @Test
  @DisplayName("Maps the fields of an exists of another table to their columns")
  void existsElsewhere() throws TimeoutException {
    assertThat(await(customers.list(customers.query().where(exists(ordersAfter(2))).orderBy("id"))))
        .extracting(Shop.Customer::id).containsExactly("beth", "carl");
  }

  @Test
  @DisplayName("Maps the fields of a relation and of its paths to their columns")
  void relation() throws TimeoutException {
    Query query = customers.query().relation("orders", orders.query().on("customer_id", "id"))
        .where(gt(column("orders", "create_time"), day(3)));

    assertThat(await(customers.list(query))).extracting(Shop.Customer::id).containsExactly("carl");
    assertThat(await(customers.count(query))).isEqualTo(1L);
    assertThat(await(customers.exists(query))).isTrue();
  }

  @Test
  @DisplayName("Counts the rows of a query with its joins, groups and distinct values")
  void count() throws TimeoutException {
    assertThat(await(orders.count(orders.query().columns("customer_id").distinct()))).isEqualTo(3L);
    assertThat(await(orders.count(orders.query().groupBy("customer_id")))).isEqualTo(3L);
  }

  @Test
  @DisplayName("Reads the rows of a WITH query of the table")
  void with() throws TimeoutException {
    Query recent = driver.dialect().collection("recent").query().with("recent", orders.query()
        .where(gt("create_time", day(2))));

    assertThat(idsInOrder(recent.orderBy("id"))).containsExactly(4L, 5L);
  }

  @Test
  @DisplayName("Reads the rows of a union, an intersect and an except of queries of the table")
  void compound() throws TimeoutException {
    Query anne = orders.query().where(eq("customer_id", "anne"));
    Query large = orders.query().where(gt("total_cents", 10000L));

    assertThat(ids(await(orders.list(anne.union(orders.query().where(gt("create_time", day(3))))))))
        .containsExactlyInAnyOrder(1L, 2L, 5L);
    assertThat(ids(await(orders.list(anne.intersect(large))))).containsExactly(1L);
    assertThat(ids(await(orders.list(anne.except(large))))).containsExactly(2L);
    assertThat(await(orders.count(anne.union(large)))).isEqualTo(5L);
  }

  @Test
  @DisplayName("Refuses a page of a union")
  void refusePage() throws TimeoutException {
    assertThat(failure(orders.page(orders.query().union(orders.query()), new PageRequest("", "", 2, ""))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("A page continues one query of the table, not a"
            + " union, an intersect or an except of queries.");
  }

  @Test
  @DisplayName("Maps the fields of a subquery in an update, and of an exists in a delete")
  void writes() throws TimeoutException {
    await(orders.execute(orders.update().set("total_cents", subquery(items.query().on("order_id", "id")
        .select("total", sum("quantity")))).where(eq("id", 1L))));
    long deleted = await(orders.execute(orders.delete().where(exists(items.query().on("order_id", "id")
        .where(gt("quantity", 3))))));

    assertThat(await(orders.find(1L))).map(Shop.Order::totalCents).contains(3L);
    assertThat(deleted).isEqualTo(1L);
    assertThat(await(orders.find(3L))).isEmpty();
  }

  @Test
  @DisplayName("Refuses a statement of a table that no model of the database has")
  void refuseTable() throws TimeoutException {
    assertThat(failure(customers.list(customers.query().where(exists(driver.dialect().collection("payments")
        .query().on("customer_id", "id")))))).isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("No model of the database has the table payments.");
  }
}

package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.expression.Expression.column;
import static dev.horizon.basepoint.expression.Expression.multiply;
import static dev.horizon.basepoint.expression.Expression.subquery;
import static dev.horizon.basepoint.expression.Expression.value;
import static dev.horizon.basepoint.filter.Filter.eq;
import static dev.horizon.basepoint.filter.Filter.gt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.horizon.basepoint.Direction;
import dev.horizon.basepoint.expression.Expression;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public abstract class ComputedContractTest extends ContractTest {

  private Expression orderCount() {
    return subquery(orders.query().on("customer_id", "id").count());
  }

  @Test
  @DisplayName("Reads a value that the query selects into the computed field of its name")
  void computed() throws TimeoutException {
    List<Shop.Customer> found = await(customers.list(customers.query().select("order_count", orderCount())
        .orderBy("id")));

    assertThat(found).extracting(Shop.Customer::id, Shop.Customer::orderCount, Shop.Customer::name)
        .containsExactly(tuple("anne", 2L, "Anne"), tuple("beth", 2L, "Beth"), tuple("carl", 1L, "Carl"));
  }

  @Test
  @DisplayName("Filters and sorts by a computed value")
  void filterAndSort() throws TimeoutException {
    List<Shop.Customer> found = await(customers.list(customers.query().select("order_count", orderCount())
        .where(gt(orderCount(), 1)).orderBy("order_count", Direction.DESC).orderBy("id")));

    assertThat(found).extracting(Shop.Customer::id).containsExactly("anne", "beth");
  }

  @Test
  @DisplayName("Leaves a computed field unset when the query does not select it")
  void unselected() throws TimeoutException {
    assertThat(await(customers.find("anne"))).map(Shop.Customer::orderCount).isEmpty();
  }

  @Test
  @DisplayName("Reads a computed value of the related rows of an include")
  void include() throws TimeoutException {
    Shop.Order order = await(orders.single(orders.query().where(eq("id", 3L)).includeOne("customer", orders
        .related("customer").select("order_count", orderCount()))));

    assertThat(order.customer()).extracting(Shop.Customer::id, Shop.Customer::orderCount)
        .containsExactly("beth", 2L);
  }

  @Test
  @DisplayName("Refuses a computed field as a column of a statement")
  void refuseColumn() throws TimeoutException {
    assertThat(failure(customers.list(customers.query().where(gt("order_count", 1)))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("The field order_count of the model of"
            + " the table customers is computed, so a statement names it only as the name of a value that it selects");
  }

  @Test
  @DisplayName("Refuses a value under the name of a stored field or of no field, which the entity would lose")
  void refuseName() throws TimeoutException {
    assertThat(failure(orders.list(orders.query().select("total_cents", multiply(column("total_cents"), value(2))))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The query selects a value under the name"
            + " total_cents, which is a stored field of the model of the table orders. Give the value the name of a"
            + " computed field, or read it with rows().");
    assertThat(failure(orders.list(orders.query().select("double_total", multiply(column("total_cents"),
        value(2)))))).isInstanceOf(IllegalArgumentException.class).hasMessage("The query selects a value under the"
            + " name double_total, which is no field of the model of the table orders. Give the value the name of a"
            + " computed field, or read it with rows().");
  }

  @Test
  @DisplayName("Reads the grouped fields of a grouped query")
  void grouped() throws TimeoutException {
    List<Shop.Order> found = await(orders.list(orders.query().groupBy("customer_id").orderBy("customer_id")));

    assertThat(found).extracting(Shop.Order::customerId, Shop.Order::id).containsExactly(tuple("anne", null),
        tuple("beth", null), tuple("carl", null));
  }
}

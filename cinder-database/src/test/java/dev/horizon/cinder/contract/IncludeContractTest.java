package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.filter.Filter.eq;
import static dev.horizon.basepoint.filter.Filter.in;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public abstract class IncludeContractTest extends ContractTest {

  @Test
  @DisplayName("Reads the related rows of a relation of many, in their order")
  void many() throws TimeoutException {
    List<Shop.Order> found = await(orders.list(orders.query().where(in("id", List.of(1L, 2L))).orderBy("id")
        .include("items", orders.related("items").orderBy("position"))));

    assertThat(found.get(0).items()).extracting(Shop.Item::product).containsExactly("cup", "mug");
    assertThat(found.get(1).items()).isEmpty();
  }

  @Test
  @DisplayName("Reads the related row of a relation of one")
  void one() throws TimeoutException {
    Shop.Order order = await(orders.single(orders.query().where(eq("id", 3L)).includeOne("customer", orders.related(
        "customer"))));

    assertThat(order.customer()).isEqualTo(new Shop.Customer("beth", "beth@example.com", "Beth"));
  }

  @Test
  @DisplayName("Narrows the related rows by a filter of the related model")
  void narrow() throws TimeoutException {
    Shop.Order order = await(orders.single(orders.query().where(eq("id", 1L)).include("items", orders.related("items")
        .where(eq("product", "mug")))));

    assertThat(order.items()).containsExactly(new Shop.Item(10L, 1L, "mug", 2, 2));
  }

  @Test
  @DisplayName("Reads the related rows of several relations, with every field of the row")
  void several() throws TimeoutException {
    Shop.Order order = await(orders.single(orders.query().where(eq("id", 1L)).include("items", orders.related("items"))
        .includeOne("customer", orders.related("customer"))));

    assertThat(order.items()).hasSize(2);
    assertThat(order.customer().name()).isEqualTo("Anne");
    assertThat(order.createTime()).isEqualTo(FIRST);
  }

  @Test
  @DisplayName("Reads only the fields that the queries of the row and of its related rows name")
  void columns() throws TimeoutException {
    Shop.Order order = await(orders.single(orders.query().columns("id").where(eq("id", 1L)).include("items", orders
        .related("items").columns("id", "product").orderBy("position"))));

    assertThat(order.state()).isNull();
    assertThat(order.items()).containsExactly(new Shop.Item(11L, null, "cup", null, null), new Shop.Item(10L, null,
        "mug", null, null));
  }

  @Test
  @DisplayName("Refuses a name that is no relation of the model")
  void refuseRelation() throws TimeoutException {
    assertThatThrownBy(() -> orders.related("payments")).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The model of the table orders has no relation payments.");
    assertThat(failure(orders.list(orders.query().include("payments", items.query()))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The model of the table orders has no relation"
            + " payments, so the rows of the include have no field to go to.");
  }

  @Test
  @DisplayName("Refuses an include of another shape than its relation")
  void refuseShape() throws TimeoutException {
    assertThat(failure(orders.list(orders.query().include("customer", orders.related("customer")))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The relation customer of the model of the table"
            + " orders has one row. Include it with includeOne().");
    assertThat(failure(orders.list(orders.query().includeOne("items", orders.related("items")))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The relation items of the model of the table"
            + " orders has many rows. Include it with include().");
  }

  @Test
  @DisplayName("Refuses a field that the related model does not have")
  void refuseField() throws TimeoutException {
    assertThat(failure(orders.list(orders.query().include("items", orders.related("items").where(eq("colour",
        "red")))))).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The model of the table items has no field colour.");
  }
}

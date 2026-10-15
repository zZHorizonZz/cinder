package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.Filter.eq;
import static dev.horizon.basepoint.Filter.in;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

public abstract class IncludeContractTest extends ContractTest {

  @Test
  void readsTheRelatedRowsOfARelation() {
    List<Shop.Order> found = orders.find(in("id", List.of(1L, 2L))).orderBy("id").include("items",
        rows -> rows.orderBy("position")).list().await();
    assertThat(found.get(0).items()).extracting(Shop.Item::product).containsExactly("cup", "mug");
    assertThat(found.get(1).items()).isEmpty();
  }

  @Test
  void readsTheRelatedRowOfARelationOfOne() {
    Shop.Order order = orders.find(eq("id", 3L)).include("customer").single().await();
    assertThat(order.customer()).isEqualTo(new Shop.Customer("beth", "beth@example.com", "Beth"));
  }

  @Test
  void narrowsTheRelatedRows() {
    Shop.Order order = orders.find(eq("id", 1L)).include("items", rows -> rows.where(eq("product", "mug")))
        .single().await();
    assertThat(order.items()).containsExactly(new Shop.Item(10L, 1L, "mug", 2, 2));
  }

  @Test
  void readsTheRelatedRowsOfSeveralRelations() {
    Shop.Order order = orders.find(eq("id", 1L)).include("items").include("customer").single().await();
    assertThat(order.items()).hasSize(2);
    assertThat(order.customer().name()).isEqualTo("Anne");
    assertThat(order.createTime()).isEqualTo(FIRST);
  }
}

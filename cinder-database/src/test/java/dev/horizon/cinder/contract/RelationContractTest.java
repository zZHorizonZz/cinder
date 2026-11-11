package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.expression.Expression.column;
import static dev.horizon.basepoint.filter.Filter.eq;
import static org.assertj.core.api.Assertions.assertThat;

import dev.horizon.cinder.Table;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public abstract class RelationContractTest extends ContractTest {

  private Table<Shop.Category> categories;
  private Table<Shop.Tag> tags;
  private Table<Shop.CustomerTag> customerTags;

  @BeforeEach
  void tree() throws TimeoutException {
    categories = database.table(Shop.CATEGORY);
    tags = database.table(Shop.TAG);
    customerTags = database.table(Shop.CUSTOMER_TAG);
    await(categories.insert(List.of(new Shop.Category(1L, null, "clothing", null, List.of()), new Shop.Category(2L,
        1L, "shirts", null, List.of()), new Shop.Category(3L, 1L, "shoes", null, List.of()))));
    await(tags.insert(List.of(new Shop.Tag(1L, "loyal"), new Shop.Tag(2L, "new"), new Shop.Tag(3L, "late"))));
    await(customerTags.insert(List.of(new Shop.CustomerTag("anne", 1L), new Shop.CustomerTag("anne", 3L),
        new Shop.CustomerTag("beth", 2L))));
  }

  @Test
  @DisplayName("Reads the parent and the children of a relation to its own table")
  void self() throws TimeoutException {
    Shop.Category shirts = await(categories.single(categories.query().where(eq("id", 2L)).includeOne("parent",
        categories.related("parent"))));
    Shop.Category clothing = await(categories.single(categories.query().where(eq("id", 1L)).include("children",
        categories.related("children").orderBy("id"))));

    assertThat(shirts.parent().name()).isEqualTo("clothing");
    assertThat(clothing.children()).extracting(Shop.Category::name).containsExactly("shirts", "shoes");
  }

  @Test
  @DisplayName("Reads the rows of a relation through a link table")
  void through() throws TimeoutException {
    List<Shop.Customer> found = await(customers.list(customers.query().include("tags", customers.related("tags"))
        .orderBy("id")));

    assertThat(found.get(0).tags()).extracting(Shop.Tag::label).containsExactlyInAnyOrder("loyal", "late");
    assertThat(found.get(1).tags()).extracting(Shop.Tag::label).containsExactly("new");
    assertThat(found.get(2).tags()).isEmpty();
  }

  @Test
  @DisplayName("Narrows the rows of a relation through a link table by the paths of the target")
  void throughNarrowed() throws TimeoutException {
    Shop.Customer anne = await(customers.single(customers.query().where(eq("id", "anne")).include("tags", customers
        .related("tags").where(eq(column("tags", "label"), "late")))));

    assertThat(anne.tags()).containsExactly(new Shop.Tag(3L, "late"));
  }

  @Test
  @DisplayName("Reads related rows of a relation with a filter of its own")
  void narrowed() throws TimeoutException {
    Shop.Customer beth = await(customers.single(customers.query().where(eq("id", "beth")).include("orders",
        customers.related("orders").where(eq("state", "OPEN")))));

    assertThat(beth.orders()).extracting(Shop.Order::id).containsExactly(3L);
  }
}

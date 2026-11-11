package dev.horizon.cinder.contract;

import dev.horizon.cinder.Context;
import dev.horizon.cinder.Extension;
import dev.horizon.cinder.Invocation;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.ModelSource;
import dev.horizon.cinder.Part;
import dev.horizon.cinder.Relation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Shop implements Extension, ModelSource {

  static final Customer CUSTOMER = new Customer(null, null, null);
  static final Order ORDER = new Order(null, null, null, null, null);
  static final Item ITEM = new Item(null, null, null, null, null);
  static final File FILE = new File(null, null);
  static final Category CATEGORY = new Category(null, null, null, null, List.of());
  static final Tag TAG = new Tag(null, null);
  static final CustomerTag CUSTOMER_TAG = new CustomerTag(null, null);
  static final Note NOTE = new Note(null, null, null);

  public record Customer(String id, String email, String name, Long orderCount, List<Order> orders,
      List<Tag> tags) {

    public Customer(String id, String email, String name) {
      this(id, email, name, null, List.of(), List.of());
    }
  }

  public record Order(Long id, String customerId, String state, Long totalCents, Integer version, Instant createTime,
      Instant cancelTime, Customer customer, List<Item> items) {

    public Order(Long id, String customerId, String state, Long totalCents, Instant createTime) {
      this(id, customerId, state, totalCents, 0, createTime, null, null, List.of());
    }
  }

  public record Item(Long id, Long orderId, String product, Integer quantity, Integer position) {
  }

  public record File(Long id, byte[] content) {
  }

  public record Category(Long id, Long parentId, String name, Category parent, List<Category> children) {
  }

  public record Tag(Long id, String label) {
  }

  public record CustomerTag(String customerId, Long tagId) {
  }

  public record Note(Long id, String body, String slug) {
  }

  private record NoteKey() implements Part {

    @Override
    public Map<String, Object> write(Map<String, Object> row, Context<?> context) {
      if (context.kind() != Invocation.Kind.INSERT || row.get("id") != null) {
        return row;
      }
      Map<String, Object> keyless = new LinkedHashMap<>(row);
      keyless.remove("id");
      return keyless;
    }
  }

  private final Map<Class<?>, Model<?>> models = Map.of(
      Customer.class, RecordModel.of(Customer.class, "customers", "id").computed("order_count")
          .relation(new Relation<>("orders", ORDER, Relation.Shape.MANY, List.of("id"), List.of("customer_id")))
          .relation(new Relation<>("tags", TAG, Relation.Shape.MANY, List.of("id"), List.of("customer_id"),
              new Relation.Link<>(CUSTOMER_TAG, List.of("tag_id"), List.of("id")))),
      Order.class, RecordModel.of(Order.class, "orders", "id").column("create_time", "created_at")
          .nullable("cancel_time")
          .relation(new Relation<>("customer", CUSTOMER, Relation.Shape.ONE, List.of("customer_id"),
              List.of("id")))
          .relation(new Relation<>("items", ITEM, Relation.Shape.MANY, List.of("id"), List.of("order_id"))),
      Item.class, RecordModel.of(Item.class, "items", "id"),
      File.class, RecordModel.of(File.class, "files", "id").nullable("content"),
      Category.class, RecordModel.of(Category.class, "categories", "id").nullable("parent_id")
          .relation(new Relation<>("parent", CATEGORY, Relation.Shape.ONE, List.of("parent_id"), List.of("id")))
          .relation(new Relation<>("children", CATEGORY, Relation.Shape.MANY, List.of("id"), List.of("parent_id"))),
      Tag.class, RecordModel.of(Tag.class, "tags", "id"),
      CustomerTag.class, RecordModel.of(CustomerTag.class, "customer_tags", "customer_id", "tag_id"),
      Note.class, RecordModel.of(Note.class, "notes", "id").nullable("slug"));

  @Override
  public List<ModelSource> models() {
    return List.of(this);
  }

  @Override
  public List<Part> parts(Model<?> model) {
    return model.table().equals("notes") ? List.of(new NoteKey()) : List.of();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <E> Optional<Model<E>> model(E prototype) {
    return Optional.ofNullable((Model<E>) models.get(prototype.getClass()));
  }
}

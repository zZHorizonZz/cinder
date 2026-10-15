package dev.horizon.cinder.contract;

import dev.horizon.cinder.Backend;
import dev.horizon.cinder.Database;
import dev.horizon.cinder.Extension;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.ModelSource;
import dev.horizon.cinder.Relation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Shop implements Extension, ModelSource {

  public record Customer(String id, String email, String name) {
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

  public record Files(Database database) implements Backend<File, Long> {

    @Override
    public Class<File> type() {
      return File.class;
    }
  }

  public record Orders(Database database) implements Backend<Order, Long> {

    @Override
    public Class<Order> type() {
      return Order.class;
    }
  }

  public record Customers(Database database) implements Backend<Customer, String> {

    @Override
    public Class<Customer> type() {
      return Customer.class;
    }
  }

  public record Items(Database database) implements Backend<Item, Long> {

    @Override
    public Class<Item> type() {
      return Item.class;
    }
  }

  private final Map<Class<?>, Model<?>> models = Map.of(
      Customer.class, RecordModel.of(Customer.class, "customers", "id"),
      Order.class, RecordModel.of(Order.class, "orders", "id").column("create_time", "created_at")
          .nullable("cancel_time")
          .relation(new Relation<>("customer", Customer.class, Relation.Shape.ONE, List.of("customer_id"),
              List.of("id")))
          .relation(new Relation<>("items", Item.class, Relation.Shape.MANY, List.of("id"), List.of("order_id"))),
      Item.class, RecordModel.of(Item.class, "items", "id"),
      File.class, RecordModel.of(File.class, "files", "id").nullable("content"));

  @Override
  public List<ModelSource> models() {
    return List.of(this);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <E> Optional<Model<E>> model(Class<E> type) {
    return Optional.ofNullable((Model<E>) models.get(type));
  }
}

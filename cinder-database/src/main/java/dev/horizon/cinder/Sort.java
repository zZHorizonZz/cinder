package dev.horizon.cinder;

import dev.horizon.basepoint.Direction;
import java.util.ArrayList;
import java.util.List;

/**
 * The order of a query, by fields of the message.
 *
 * <pre>{@code
 * Sort.by("create_time").descending().and("id")
 * }</pre>
 *
 * @param orders the fields, in order
 */
public record Sort(List<Order> orders) {

  /**
   * A field and its direction.
   *
   * @param field the name of the field
   * @param direction the direction
   */
  public record Order(String field, Direction direction) {
  }

  public Sort {
    orders = List.copyOf(orders);
  }

  /**
   * Sorts by a field, in ascending order.
   *
   * @param field the name of the field, such as {@code create_time}
   * @return the sort
   */
  public static Sort by(String field) {
    return new Sort(List.of(new Order(field, Direction.ASC)));
  }

  /**
   * Sorts by another field, in ascending order, after the fields of this sort.
   *
   * @param field the name of the field, such as {@code id}
   * @return the sort, with the field last
   */
  public Sort and(String field) {
    List<Order> more = new ArrayList<>(orders);
    more.add(new Order(field, Direction.ASC));
    return new Sort(more);
  }

  /**
   * Sorts by the last field in descending order.
   *
   * @return the sort, with the direction of its last field changed
   * @throws java.util.NoSuchElementException if the sort has no field
   */
  public Sort descending() {
    List<Order> changed = new ArrayList<>(orders);
    Order last = changed.removeLast();
    changed.add(new Order(last.field(), Direction.DESC));
    return new Sort(changed);
  }
}

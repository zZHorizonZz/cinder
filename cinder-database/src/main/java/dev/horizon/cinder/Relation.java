package dev.horizon.cinder;

import java.util.List;

/**
 * A relation from the rows of a table to the rows of another: the rows of the target whose reference fields equal the
 * fields of the row. An include reads the related rows into the field of the relation.
 *
 * @param name the name of the relation, which is the field that holds the related rows, such as {@code items}
 * @param target an entity of the related type, which names its model
 * @param shape whether the field holds one related entity or a list of them
 * @param fields the fields of this model, such as {@code id}
 * @param references the fields of the target that match them, such as {@code order_id}
 * @param <T> the type of the related entities
 */
public record Relation<T>(String name, T target, Shape shape, List<String> fields, List<String> references) {

  /**
   * How many related rows a row has.
   */
  public enum Shape {
    /** At most one, such as the customer of an order. */
    ONE,
    /** Any number, such as the items of an order. */
    MANY
  }

  public Relation {
    fields = List.copyOf(fields);
    references = List.copyOf(references);
  }
}

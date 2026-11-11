package dev.horizon.cinder;

import java.util.List;

/**
 * A relation from the rows of a table to the rows of another: the rows of the target whose reference fields equal the
 * fields of the row. An include reads the related rows into the field of the relation.
 *
 * <p>
 * A relation through a link table, such as the tags of a customer through {@code customer_tags}, relates the fields of
 * the row to the reference fields of the link, and the fields of the link to the reference fields of the target.
 *
 * @param name the name of the relation, which is the field that holds the related rows, such as {@code items}
 * @param target an entity of the related type, which names its model
 * @param shape whether the field holds one related entity or a list of them
 * @param fields the fields of this model, such as {@code id}
 * @param references the fields of the target that match them, such as {@code order_id}, or of the link for a
 * relation through a link table
 * @param through the link table between the two, or {@code null} for a relation without one
 * @param <T> the type of the related entities
 */
public record Relation<T>(String name, T target, Shape shape, List<String> fields, List<String> references,
    Link<?> through) {

  /**
   * How many related rows a row has.
   */
  public enum Shape {
    /** At most one, such as the customer of an order. */
    ONE,
    /** Any number, such as the items of an order. */
    MANY
  }

  /**
   * The link table of a relation between two tables, which holds a row for each pair of related rows.
   *
   * @param table an entity of the link table, which names its model
   * @param fields the fields of the link that name a row of the target, such as {@code tag_id}
   * @param references the fields of the target that match them, such as {@code id}
   * @param <L> the type of the entities of the link table
   */
  public record Link<L>(L table, List<String> fields, List<String> references) {

    public Link {
      fields = List.copyOf(fields);
      references = List.copyOf(references);
    }
  }

  public Relation {
    fields = List.copyOf(fields);
    references = List.copyOf(references);
  }

  /**
   * Gives a relation without a link table.
   *
   * @param name the name of the relation, which is the field that holds the related rows, such as {@code items}
   * @param target an entity of the related type, which names its model
   * @param shape whether the field holds one related entity or a list of them
   * @param fields the fields of this model, such as {@code id}
   * @param references the fields of the target that match them, such as {@code order_id}
   */
  public Relation(String name, T target, Shape shape, List<String> fields, List<String> references) {
    this(name, target, shape, fields, references, null);
  }
}

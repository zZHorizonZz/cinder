package dev.horizon.cinder;

import dev.horizon.basepoint.Filter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How a type of entity maps to its table: the table name, a column per field, the key and the relations to other
 * tables. A {@link ModelSource} gives the models of the types it knows, such as protobuf messages or Java records.
 *
 * <p>
 * Filters, orders, masks and assignments name the fields of the model, and the model maps each to its column.
 *
 * @param <E> the type of the entities
 */
public interface Model<E> {

  /**
   * Gives the type of the entities.
   *
   * @return the type, such as {@code Order.class}
   */
  Class<E> type();

  /**
   * Gives the name of the table.
   *
   * @return the name, such as {@code orders}
   */
  String table();

  /**
   * Gives the fields that have a column.
   *
   * @return the fields, in the order of the type
   */
  List<Field<E, ?>> fields();

  /**
   * Gives the fields of the primary key.
   *
   * @return the fields, in the order of the key
   */
  List<Field<E, ?>> key();

  /**
   * Finds a field.
   *
   * @param name the name of the field, such as {@code user}, or of its column, such as {@code _user}
   * @return the field, or empty if the model has none of the name
   */
  Optional<Field<E, ?>> field(String name);

  /**
   * Gives the relations to the rows of other tables.
   *
   * @return the relations, in the order of the type
   */
  List<Relation<?>> relations();

  /**
   * Finds a relation.
   *
   * @param name the name of the relation, such as {@code items}
   * @return the relation, or empty if the model has none of the name
   */
  Optional<Relation<?>> relation(String name);

  /**
   * Makes an entity of the values of a row.
   *
   * @param values the values, by field name, as the codecs gave them. A field without a value keeps its default.
   * @return the entity
   * @throws IllegalArgumentException if a value is not of the type of its field
   */
  E assemble(Map<String, Object> values);

  /**
   * Gives an entity with the value of one field or relation changed, such as a key that the database computed or the rows of an
   * include.
   *
   * @param entity the entity
   * @param name the name of the field or the relation
   * @param value the value, or for a relation the related entity or the list of them
   * @return the entity with the value, a new one if the type is immutable
   * @throws IllegalArgumentException if the model has no field or relation of the name
   */
  E with(E entity, String name, Object value);

  /**
   * Gives the filter that selects the row of a key.
   *
   * @param id the value of the key field
   * @return the filter
   * @throws IllegalArgumentException if the key has more than one field: select such a row with a filter on each of
   * them
   */
  default Filter keyFilter(Object id) {
    List<Field<E, ?>> key = key();
    if (key.size() != 1) {
      throw new IllegalArgumentException("The key of the table " + table() + " has " + key.size() + " fields, so a"
          + " filter on each of them selects its rows, not a single key.");
    }
    return Filter.eq(key.getFirst().name(), id);
  }

  /**
   * Gives the filter that selects the row of an entity, by its key fields.
   *
   * @param entity the entity
   * @return the filter, with a condition per key field
   */
  default Filter keyFilterOf(E entity) {
    return Filter.and(key().stream().map(field -> Filter.eq(field.name(), field.get(entity))).toArray(Filter[]::new));
  }
}

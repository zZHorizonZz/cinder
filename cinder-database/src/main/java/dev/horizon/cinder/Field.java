package dev.horizon.cinder;

import java.util.Optional;

/**
 * A field of an entity and its column.
 *
 * @param <E> the type of the entities
 * @param <V> the type of the values of the field
 */
public interface Field<E, V> {

  /**
   * Gives the name of the field, which filters, orders, masks and assignments use.
   *
   * @return the name, such as {@code create_time}
   */
  String name();

  /**
   * Gives the name of the column.
   *
   * @return the name, such as {@code created_at}
   */
  String column();

  /**
   * Gives the type of the values of the field.
   *
   * @return the type, such as {@code String.class}
   */
  Class<V> type();

  /**
   * Tells if the column is part of the primary key.
   *
   * @return true if it is
   */
  boolean key();

  /**
   * Tells if the column can be NULL.
   *
   * @return true if it can, as the column of an {@code optional} protobuf field or a nullable record component can
   */
  boolean nullable();

  /**
   * Gives the value of the field of an entity.
   *
   * @param entity the entity
   * @return the value, or null if the field has none
   */
  V get(E entity);

  /**
   * Gives an attribute of the field, which an extension reads to pick its columns, such as the obfuscated ones.
   *
   * @param attribute the attribute
   * @param <T> the type of the value of the attribute
   * @return the value that the model source gave the field, or empty if it gave none
   */
  <T> Optional<T> attribute(Attribute<T> attribute);
}

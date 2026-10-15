package dev.horizon.cinder;

/**
 * Changes the value of a column on its way to the database, and back on its way to the entity. A codec picks its
 * fields, usually by an {@link Attribute} that the model source gave them, such as the protobuf option
 * {@code (obfuscation.v1.obfuscated) = true}. The codecs of a field chain in the order of their extensions, and read
 * back in the reverse order. A null value skips the codecs.
 */
public interface TypeCodec {

  /**
   * Tells if the codec changes the values of a field.
   *
   * @param field the field
   * @return true to change its values both ways
   */
  boolean applies(Field<?, ?> field);

  /**
   * Gives the codec of one field, which the database keeps for the field, so that a codec resolves what it does with
   * a field once rather than for each value.
   *
   * @param field the field, which the codec applies to
   * @return the codec of the field. The default is this codec.
   */
  default TypeCodec of(Field<?, ?> field) {
    return this;
  }

  /**
   * Gives the type of the values that the codec stores, so that the driver reads the column as that type.
   *
   * @param field the field
   * @param given the type of the values that the codec gets: the type of the field, or the type that the codec before
   * this one stores
   * @return the type, such as {@code Instant} for a codec of protobuf timestamps. The default is the type it gets.
   */
  default Class<?> type(Field<?, ?> field, Class<?> given) {
    return given;
  }

  /**
   * Gives the value to store, from the value of the field.
   *
   * @param field the field
   * @param value the value of the field, or the value that the codec before this one gave, never null
   * @return the value to bind to the column
   */
  Object write(Field<?, ?> field, Object value);

  /**
   * Gives the value of the field, from the stored value.
   *
   * @param field the field
   * @param stored the value of the column, or the value that the codec after this one gave, never null
   * @return the value of the field
   */
  Object read(Field<?, ?> field, Object stored);
}

package dev.horizon.cinder.impl;

import dev.horizon.cinder.Driver;
import dev.horizon.cinder.Field;
import dev.horizon.cinder.TypeCodec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * A field of a model with the codecs of the database that apply to it, each resolved for the field.
 *
 * @param field the field
 * @param type the type of the values of the field, boxed
 * @param codecs the codecs, in the order they write
 * @param reversed the codecs, in the order they read
 * @param stored the type of the values that the last codec stores, or the type of the field without codecs
 */
record Binding(Field<?, ?> field, Class<?> type, List<TypeCodec> codecs, List<TypeCodec> reversed, Class<?> stored) {

  private static final Map<Class<?>, Class<?>> BOXES = Map.of(boolean.class, Boolean.class, byte.class, Byte.class,
      short.class, Short.class, int.class, Integer.class, long.class, Long.class, float.class, Float.class,
      double.class, Double.class, char.class, Character.class);

  static Binding of(Field<?, ?> field, List<TypeCodec> codecs) {
    List<TypeCodec> own = codecs.stream().filter(codec -> codec.applies(field)).map(codec -> codec.of(field))
        .toList();
    Class<?> type = BOXES.getOrDefault(field.type(), field.type());
    Class<?> stored = type;
    for (TypeCodec codec : own) {
      Class<?> given = codec.type(field, stored);
      stored = BOXES.getOrDefault(given, given);
    }
    return new Binding(field, type, own, own.reversed(), stored);
  }

  String name() {
    return field.name();
  }

  String column() {
    return field.column();
  }

  @SuppressWarnings("unchecked")
  Object get(Object entity) {
    return ((Field<Object, ?>) field).get(entity);
  }

  /**
   * Gives the value that the client binds for a value. A value of another type than the field, such as the text of a
   * timestamp in an AIP-160 filter, becomes a value of the field first, so that it goes through the codecs as any
   * value of the field does.
   *
   * @throws IllegalArgumentException if the value is not one of the field, and the driver cannot read it as one
   */
  Object bind(Driver driver, Object value) {
    if (value == null) {
      return driver.bindNull(stored);
    }
    return driver.bind(store(type.isInstance(value) ? value : coerce(driver, value)));
  }

  /** Reads a value of the column, or of the JSON of an include, as a value of the field. */
  Object read(Driver driver, Object raw, boolean json) {
    return raw == null ? null : unstore(json ? driver.readJson(raw, stored) : driver.read(raw, stored));
  }

  /** Gives the text of a value of the field in a page token: its stored value, and Base64 for bytes. */
  String cursor(Object value) {
    Object storedValue = value == null ? null : store(value);
    return storedValue instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes)
        : String.valueOf(storedValue);
  }

  /** Reads the text of a value of the field in a page token. */
  Object fromCursor(Driver driver, String text) {
    return unstore(stored == byte[].class ? Base64.getDecoder().decode(text) : driver.read(text, stored));
  }

  private Object coerce(Driver driver, Object value) {
    Object read;
    try {
      read = driver.read(value, stored);
    } catch (RuntimeException unreadable) {
      throw new IllegalArgumentException("The value " + value + " is not a value of the field " + field.name() + ".",
          unreadable);
    }
    if (!stored.isInstance(read)) {
      throw new IllegalArgumentException("The value " + value + " is not a value of the field " + field.name()
          + ", whose column holds " + stored.getSimpleName() + ".");
    }
    return unstore(read);
  }

  private Object store(Object value) {
    Object storedValue = value;
    for (TypeCodec codec : codecs) {
      storedValue = codec.write(field, storedValue);
    }
    return storedValue;
  }

  private Object unstore(Object storedValue) {
    Object value = storedValue;
    for (TypeCodec codec : reversed) {
      value = codec.read(field, value);
    }
    return value;
  }
}

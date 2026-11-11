package dev.horizon.cinder;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * The values that pick out one row of a table, in the order of the fields of its {@link KeyFields}. Bytes compare by
 * their content, so that a key of bytes finds its row.
 *
 * @param values the values, with each array of bytes held as a buffer
 */
record RowKey(List<Object> values) {

  static RowKey of(List<Object> values) {
    return new RowKey(values.stream().map(value -> value instanceof byte[] bytes ? (Object) ByteBuffer.wrap(bytes)
        : value).toList());
  }

  /**
   * Gives a value as the database takes it, with a buffer as its bytes.
   */
  Object get(int index) {
    if (values.get(index) instanceof ByteBuffer buffer) {
      byte[] bytes = new byte[buffer.remaining()];
      buffer.duplicate().get(bytes);
      return bytes;
    }
    return values.get(index);
  }
}

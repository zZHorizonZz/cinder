package dev.horizon.cinder.protobuf;

import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;

/**
 * Converts one value of an API to the columns of a protobuf row, and back. Only the mapper knows how the value is
 * stored.
 *
 * @param <T> the value of the API
 * @param <M> the row, or its builder
 * @param <B> the builder of the row
 */
public interface ProtobufMapper<T, M extends MessageOrBuilder, B extends Message.Builder> {

  /**
   * Reads the value from the columns of the row.
   *
   * @throws IllegalStateException if the columns hold a value that the mapper does not know
   */
  T read(M row);

  /**
   * Writes the value to the columns of the row, and returns the row.
   */
  B write(B row, T value);
}

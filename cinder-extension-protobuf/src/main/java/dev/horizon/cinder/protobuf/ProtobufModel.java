package dev.horizon.cinder.protobuf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;
import dev.horizon.cinder.Model;

/**
 * The model of a protobuf message. Its fields are {@link ProtobufField}s, and its entities are built with the builder
 * of the message, so {@link #with} gives a new message.
 *
 * @param <M> the message type
 */
public interface ProtobufModel<M extends Message> extends Model<M> {

  /**
   * Gives the default instance of the message type.
   *
   * @return the instance, such as {@code Order.getDefaultInstance()}
   */
  M prototype();

  /**
   * Gives the descriptor of the message type.
   *
   * @return the descriptor, with the {@code leadseal.v1.table} option
   */
  Descriptor descriptor();
}

package dev.horizon.cinder.protobuf;

import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Extension;
import com.google.protobuf.Message;
import dev.horizon.cinder.Attribute;
import dev.horizon.cinder.Field;

/**
 * A field of a protobuf message and its column.
 *
 * @param <M> the message type
 */
public interface ProtobufField<M extends Message> extends Field<M, Object> {

  /**
   * Gives the attribute of a custom field option, so that an extension reads the option without knowing protobuf.
   *
   * @param option the option, such as {@code ObfuscationProto.obfuscated}
   * @param <T> the type of the value of the option
   * @return the attribute of the full name of the option, such as {@code obfuscation.v1.obfuscated}
   */
  @SuppressWarnings("unchecked")
  static <T> Attribute<T> attribute(Extension<FieldOptions, T> option) {
    return Attribute.of(option.getDescriptor().getFullName(), (Class<T>) option.getDefaultValue().getClass());
  }

  /**
   * Gives the descriptor of the field.
   *
   * @return the descriptor, with its options
   */
  FieldDescriptor descriptor();
}

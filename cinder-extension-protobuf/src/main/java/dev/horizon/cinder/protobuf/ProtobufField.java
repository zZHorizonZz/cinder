package dev.horizon.cinder.protobuf;

import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Extension;
import com.google.protobuf.Message;
import dev.horizon.cinder.Attribute;
import dev.horizon.cinder.Field;
import dev.horizon.leadseal.protobuf.ColumnOptions;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A field of a protobuf message and its column, as the {@code leadseal.v1.column} option of the field maps it.
 *
 * @param <M> the message type
 */
public final class ProtobufField<M extends Message> implements Field<M, Object> {

  private final FieldDescriptor descriptor;
  private final M prototype;
  private final ColumnOptions options;
  private final Map<String, Object> attributes;

  /**
   * Maps a field.
   *
   * @param descriptor the field
   * @param prototype the default instance of the message, for the builders of the values of the field
   * @param options the column options of the field
   * @param attributes the custom options of the field, by full name
   */
  ProtobufField(FieldDescriptor descriptor, M prototype, ColumnOptions options, Map<String, Object> attributes) {
    this.descriptor = descriptor;
    this.prototype = prototype;
    this.options = options;
    this.attributes = Map.copyOf(attributes);
  }

  /**
   * Gives the attribute of a custom field option, so that an extension reads the option without knowing protobuf.
   *
   * @param option the option, such as {@code ObfuscationProto.obfuscated}
   * @param <T> the type of the value of the option
   * @return the attribute of the full name of the option, such as {@code obfuscation.v1.obfuscated}
   */
  @SuppressWarnings("unchecked")
  public static <T> Attribute<T> attribute(Extension<FieldOptions, T> option) {
    return Attribute.of(option.getDescriptor().getFullName(), (Class<T>) option.getDefaultValue().getClass());
  }

  /**
   * Gives the descriptor of the field.
   *
   * @return the descriptor, with its options
   */
  public FieldDescriptor descriptor() {
    return descriptor;
  }

  M prototype() {
    return prototype;
  }

  ColumnOptions options() {
    return options;
  }

  @Override
  public String name() {
    return descriptor.getName();
  }

  @Override
  public String column() {
    return options.getName().isEmpty() ? descriptor.getName() : options.getName();
  }

  /**
   * Gives the type of the values of the field. An enum field takes any value of its enum: its value descriptor, the
   * generated enum, the name or the number of a value, so its type is {@code Object}.
   */
  @Override
  @SuppressWarnings("unchecked")
  public Class<Object> type() {
    if (descriptor.isRepeated()) {
      return (Class<Object>) (Class<?>) List.class;
    }
    return (Class<Object>) switch (descriptor.getJavaType()) {
      case INT -> Integer.class;
      case LONG -> Long.class;
      case FLOAT -> Float.class;
      case DOUBLE -> Double.class;
      case BOOLEAN -> Boolean.class;
      case STRING -> String.class;
      case BYTE_STRING -> com.google.protobuf.ByteString.class;
      case ENUM -> Object.class;
      case MESSAGE -> prototype.getDefaultInstanceForType().getField(descriptor).getClass();
    };
  }

  @Override
  public boolean key() {
    return options.getPrimaryKey();
  }

  @Override
  public boolean nullable() {
    return options.hasNullable() ? options.getNullable()
        : descriptor.isRepeated() || descriptor.hasPresence() && !descriptor.isRequired();
  }

  @Override
  public Object get(M entity) {
    return descriptor.hasPresence() && !entity.hasField(descriptor) ? null : entity.getField(descriptor);
  }

  @Override
  public <T> Optional<T> attribute(Attribute<T> attribute) {
    return Optional.ofNullable(attributes.get(attribute.name())).filter(attribute.type()::isInstance)
        .map(attribute.type()::cast);
  }

  /**
   * Tells whether a value of the field is the value of a message that does not set the field.
   *
   * @param value a value of the field
   * @return true for {@code null}, the default value of a field without presence, and an empty repeated field
   */
  public boolean unset(Object value) {
    return value == null || !descriptor.hasPresence() && !descriptor.isRepeated()
        && value.equals(descriptor.getDefaultValue()) || descriptor.isRepeated() && ((List<?>) value).isEmpty();
  }
}

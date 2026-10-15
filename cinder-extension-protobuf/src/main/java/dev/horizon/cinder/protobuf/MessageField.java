package dev.horizon.cinder.protobuf;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import dev.horizon.cinder.Attribute;
import dev.horizon.leadseal.protobuf.ColumnOptions;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A field of a table message and its column, as the {@code leadseal.v1.column} option of the field maps it.
 *
 * @param descriptor the field
 * @param prototype the default instance of the message, for the builders of the values of the field
 * @param options the column options of the field
 * @param attributes the custom options of the field, by full name
 */
record MessageField<M extends Message>(FieldDescriptor descriptor, M prototype, ColumnOptions options,
    Map<String, Object> attributes) implements ProtobufField<M> {

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

  /** Tells if a value of the field is its default, which a message that does not set the field has. */
  boolean unset(Object value) {
    return value == null || !descriptor.hasPresence() && !descriptor.isRepeated()
        && value.equals(descriptor.getDefaultValue()) || descriptor.isRepeated() && ((List<?>) value).isEmpty();
  }
}

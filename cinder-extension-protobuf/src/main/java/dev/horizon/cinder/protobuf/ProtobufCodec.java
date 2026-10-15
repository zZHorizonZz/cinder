package dev.horizon.cinder.protobuf;

import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.ProtocolMessageEnum;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.util.JsonFormat;
import dev.horizon.cinder.Field;
import dev.horizon.cinder.TypeCodec;
import dev.horizon.leadseal.protobuf.ColumnOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The codec of the fields of table messages. It turns each value that a column does not hold as it is into the plain
 * value of the type of the column that Leadseal gives the field:
 *
 * <ul>
 * <li>a {@code Timestamp} into an {@code Instant}, a {@code google.type.Date} into a {@code LocalDate}, a
 * {@code google.type.TimeOfDay} into a {@code LocalTime} and a {@code google.type.Decimal} into a
 * {@code BigDecimal}.</li>
 * <li>a wrapper, such as {@code Int64Value}, into its value.</li>
 * <li>an enum into the name of its value, or into its number for an integer column.</li>
 * <li>bytes into a {@code byte[]}, or a {@code UUID} for a UUID column, and an unsigned 32-bit integer into a
 * {@code Long}.</li>
 * <li>a string into the value of its column type: a {@code UUID}, a date, a time, a timestamp, a decimal or JSON.</li>
 * <li>a repeated field, a map and any other message into JSON, as the protobuf JSON mapping writes them.</li>
 * </ul>
 *
 * The codec resolves the conversion of each field once, as the codec of the field.
 */
final class ProtobufCodec implements TypeCodec {

  private static final String DATE = "google.type.Date";
  private static final String TIME_OF_DAY = "google.type.TimeOfDay";
  private static final String DECIMAL = "google.type.Decimal";
  private static final int UUID_BYTES = 16;
  private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();
  private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();

  /**
   * The conversion of the values of one field.
   *
   * @param stored the type of the values of the column
   * @param write turns a value of the field into a value of the column
   * @param read turns a value of the column into a value of the field
   */
  private record Conversion(Class<?> stored, UnaryOperator<Object> write, UnaryOperator<Object> read)
      implements TypeCodec {

    @Override
    public boolean applies(Field<?, ?> field) {
      return true;
    }

    @Override
    public Class<?> type(Field<?, ?> field, Class<?> given) {
      return stored;
    }

    @Override
    public Object write(Field<?, ?> field, Object value) {
      return write.apply(value);
    }

    @Override
    public Object read(Field<?, ?> field, Object stored) {
      return read.apply(stored);
    }
  }

  @Override
  public boolean applies(Field<?, ?> field) {
    return field instanceof MessageField<?> message && conversion(message) != null;
  }

  @Override
  public TypeCodec of(Field<?, ?> field) {
    return conversion((MessageField<?>) field);
  }

  @Override
  public Object write(Field<?, ?> field, Object value) {
    return of(field).write(field, value);
  }

  @Override
  public Object read(Field<?, ?> field, Object stored) {
    return of(field).read(field, stored);
  }

  private static Conversion conversion(MessageField<?> field) {
    FieldDescriptor descriptor = field.descriptor();
    ColumnOptions column = field.options();
    if (descriptor.isRepeated()) {
      return json(field);
    }
    return switch (descriptor.getJavaType()) {
      case ENUM -> enumeration(descriptor.getEnumType(), column.getTypeCase() == ColumnOptions.TypeCase.INTEGER);
      case BYTE_STRING -> column.getTypeCase() == ColumnOptions.TypeCase.UUID ? new Conversion(UUID.class,
          value -> uuid((ByteString) value), stored -> bytes((UUID) stored))
          : new Conversion(byte[].class, value -> ((ByteString) value).toByteArray(),
              stored -> ByteString.copyFrom((byte[]) stored));
      case INT -> descriptor.getType() == FieldDescriptor.Type.UINT32
          || descriptor.getType() == FieldDescriptor.Type.FIXED32
              ? new Conversion(Long.class,
                  value -> Integer.toUnsignedLong((Integer) value), stored -> ((Long) stored).intValue())
              : null;
      case STRING -> text(column.getTypeCase());
      case MESSAGE -> message(field);
      default -> null;
    };
  }

  private static Conversion enumeration(EnumDescriptor type, boolean number) {
    UnaryOperator<Object> read = stored -> switch (stored) {
      case Number value -> type.findValueByNumberCreatingIfUnknown(value.intValue());
      case String name -> {
        EnumValueDescriptor value = type.findValueByName(name);
        if (value == null) {
          throw new IllegalArgumentException("The enum " + type.getFullName() + " has no value " + name + ".");
        }
        yield value;
      }
      default -> throw new IllegalArgumentException("The value " + stored + " is not a value of the enum "
          + type.getFullName() + ".");
    };
    UnaryOperator<Object> write = value -> {
      EnumValueDescriptor descriptor = switch (value) {
        case EnumValueDescriptor own -> own;
        case ProtocolMessageEnum generated -> generated.getValueDescriptor();
        default -> (EnumValueDescriptor) read.apply(value);
      };
      return number ? (Object) descriptor.getNumber() : descriptor.getName();
    };
    return new Conversion(number ? Integer.class : String.class, write, read);
  }

  private static Conversion text(ColumnOptions.TypeCase type) {
    return switch (type) {
      case UUID -> new Conversion(UUID.class, value -> UUID.fromString((String) value), Object::toString);
      case DATE -> new Conversion(LocalDate.class, value -> LocalDate.parse((String) value), Object::toString);
      case TIME -> new Conversion(LocalTime.class, value -> LocalTime.parse((String) value), Object::toString);
      case TIMESTAMP -> new Conversion(Instant.class, value -> Instant.parse((String) value), Object::toString);
      case DECIMAL -> new Conversion(BigDecimal.class, value -> new BigDecimal((String) value),
          stored -> ((BigDecimal) stored).toPlainString());
      case JSON -> new Conversion(Object.class, value -> ((String) value).isBlank() ? value
          : Json.decodeValue((String) value), stored -> stored instanceof String text ? text : Json.encode(stored));
      default -> null;
    };
  }

  private static Conversion message(MessageField<?> field) {
    Descriptor type = field.descriptor().getMessageType();
    return switch (type.getFullName()) {
      case "google.protobuf.Timestamp" -> new Conversion(Instant.class, value -> instant((Timestamp) value),
          stored -> timestamp((Instant) stored));
      case "google.protobuf.BoolValue" -> new Conversion(Boolean.class, value -> ((BoolValue) value).getValue(),
          stored -> BoolValue.of((Boolean) stored));
      case "google.protobuf.Int32Value" -> new Conversion(Integer.class, value -> ((Int32Value) value).getValue(),
          stored -> Int32Value.of((Integer) stored));
      case "google.protobuf.UInt32Value" -> new Conversion(Long.class, value -> Integer.toUnsignedLong(
          ((UInt32Value) value).getValue()), stored -> UInt32Value.of(((Long) stored).intValue()));
      case "google.protobuf.Int64Value" -> new Conversion(Long.class, value -> ((Int64Value) value).getValue(),
          stored -> Int64Value.of((Long) stored));
      case "google.protobuf.UInt64Value" -> new Conversion(Long.class, value -> ((UInt64Value) value).getValue(),
          stored -> UInt64Value.of((Long) stored));
      case "google.protobuf.FloatValue" -> new Conversion(Float.class, value -> ((FloatValue) value).getValue(),
          stored -> FloatValue.of((Float) stored));
      case "google.protobuf.DoubleValue" -> new Conversion(Double.class, value -> ((DoubleValue) value).getValue(),
          stored -> DoubleValue.of((Double) stored));
      case "google.protobuf.StringValue" -> new Conversion(String.class, value -> ((StringValue) value).getValue(),
          stored -> StringValue.of((String) stored));
      case "google.protobuf.BytesValue" -> new Conversion(byte[].class, value -> ((BytesValue) value).getValue()
          .toByteArray(), stored -> BytesValue.of(ByteString.copyFrom((byte[]) stored)));
      case DATE -> fields(field, LocalDate.class, "year", "month", "day");
      case TIME_OF_DAY -> fields(field, LocalTime.class, "hours", "minutes", "seconds", "nanos");
      case DECIMAL -> fields(field, BigDecimal.class, "value");
      default -> json(field);
    };
  }

  /**
   * Gives the conversion of a message of {@code google.type}, which protobuf-java has no classes of, by the fields of
   * its descriptor, resolved once.
   */
  private static Conversion fields(MessageField<?> field, Class<?> stored, String... names) {
    Message prototype = field.prototype().newBuilderForType().newBuilderForField(field.descriptor())
        .getDefaultInstanceForType();
    FieldDescriptor[] parts = new FieldDescriptor[names.length];
    for (int i = 0; i < names.length; i++) {
      parts[i] = prototype.getDescriptorForType().findFieldByName(names[i]);
    }
    UnaryOperator<Object> write = value -> {
      Message message = (Message) value;
      Object[] values = new Object[parts.length];
      for (int i = 0; i < parts.length; i++) {
        values[i] = message.getField(parts[i]);
      }
      return stored == LocalDate.class ? LocalDate.of((Integer) values[0], (Integer) values[1], (Integer) values[2])
          : stored == LocalTime.class ? LocalTime.of((Integer) values[0], (Integer) values[1], (Integer) values[2],
              (Integer) values[3]) : new BigDecimal((String) values[0]);
    };
    UnaryOperator<Object> read = value -> {
      Object[] values = switch (value) {
        case LocalDate date -> new Object[] { date.getYear(), date.getMonthValue(), date.getDayOfMonth() };
        case LocalTime time -> new Object[] { time.getHour(), time.getMinute(), time.getSecond(), time.getNano() };
        default -> new Object[] { ((BigDecimal) value).toPlainString() };
      };
      Message.Builder builder = prototype.newBuilderForType();
      for (int i = 0; i < parts.length; i++) {
        builder.setField(parts[i], values[i]);
      }
      return builder.build();
    };
    return new Conversion(stored, write, read);
  }

  /**
   * Gives the conversion of a field to the JSON of the protobuf JSON mapping: a repeated field as an array, a map and
   * a message as an object. It prints and parses a message of the field alone, so that every value is written as the
   * mapping writes it, such as a 64-bit integer as a string.
   */
  private static Conversion json(MessageField<?> field) {
    FieldDescriptor descriptor = field.descriptor();
    String name = descriptor.getJsonName();
    boolean array = descriptor.isRepeated() && !descriptor.isMapField();
    UnaryOperator<Object> write = value -> {
      try {
        Object json = new JsonObject(PRINTER.print(field.prototype().newBuilderForType().setField(descriptor,
            value).build())).getValue(name);
        return json != null ? json : array ? new JsonArray() : new JsonObject();
      } catch (InvalidProtocolBufferException failed) {
        throw new IllegalArgumentException("The value of the field " + descriptor.getFullName() + " has no JSON.",
            failed);
      }
    };
    UnaryOperator<Object> read = stored -> {
      Message.Builder builder = field.prototype().newBuilderForType();
      try {
        PARSER.merge(new JsonObject().put(name, stored).encode(), builder);
      } catch (InvalidProtocolBufferException failed) {
        throw new IllegalArgumentException("The JSON " + stored + " is not a value of the field "
            + descriptor.getFullName() + ".", failed);
      }
      return builder.getField(descriptor);
    };
    return new Conversion(array ? JsonArray.class : JsonObject.class, write, read);
  }

  private static Instant instant(Timestamp timestamp) {
    return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
  }

  private static UUID uuid(ByteString value) {
    ByteBuffer bytes = value.asReadOnlyByteBuffer();
    if (bytes.remaining() != UUID_BYTES) {
      throw new IllegalArgumentException("A UUID column holds 16 bytes, not " + bytes.remaining() + ".");
    }
    return new UUID(bytes.getLong(), bytes.getLong());
  }

  private static ByteString bytes(UUID uuid) {
    return ByteString.copyFrom(ByteBuffer.allocate(UUID_BYTES).putLong(uuid.getMostSignificantBits())
        .putLong(uuid.getLeastSignificantBits()).flip());
  }
}

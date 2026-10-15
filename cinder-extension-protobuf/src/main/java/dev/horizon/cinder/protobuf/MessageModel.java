package dev.horizon.cinder.protobuf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import dev.horizon.cinder.Field;
import dev.horizon.cinder.Relation;
import dev.horizon.leadseal.protobuf.ColumnOptions;
import dev.horizon.leadseal.protobuf.ForeignKeyOptions;
import dev.horizon.leadseal.protobuf.OptionsProto;
import dev.horizon.leadseal.protobuf.TableForeignKeyOptions;
import dev.horizon.leadseal.protobuf.TableOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The model of a message with the {@code leadseal.v1.table} option, as Leadseal maps it to its table: in the schema of
 * its {@code schema} option, if it has one.
 */
final class MessageModel<M extends Message> implements ProtobufModel<M> {

  private final Class<M> type;
  private final M prototype;
  private final String table;
  private final List<Field<M, ?>> fields = new ArrayList<>();
  private final List<Field<M, ?>> key;
  private final List<Relation<?>> relations = new ArrayList<>();
  private final Map<String, Field<M, ?>> names = new LinkedHashMap<>();
  private final Map<String, FieldDescriptor> descriptors = new LinkedHashMap<>();

  MessageModel(Class<M> type, M prototype) {
    this.type = type;
    this.prototype = prototype;
    Descriptor descriptor = prototype.getDescriptorForType();
    TableOptions options = descriptor.getOptions().getExtension(OptionsProto.table);
    String name = options.getName().isEmpty() ? snakeCase(descriptor.getName()) : options.getName();
    this.table = options.getSchema().isEmpty() ? name : options.getSchema() + "." + name;
    for (FieldDescriptor field : descriptor.getFields()) {
      ColumnOptions column = field.getOptions().getExtension(OptionsProto.column);
      if (!column.getIgnore()) {
        MessageField<M> mapped = new MessageField<>(field, prototype, column, attributes(field));
        fields.add(mapped);
        names.putIfAbsent(mapped.column(), mapped);
        descriptors.put(field.getName(), field);
      } else if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE && isTable(field.getMessageType())) {
        relations.add(relation(descriptor, field));
        descriptors.put(field.getName(), field);
      }
    }
    fields.forEach(field -> names.put(field.name(), field));
    this.key = fields.stream().filter(Field::key).toList();
    if (key.isEmpty()) {
      throw new IllegalArgumentException("The table message " + descriptor.getFullName() + " has no primary key."
          + " Set (leadseal.v1.column).primary_key on its key fields.");
    }
  }

  @Override
  public M prototype() {
    return prototype;
  }

  @Override
  public Descriptor descriptor() {
    return prototype.getDescriptorForType();
  }

  @Override
  public Class<M> type() {
    return type;
  }

  @Override
  public String table() {
    return table;
  }

  @Override
  public List<Field<M, ?>> fields() {
    return fields;
  }

  @Override
  public List<Field<M, ?>> key() {
    return key;
  }

  @Override
  public Optional<Field<M, ?>> field(String name) {
    return Optional.ofNullable(names.get(name));
  }

  @Override
  public List<Relation<?>> relations() {
    return relations;
  }

  @Override
  public Optional<Relation<?>> relation(String name) {
    return relations.stream().filter(relation -> relation.name().equals(name)).findFirst();
  }

  @Override
  @SuppressWarnings("unchecked")
  public M assemble(Map<String, Object> values) {
    Message.Builder builder = prototype.newBuilderForType();
    values.forEach((name, value) -> {
      if (value != null) {
        builder.setField(descriptor(name), value);
      }
    });
    return (M) builder.build();
  }

  @Override
  @SuppressWarnings("unchecked")
  public M with(M entity, String name, Object value) {
    FieldDescriptor field = descriptor(name);
    Message.Builder builder = entity.toBuilder();
    if (value == null) {
      builder.clearField(field);
    } else {
      builder.setField(field, value);
    }
    return (M) builder.build();
  }

  private FieldDescriptor descriptor(String name) {
    FieldDescriptor field = descriptors.get(name);
    if (field == null) {
      throw new IllegalArgumentException("The table message " + descriptor().getFullName() + " has no field " + name
          + ".");
    }
    return field;
  }

  /**
   * Gives the relation of a field whose type is another table, by the foreign key between the two tables: one of the
   * fields of this table to the other, or one of the other table to this one.
   */
  private Relation<?> relation(Descriptor descriptor, FieldDescriptor field) {
    Descriptor target = field.getMessageType();
    List<Relation<?>> found = new ArrayList<>();
    Class<?> targetType = prototype.newBuilderForType().newBuilderForField(field).build().getClass();
    Relation.Shape shape = field.isRepeated() ? Relation.Shape.MANY : Relation.Shape.ONE;
    for (List<List<String>> key : foreignKeys(descriptor, target)) {
      found.add(new Relation<>(field.getName(), targetType, shape, key.get(0), key.get(1)));
    }
    for (List<List<String>> key : foreignKeys(target, descriptor)) {
      found.add(new Relation<>(field.getName(), targetType, shape, key.get(1), key.get(0)));
    }
    if (found.size() != 1) {
      throw new IllegalArgumentException("The relation " + field.getFullName() + " needs one foreign key between "
          + descriptor.getFullName() + " and " + target.getFullName() + ", but there are " + found.size() + ".");
    }
    return found.getFirst();
  }

  /** Gives the foreign keys from a table to another, each as its fields and the fields they reference. */
  private static List<List<List<String>>> foreignKeys(Descriptor from, Descriptor to) {
    List<List<List<String>>> keys = new ArrayList<>();
    for (FieldDescriptor field : from.getFields()) {
      ForeignKeyOptions key = field.getOptions().getExtension(OptionsProto.column).getForeignKey();
      if (key.getMessage().equals(to.getFullName())) {
        keys.add(List.of(List.of(field.getName()), key.getField().isEmpty() ? primaryKey(to)
            : List.of(key.getField())));
      }
    }
    for (TableForeignKeyOptions key : from.getOptions().getExtension(OptionsProto.table).getForeignKeyList()) {
      if (key.getMessage().equals(to.getFullName())) {
        keys.add(List.of(key.getFieldsList(), key.getReferencesList().isEmpty() ? primaryKey(to)
            : key.getReferencesList()));
      }
    }
    return keys;
  }

  private static List<String> primaryKey(Descriptor table) {
    return table.getFields().stream().filter(field -> field.getOptions().getExtension(OptionsProto.column)
        .getPrimaryKey()).map(FieldDescriptor::getName).toList();
  }

  private static boolean isTable(Descriptor message) {
    return message.getOptions().hasExtension(OptionsProto.table);
  }

  private static Map<String, Object> attributes(FieldDescriptor field) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    field.getOptions().getAllFields().forEach((option, value) -> {
      if (option.isExtension()) {
        attributes.put(option.getFullName(), value);
      }
    });
    return attributes;
  }

  /** Gives a name in snake case, as Leadseal names the table of a message: {@code order_line} for {@code OrderLine}. */
  private static String snakeCase(String name) {
    StringBuilder snake = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean boundary = i > 0 && Character.isUpperCase(c) && (Character.isLowerCase(name.charAt(i - 1))
          || i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1)));
      snake.append(boundary ? "_" : "").append(c);
    }
    return snake.toString().toLowerCase(Locale.ROOT);
  }
}

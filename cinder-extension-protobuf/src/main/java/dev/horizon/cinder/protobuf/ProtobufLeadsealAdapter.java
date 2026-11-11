package dev.horizon.cinder.protobuf;

import dev.horizon.cinder.Context;
import dev.horizon.cinder.Field;
import dev.horizon.cinder.Invocation;
import dev.horizon.cinder.Part;
import dev.horizon.leadseal.protobuf.ColumnOptions;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Applies the Leadseal column options of a model to its writes, and nothing else: it decides only by the
 * {@code leadseal.v1.column} option of each field. A {@code generated} column is left out of every insert and update,
 * as an output-only field of AIP-134. A column whose type has a {@code default_*} option or an {@code identity} is
 * left out of an insert whose message does not set its field. The write reads their values back.
 *
 * <p>
 * The protobuf extension gives this part to each model with such columns. An extension of your own that decides by
 * other options gives a part of its own, before or after this one in the order of the extensions.
 */
public final class ProtobufLeadsealAdapter implements Part {

  private final Set<String> generatedFields = new LinkedHashSet<>();
  private final Map<String, ProtobufField<?>> fieldsWithDefaults = new LinkedHashMap<>();

  /**
   * Reads the Leadseal column options of the fields of a model.
   *
   * @param model the model of a message with the {@code leadseal.v1.table} option
   */
  public ProtobufLeadsealAdapter(ProtobufModel<?> model) {
    for (Field<?, ?> field : model.fields()) {
      ProtobufField<?> column = (ProtobufField<?>) field;
      if (isGenerated(column.options())) {
        generatedFields.add(column.name());
      } else if (hasDefault(column.options())) {
        fieldsWithDefaults.put(column.name(), column);
      }
    }
  }

  /**
   * Tells whether the database computes the value of a column from the other columns of its row.
   *
   * @param column the column options of a field
   * @return true if the column has the {@code generated} option
   */
  public static boolean isGenerated(ColumnOptions column) {
    return !column.getGenerated().isEmpty();
  }

  /**
   * Tells whether the database fills a column that an insert leaves out, with its default or its identity.
   *
   * @param column the column options of a field
   * @return true if the type of the column has a default value, a default of the current time or date, a random
   * UUID, or an identity
   */
  public static boolean hasDefault(ColumnOptions column) {
    return switch (column.getTypeCase()) {
      case BOOLEAN -> column.getBoolean().hasDefaultValue();
      case INTEGER -> column.getInteger().hasDefaultValue() || column.getInteger().getIdentity();
      case NUMBER -> column.getNumber().hasDefaultValue();
      case DECIMAL -> column.getDecimal().hasDefaultValue();
      case TEXT -> column.getText().hasDefaultValue();
      case BYTES -> column.getBytes().hasDefaultValue();
      case UUID -> column.getUuid().hasDefaultValue() || column.getUuid().getDefaultRandom();
      case TIMESTAMP -> column.getTimestamp().hasDefaultValue() || column.getTimestamp().getDefaultNow();
      case DATE -> column.getDate().hasDefaultValue() || column.getDate().getDefaultToday();
      case TIME -> column.getTime().hasDefaultValue();
      case JSON -> column.getJson().hasDefaultValue();
      case TYPE_NOT_SET -> false;
    };
  }

  /**
   * Tells whether the model has no column that the database fills.
   *
   * @return true if the part changes no write of the model
   */
  public boolean isEmpty() {
    return generatedFields.isEmpty() && fieldsWithDefaults.isEmpty();
  }

  @Override
  public Map<String, Object> write(Map<String, Object> row, Context<?> context) {
    Map<String, Object> fields = new LinkedHashMap<>(row);
    fields.keySet().removeAll(generatedFields);
    if (context.kind() == Invocation.Kind.INSERT) {
      fieldsWithDefaults.forEach((name, field) -> {
        if (fields.containsKey(name) && field.unset(fields.get(name))) {
          fields.remove(name);
        }
      });
    }
    return fields;
  }

}

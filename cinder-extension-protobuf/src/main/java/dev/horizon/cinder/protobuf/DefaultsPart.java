package dev.horizon.cinder.protobuf;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import dev.horizon.cinder.Assignments;
import dev.horizon.cinder.Call;
import dev.horizon.cinder.Context;
import dev.horizon.cinder.Part;
import dev.horizon.leadseal.protobuf.ColumnOptions;
import java.util.Map;

/**
 * Leaves the fields that the database fills out of the row of an insert, when the message does not set them: the
 * fields whose columns have a Leadseal default, such as {@code default_now}, and the identity columns. The insert reads
 * their values back.
 *
 * @param fields the fields, by name
 */
record DefaultsPart(Map<String, MessageField<?>> fields) implements Part {

  private static final String DEFAULT_VALUE = "default_value";
  private static final String DEFAULT = "default_";
  private static final String IDENTITY = "identity";

  static boolean filledByDatabase(ColumnOptions column) {
    if (column.getTypeCase() == ColumnOptions.TypeCase.TYPE_NOT_SET) {
      return false;
    }
    Message type = (Message) column.getField(ColumnOptions.getDescriptor().findFieldByNumber(column.getTypeCase()
        .getNumber()));
    for (FieldDescriptor option : type.getDescriptorForType().getFields()) {
      if (option.getName().equals(DEFAULT_VALUE) && type.hasField(option)) {
        return true;
      }
      if (option.getJavaType() == FieldDescriptor.JavaType.BOOLEAN && (option.getName().startsWith(DEFAULT)
          || option.getName().equals(IDENTITY)) && (Boolean) type.getField(option)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Assignments write(Assignments row, Context<?> context) {
    if (context.kind() != Call.Kind.INSERT) {
      return row;
    }
    return new Assignments(row.changes().stream().filter(change -> !(change instanceof Assignments.SetValue set
        && fields.containsKey(set.field()) && fields.get(set.field()).unset(set.value()))).toList());
  }
}

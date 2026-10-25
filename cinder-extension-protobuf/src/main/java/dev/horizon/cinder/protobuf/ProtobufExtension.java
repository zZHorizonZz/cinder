package dev.horizon.cinder.protobuf;

import com.google.protobuf.Message;
import dev.horizon.cinder.Extension;
import dev.horizon.cinder.Field;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.ModelSource;
import dev.horizon.cinder.Part;
import dev.horizon.cinder.TypeCodec;
import dev.horizon.leadseal.protobuf.OptionsProto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The extension of protobuf messages. A message with the {@code leadseal.v1.table} option is a table, as Leadseal
 * makes its migrations:
 *
 * <ul>
 * <li>each field is a column, as the {@code leadseal.v1.column} options map it, except the ignored ones.</li>
 * <li>an ignored field whose type is another table is a relation, joined by the foreign key between the two
 * tables.</li>
 * <li>each custom option of a field is an {@link dev.horizon.cinder.Attribute} of its full name, such as
 * {@code obfuscation.v1.obfuscated}.</li>
 * </ul>
 *
 * Its codec turns the field types that a column does not hold as they are into values that it does:
 * {@code Timestamp} into times, wrapper types into their values, enums into their names or numbers, and repeated
 * fields, maps and other messages into JSON.
 */
public final class ProtobufExtension implements Extension, ModelSource {

  private final ProtobufCodec codec = new ProtobufCodec();

  @Override
  public List<ModelSource> models() {
    return List.of(this);
  }

  /**
   * Gives the part of the inserts of a message with Leadseal column defaults, such as {@code default_now} or
   * {@code default_random}, or with identity columns. It leaves each such field that the message does not set out of
   * the row, for the database to fill, and the insert reads the value back.
   *
   * @param model the model
   * @return the part, or none if no column of the model has a default
   */
  @Override
  public List<Part> parts(Model<?> model) {
    if (!(model instanceof MessageModel<?> message)) {
      return List.of();
    }
    Map<String, MessageField<?>> filled = new LinkedHashMap<>();
    for (Field<?, ?> field : message.fields()) {
      if (field instanceof MessageField<?> mapped && DefaultsPart.filledByDatabase(mapped.options())) {
        filled.put(mapped.name(), mapped);
      }
    }
    return filled.isEmpty() ? List.of() : List.of(new DefaultsPart(Map.copyOf(filled)));
  }

  @Override
  public List<TypeCodec> codecs() {
    return List.of(codec);
  }

  /**
   * Gives the model of a message's type.
   *
   * @param prototype a message of the type, such as {@code Order.getDefaultInstance()}
   * @param <E> the type
   * @return a {@link ProtobufModel}, or empty if the entity is not a message with the {@code leadseal.v1.table} option
   * @throws IllegalArgumentException if the message has no primary key, or a relation has no foreign key to join it
   */
  @Override
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public <E> Optional<Model<E>> model(E prototype) {
    if (!(prototype instanceof Message message)
        || !message.getDescriptorForType().getOptions().hasExtension(OptionsProto.table)) {
      return Optional.empty();
    }
    Message empty = message.getDefaultInstanceForType();
    return Optional.of((Model<E>) new MessageModel(empty.getClass(), empty));
  }
}

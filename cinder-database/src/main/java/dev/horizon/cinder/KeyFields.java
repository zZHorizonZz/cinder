package dev.horizon.cinder;

import dev.horizon.basepoint.expression.Expression;
import dev.horizon.basepoint.filter.Filter;
import io.vertx.sqlclient.Row;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * The fields whose values pick out one row of a table: the key of its model, or the target of a conflict.
 */
final class KeyFields {

  private final List<Binding> fields;

  KeyFields(List<Binding> fields) {
    this.fields = List.copyOf(fields);
  }

  List<Binding> fields() {
    return fields;
  }

  /**
   * Gives the columns of the fields, with the types that the driver reads their values as.
   */
  List<Driver.Key> columns() {
    return fields.stream().map(field -> new Driver.Key(field.column(), field.stored())).toList();
  }

  /**
   * Gives the key of a row, as the database holds its values.
   */
  RowKey of(Row row) {
    List<Object> values = new ArrayList<>();
    for (Binding field : fields) {
      values.add(row.getValue(field.column()));
    }
    return RowKey.of(values);
  }

  /**
   * Gives the key of an entity, as its fields hold the values.
   */
  RowKey of(Object entity) {
    List<Object> values = new ArrayList<>();
    for (Binding field : fields) {
      values.add(field.get(entity));
    }
    return RowKey.of(values);
  }

  /**
   * Gives the filter of the rows of keys that the database gave, by their columns, without the codecs of the fields.
   */
  Filter filterColumns(Collection<RowKey> keys) {
    return Filter.or(keys.stream().map(key -> match(key, field -> Expression.column(field.column())))
        .toArray(Filter[]::new));
  }

  /**
   * Gives the filter of the rows of keys of entities, by their fields, for the mapping to send through the codecs of
   * the fields.
   */
  Filter filterFields(Collection<RowKey> keys) {
    return Filter.or(keys.stream().map(key -> match(key, field -> Expression.column(field.name())))
        .toArray(Filter[]::new));
  }

  private Filter match(RowKey key, Function<Binding, Expression> operand) {
    List<Filter> terms = new ArrayList<>();
    for (int i = 0; i < fields.size(); i++) {
      terms.add(Filter.eq(operand.apply(fields.get(i)), key.get(i)));
    }
    return Filter.and(terms.toArray(Filter[]::new));
  }
}

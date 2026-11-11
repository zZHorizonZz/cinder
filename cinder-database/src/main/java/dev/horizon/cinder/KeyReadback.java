package dev.horizon.cinder;

import dev.horizon.basepoint.LockMode;
import dev.horizon.basepoint.filter.Filter;
import dev.horizon.basepoint.statement.Statement;
import dev.horizon.basepoint.statement.Where;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The rows of a write read again by their keys, for a database whose write does not give them as its triggers leave
 * them: an insert reads the rows of the keys that it gives, and an update and a delete lock the rows of their filter
 * first, then write the rows of those keys.
 */
final class KeyReadback implements Readback {

  @Override
  public boolean needsTransaction() {
    return true;
  }

  @Override
  public <E> Future<List<E>> insert(TableInsert<E> insert) {
    if (insert.hasConflict()) {
      return insert.run().compose(count -> insert.readTargets());
    }
    return insert.runKeys().compose(keys -> read(insert.execution(), keys));
  }

  @Override
  public <E> Future<List<E>> update(TableUpdate<E> update) {
    Execution<E> execution = update.execution();
    Mapping<E> mapping = execution.mapping();
    KeyFields key = mapping.keyFields();
    Statement statement = update.statement();
    Statement lock = mapping.collection().query().columns(key.fields().stream().map(Binding::column)
        .toArray(String[]::new)).where(filterOf(statement)).lock(LockMode.UPDATE).statement();
    return execution.run(lock).compose(rows -> {
      List<RowKey> keys = new ArrayList<>();
      for (Row row : rows) {
        keys.add(key.of(row));
      }
      if (keys.isEmpty()) {
        return Future.succeededFuture(List.<E> of());
      }
      return execution.run(withFilter(statement, key.filterColumns(keys))).compose(count -> read(execution, keys));
    });
  }

  @Override
  public <E> Future<List<E>> delete(TableDelete<E> delete) {
    Execution<E> execution = delete.execution();
    Mapping<E> mapping = execution.mapping();
    KeyFields key = mapping.keyFields();
    Statement statement = delete.statement();
    Statement read = mapping.collection().query().where(filterOf(statement)).lock(LockMode.UPDATE).statement();
    Function<Row, E> reader = mapping.reader(read);
    return execution.run(read).compose(rows -> {
      List<E> entities = new ArrayList<>();
      List<RowKey> keys = new ArrayList<>();
      for (Row row : rows) {
        entities.add(reader.apply(row));
        keys.add(key.of(row));
      }
      if (keys.isEmpty()) {
        return Future.succeededFuture(List.<E> of());
      }
      return execution.run(withFilter(statement, key.filterColumns(keys))).compose(count -> execution.complete(
          entities));
    });
  }

  private static <E> Future<List<E>> read(Execution<E> execution, List<RowKey> keys) {
    if (keys.isEmpty()) {
      return Future.succeededFuture(List.of());
    }
    Mapping<E> mapping = execution.mapping();
    KeyFields key = mapping.keyFields();
    Statement read = mapping.collection().query().where(key.filterColumns(keys)).statement();
    Function<Row, E> reader = mapping.reader(read);
    return execution.run(read).compose(rows -> {
      Map<RowKey, E> byKey = new LinkedHashMap<>();
      for (Row row : rows) {
        byKey.put(key.of(row), reader.apply(row));
      }
      return execution.complete(keys.stream().map(byKey::get).filter(Objects::nonNull).toList());
    });
  }

  private static Filter filterOf(Statement statement) {
    return statement.find(Where.class).map(Where::filter).orElse(Filter.and());
  }

  private static Statement withFilter(Statement statement, Filter filter) {
    return statement.find(Where.class).map(where -> statement.replace(where, new Where(filter)))
        .orElseGet(() -> statement.where(filter));
  }
}

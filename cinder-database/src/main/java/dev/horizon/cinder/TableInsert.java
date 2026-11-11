package dev.horizon.cinder;

import dev.horizon.basepoint.InsertQuery;
import dev.horizon.basepoint.statement.OnConflict;
import dev.horizon.basepoint.statement.Statement;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * An insert of entities into a table, with what to do with an entity whose key a row already has. The parts write the
 * fields of each entity, and the entities whose fields agree insert in one statement.
 *
 * @param <E> the type of the entities
 */
final class TableInsert<E> {

  private final Execution<E> execution;
  private final Mapping<E> mapping;
  private final List<E> entities;
  private final Conflict conflict;
  private final List<List<Map<String, Object>>> groups;

  TableInsert(Execution<E> execution, List<E> entities, Conflict conflict) {
    this.execution = execution;
    this.mapping = execution.mapping();
    this.entities = entities;
    this.conflict = conflict;
    Map<List<String>, List<Map<String, Object>>> byFields = new LinkedHashMap<>();
    for (E entity : entities) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (Binding binding : mapping.bindings()) {
        row.put(binding.name(), binding.get(entity));
      }
      Map<String, Object> fields = execution.write(row);
      byFields.computeIfAbsent(List.copyOf(fields.keySet()), ignored -> new ArrayList<>()).add(fields);
    }
    this.groups = List.copyOf(byFields.values());
  }

  Execution<E> execution() {
    return execution;
  }

  boolean hasConflict() {
    return conflict != null;
  }

  boolean skipsConflicts() {
    return conflict instanceof Conflict.Ignore;
  }

  /**
   * Runs the insert.
   *
   * @return the number of rows that the database changed
   */
  Future<Long> run() {
    Future<Long> count = Future.succeededFuture(0L);
    for (Statement statement : statements(false)) {
      count = count.compose(before -> execution.run(statement).map(rows -> before + rows.rowCount()));
    }
    return count;
  }

  /**
   * Runs the insert with {@code RETURNING}, and reads the rows that it gives.
   */
  Future<List<E>> runReturning() {
    Future<List<E>> found = Future.succeededFuture(new ArrayList<>());
    for (Statement statement : statements(true)) {
      Function<Row, E> reader = mapping.reader(statement);
      found = found.compose(entities -> execution.run(statement).compose(rows -> execution.entities(rows, reader))
          .map(more -> {
            entities.addAll(more);
            return entities;
          }));
    }
    return found;
  }

  /**
   * Runs the insert as the driver gives back its keys, and reads the keys from the last rows of each statement.
   */
  Future<List<RowKey>> runKeys() {
    KeyFields key = mapping.keyFields();
    Future<List<RowKey>> found = Future.succeededFuture(new ArrayList<>());
    for (Statement statement : statements(false)) {
      Statement withKeys = mapping.driver().returningKeys(statement, key.columns());
      found = found.compose(keys -> execution.run(withKeys).map(rows -> {
        for (Row row : lastRows(rows)) {
          keys.add(key.of(row));
        }
        return keys;
      }));
    }
    return found;
  }

  /**
   * Reads the rows of the entities by the values of the conflict target, in the order of the entities: those that
   * the insert wrote and those that a conflict kept.
   */
  Future<List<E>> readTargets() {
    KeyFields target = new KeyFields(conflict.target().isEmpty() ? mapping.key()
        : conflict.target().stream().map(mapping::binding).toList());
    List<RowKey> keys = new ArrayList<>();
    for (int i = 0; i < entities.size(); i++) {
      RowKey key = target.of(entities.get(i));
      int missing = key.values().indexOf(null);
      if (missing >= 0) {
        throw new IllegalArgumentException("An insert with a conflict reads its rows back by "
            + target.fields().get(missing).name() + ", which the entity at " + i + " does not have.");
      }
      keys.add(key);
    }
    Statement read = mapping.rewrite(mapping.expand(mapping.collection().query().where(target.filterFields(keys))
        .statement()));
    Function<Row, E> reader = mapping.reader(read);
    return execution.run(read).compose(rows -> {
      Map<RowKey, E> byKey = new LinkedHashMap<>();
      for (Row row : rows) {
        E entity = reader.apply(row);
        byKey.put(target.of(entity), entity);
      }
      return execution.complete(keys.stream().map(byKey::get).filter(Objects::nonNull).toList());
    });
  }

  private List<Statement> statements(boolean returning) {
    return groups.stream().map(rows -> mapping.collection().insert(rows))
        .map(insert -> execution.shape(onConflict(returning ? insert.returning(mapping.fields()) : insert)))
        .toList();
  }

  private Statement onConflict(InsertQuery insert) {
    if (conflict == null) {
      return insert.statement();
    }
    String[] target = (conflict.target().isEmpty() ? mapping.key().stream().map(Binding::name)
        : conflict.target().stream()).toArray(String[]::new);
    return switch (conflict) {
      case Conflict.Ignore ignore -> insert.ignore(target).statement();
      case Conflict.Merge merge -> {
        Statement upsert = insert.replace(target).statement();
        if (merge.fields().isEmpty()) {
          yield upsert;
        }
        OnConflict on = upsert.find(OnConflict.class).orElseThrow();
        yield upsert.replace(on, new OnConflict(on.key(), merge.fields()));
      }
    };
  }

  private static RowSet<Row> lastRows(RowSet<Row> rows) {
    RowSet<Row> last = rows;
    for (RowSet<Row> next = rows; next != null; next = next.next()) {
      if (next.columnsNames() != null && !next.columnsNames().isEmpty()) {
        last = next;
      }
    }
    return last;
  }
}

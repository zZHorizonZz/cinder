package dev.horizon.cinder;

import dev.horizon.aquery.aip132.InvalidOrderByException;
import dev.horizon.aquery.aip132.Order;
import dev.horizon.aquery.aip157.ReadMask;
import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.aquery.aip158.PageToken;
import dev.horizon.aquery.aip160.InvalidFilterException;
import dev.horizon.basepoint.DeleteQuery;
import dev.horizon.basepoint.Direction;
import dev.horizon.basepoint.InsertQuery;
import dev.horizon.basepoint.LockMode;
import dev.horizon.basepoint.Query;
import dev.horizon.basepoint.RenderedStatement;
import dev.horizon.basepoint.UpdateQuery;
import dev.horizon.basepoint.expression.Column;
import dev.horizon.basepoint.expression.Expression;
import dev.horizon.basepoint.expression.Literal;
import dev.horizon.basepoint.expression.Null;
import dev.horizon.basepoint.expression.Reference;
import dev.horizon.basepoint.expression.Value;
import dev.horizon.basepoint.filter.Filter;
import dev.horizon.basepoint.statement.Assignment;
import dev.horizon.basepoint.statement.Assignments;
import dev.horizon.basepoint.statement.Offset;
import dev.horizon.basepoint.statement.OnConflict;
import dev.horizon.basepoint.statement.OrderBy;
import dev.horizon.basepoint.statement.Sort;
import dev.horizon.basepoint.statement.Statement;
import dev.horizon.basepoint.statement.Where;
import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;

/**
 * The rows of a model. A table gives Basepoint's builders for its rows, in which a caller names the fields of the
 * model, and runs them: it maps the fields to their columns, sends each value through the codecs of its field, runs
 * the parts of the model, and reads the rows into entities.
 *
 * <pre>{@code
 * Table<Order> orders = database.table(Order.getDefaultInstance());
 *
 * Query open = orders.query().where("state = \"OPEN\"").orderBy("create_time", Direction.DESC).limit(20);
 * Future<List<Order>> found = orders.list(open);
 * Future<Long> cancelled = orders.execute(orders.update().set("state", "CANCELLED").where(eq("id", 42L)));
 * }</pre>
 *
 * <p>
 * Every method that gives a future gives the failure of a statement that does not fit the model, such as one of
 * another table or one that names a field that the model does not have, as its failure.
 *
 * @param <E> the type of the entities
 */
public final class Table<E> {

  private static final int DEFAULT_FETCH = 100;
  private static final String PRESENT = "present";

  private final Database database;
  private final Mapping<E> mapping;
  private final List<Part> parts;

  Table(Database database, Mapping<E> mapping, List<Part> parts) {
    this.database = database;
    this.mapping = mapping;
    this.parts = List.copyOf(parts);
  }

  /**
   * Gives the model of the table.
   *
   * @return the model
   */
  public Model<E> model() {
    return mapping.model();
  }

  /**
   * Gives this table with one more part, which the operations of the table that it gives run after the parts of the
   * extensions.
   *
   * @param part the part
   * @return the table with the part
   */
  public Table<E> with(Part part) {
    List<Part> withPart = new ArrayList<>(parts);
    withPart.add(part);
    return new Table<>(database, mapping, withPart);
  }

  /**
   * Starts a query of the rows of the table. A query that names no fields reads every field of the model.
   *
   * @return the query
   */
  public Query query() {
    return mapping.collection().query();
  }

  /**
   * Starts a query of the fields of an AIP-157 read mask.
   *
   * @param mask the mask, such as {@code ReadMask.parse("id,state")}. A mask of every field reads every field.
   * @return the query of the fields of the mask
   * @throws IllegalArgumentException if the mask names a field that the model does not have, or a path in a field
   */
  public Query query(ReadMask mask) {
    if (mask.allFields()) {
      return query();
    }
    return query().columns(mask.paths().stream().map(path -> mapping.binding(path, "A read mask").name())
        .toArray(String[]::new));
  }

  /**
   * Starts a query from the AIP-160 filter and the AIP-132 order of a request, such as those of a List method. The
   * filter and the order name fields of the model, which the query checks at once.
   *
   * @param filter the filter, such as {@code state = "OPEN"}, or empty for every row
   * @param orderBy the order, such as {@code create_time desc, id}, or empty for no order
   * @return the query, which reads every field
   * @throws InvalidFilterException if the filter is not AIP-160 text, or names a field that the model does not have
   * @throws InvalidOrderByException if the order is not AIP-132 text, or names a field that the model does not have
   */
  public Query query(String filter, String orderBy) {
    Query query = filter.isBlank() ? query() : query().where(requestFilter(filter));
    for (Sort sort : parse(orderBy)) {
      query = query.orderBy(sort.value(), sort.direction());
    }
    return query;
  }

  /**
   * Starts a query of the related rows of a relation, for {@code include} or {@code includeOne}:
   *
   * <pre>{@code
   * orders.list(orders.query().include("items", orders.related("items").orderBy("position").limit(20)));
   * }</pre>
   *
   * @param relation the name of the relation, which is the name to include it under
   * @return the query of the rows of the related model that the relation joins to each row of this table
   * @throws IllegalArgumentException if the model has no such relation
   */
  public Query related(String relation) {
    Relation<?> found = model().relation(relation).orElseThrow(() -> new IllegalArgumentException("The model of"
        + " the table " + model().table() + " has no relation " + relation + "."));
    Query rows = database.mapping(found.target()).collection().query();
    for (int i = 0; i < found.fields().size(); i++) {
      rows = rows.on(found.references().get(i), found.fields().get(i));
    }
    return rows;
  }

  /**
   * Starts an update of the rows of the table.
   *
   * @return the update
   */
  public UpdateQuery update() {
    return mapping.collection().update();
  }

  /**
   * Starts a delete of the rows of the table.
   *
   * @return the delete
   */
  public DeleteQuery delete() {
    return mapping.collection().delete();
  }

  /**
   * Reads the entities of a query.
   *
   * @param query the query of this table
   * @return the entities, with the related rows of its includes
   */
  public Future<List<E>> list(Query query) {
    return Database.attempt(() -> {
      Execution<E> execution = execution(Invocation.Kind.QUERY);
      Statement statement = execution.shape(mapping.expand(query.statement()));
      Function<Row, E> reader = mapping.reader(statement);
      return execution.run(statement).compose(rows -> execution.entities(rows, reader));
    });
  }

  /**
   * Reads the first entity of a query.
   *
   * @param query the query of this table
   * @return the first entity, or nothing if the query has no rows
   */
  public Future<Optional<E>> first(Query query) {
    return list(query.limit(1)).map(entities -> entities.stream().findFirst());
  }

  /**
   * Reads the one entity of a query.
   *
   * @param query the query of this table
   * @return the entity. The future fails with a {@link NoSuchElementException} if the query has no rows, and with an
   * {@link IllegalStateException} if it has more than one.
   */
  public Future<E> single(Query query) {
    return list(query.limit(2)).compose(entities -> switch (entities.size()) {
      case 0 -> Future.failedFuture(new NoSuchElementException("The query of the table " + model().table()
          + " has no row."));
      case 1 -> Future.succeededFuture(entities.getFirst());
      default -> Future.failedFuture(new IllegalStateException("The query of the table " + model().table()
          + " has more than one row."));
    });
  }

  /**
   * Reads the entity of a key.
   *
   * @param key the value of the one key field of the model
   * @return the entity, or nothing if no row has the key
   */
  public Future<Optional<E>> find(Object key) {
    return Database.attempt(() -> first(query().where(model().keyFilter(key))));
  }

  /**
   * Counts the rows that the filter of a query keeps.
   *
   * @param query the query of this table. Its filter counts; its order, limit and offset do not.
   * @return the number of rows
   */
  public Future<Long> count(Query query) {
    return Database.attempt(() -> {
      Execution<E> execution = execution(Invocation.Kind.QUERY);
      Statement statement = execution.shape(whereOf(query).count().statement());
      return execution.run(statement).map(rows -> ((Number) rows.iterator().next().getValue(0)).longValue());
    });
  }

  /**
   * Tells whether the filter of a query keeps a row.
   *
   * @param query the query of this table. Its filter counts; its order, limit and offset do not.
   * @return whether a row exists
   */
  public Future<Boolean> exists(Query query) {
    return Database.attempt(() -> {
      Execution<E> execution = execution(Invocation.Kind.QUERY);
      Statement statement = execution.shape(whereOf(query).select(PRESENT, new Literal(1)).limit(1).statement());
      return execution.run(statement).map(rows -> rows.size() > 0);
    });
  }

  /**
   * Reads a page of a query, as AIP-158 asks. The filter and the order of the request narrow and order the query,
   * the key of the model ends the order, and the token of the request starts the page after the row of the page
   * before it.
   *
   * @param query the query of this table, which orders by fields and has no offset
   * @param request the page size, the token, and the AIP-160 filter and AIP-132 order of a request
   * @return the page, with the token of the next page, or an empty token after the last page. The future fails with
   * an {@code InvalidFilterException} or an {@code InvalidOrderByException} for a filter or an order of the request
   * that names a field that the model does not have.
   */
  public Future<Page<E>> page(Query query, PageRequest request) {
    return Database.attempt(() -> {
      if (query.statement().has(Offset.class)) {
        throw new IllegalArgumentException("A page starts after the row of its token, so its query has no offset.");
      }
      String fingerprint = fingerprint(query, request);
      Query pageQuery = query;
      if (!request.filter().isEmpty()) {
        pageQuery = pageQuery.where(requestFilter(request.filter()));
      }
      List<Sort> sorts = new ArrayList<>(pageQuery.statement().find(OrderBy.class).map(OrderBy::sorts)
          .orElse(List.of()).stream().map(sort -> new Sort(new Column(field(sort).name()), sort.direction()))
          .toList());
      List<Sort> requestSorts = new ArrayList<>(parse(request.orderBy()));
      for (Binding key : mapping.key()) {
        if (sorts.stream().noneMatch(sort -> field(sort) == key)
            && requestSorts.stream().noneMatch(sort -> field(sort) == key)) {
          requestSorts.add(new Sort(new Column(key.name()), Direction.ASC));
        }
      }
      for (Sort sort : requestSorts) {
        pageQuery = pageQuery.orderBy(sort.value(), sort.direction());
      }
      sorts.addAll(requestSorts);
      Optional<PageToken> token = database.tokens().decode(request.pageToken(), fingerprint);
      int size = Math.min(request.pageSize(), database.maxPageSize());
      pageQuery = pageQuery.limit((long) size + 1);
      if (token.isPresent()) {
        pageQuery = pageQuery.where(after(sorts, token.get().cursor()));
      }
      return list(pageQuery).map(entities -> {
        if (entities.size() <= size) {
          return new Page<>(entities, "", 0);
        }
        List<E> items = entities.subList(0, size);
        return new Page<>(items, database.tokens().encode(new PageToken(cursor(sorts, items.getLast()),
            fingerprint)), 0);
      });
    });
  }

  /**
   * Streams the entities of a query, a hundred rows at a time.
   *
   * @param query the query of this table
   * @return the stream of the entities
   * @see #stream(Query, int)
   */
  public ReadStream<E> stream(Query query) {
    return stream(query, DEFAULT_FETCH);
  }

  /**
   * Streams the entities of a query, with a cursor that fetches some rows at a time. The stream runs in the
   * transaction of the current context, or in a read-only transaction of its own, which ends with the stream.
   *
   * @param query the query of this table
   * @param fetch the rows that the cursor fetches at a time, at least one
   * @return the stream of the entities
   * @throws IllegalArgumentException if the query does not fit the model, or the fetch is less than one
   */
  public ReadStream<E> stream(Query query, int fetch) {
    Execution<E> execution = execution(Invocation.Kind.QUERY);
    Statement statement = execution.shape(mapping.expand(query.statement()));
    return new EntityStream<>(execution.stream(statement, fetch), execution, mapping.reader(statement), fetch);
  }

  /**
   * Runs a query for its rows, as the database gives them, such as for an aggregate:
   *
   * <pre>{@code
   * orders.rows(orders.query().select("state", column("state")).select("total", sum(column("total_cents")))
   *     .groupBy("state"));
   * }</pre>
   *
   * @param query the query of this table
   * @return the rows, with the columns that the query selects
   */
  public Future<RowSet<Row>> rows(Query query) {
    return Database.attempt(() -> {
      Execution<E> execution = execution(Invocation.Kind.QUERY);
      return execution.run(execution.shape(query.statement()));
    });
  }

  /**
   * Inserts an entity.
   *
   * @param entity the entity
   * @return the entity as the table holds it, with the values that the database gave its columns
   */
  public Future<E> insert(E entity) {
    return insert(List.of(entity)).map(List::getFirst);
  }

  /**
   * Inserts entities.
   *
   * @param entities the entities
   * @return the entities as the table holds them
   */
  public Future<List<E>> insert(List<E> entities) {
    return insert(entities, null);
  }

  /**
   * Inserts entities, and skips or merges those whose key a row already has. Entities that write different fields,
   * because the parts leave some to the database, insert in a statement each, in one transaction.
   *
   * @param entities the entities
   * @param conflict what to do with an entity whose key a row already has, or {@code null} to fail with a
   * {@link DuplicateKeyException}
   * @return the entities that the table inserted or merged, as it holds them. MariaDB skips a row by setting its key
   * to itself, so there the entities also hold the rows that {@link Conflict#ignore()} kept, unchanged.
   */
  public Future<List<E>> insert(List<E> entities, Conflict conflict) {
    if (entities.isEmpty()) {
      return Future.succeededFuture(List.of());
    }
    return Database.attempt(() -> {
      Execution<E> execution = execution(Invocation.Kind.INSERT);
      List<Statement> statements = insertStatements(execution, entities, conflict, true);
      return statements.size() > 1 && database.current() == null
          ? database.transaction(transaction -> runReturning(execution, statements))
          : runReturning(execution, statements);
    });
  }

  /**
   * Inserts entities without reading them back, and skips or merges those whose key a row already has.
   *
   * @param entities the entities
   * @param conflict what to do with an entity whose key a row already has, or {@code null} to fail with a
   * {@link DuplicateKeyException}
   * @return the number of rows that the inserts changed, as the database counts them. MariaDB counts a merged row
   * twice, and a row that {@link Conflict#ignore()} kept not at all.
   */
  public Future<Long> execute(List<E> entities, Conflict conflict) {
    if (entities.isEmpty()) {
      return Future.succeededFuture(0L);
    }
    return Database.attempt(() -> {
      Execution<E> execution = execution(Invocation.Kind.INSERT);
      List<Statement> statements = insertStatements(execution, entities, conflict, false);
      return statements.size() > 1 && database.current() == null
          ? database.transaction(transaction -> runCounting(execution, statements))
          : runCounting(execution, statements);
    });
  }

  /**
   * Runs an update.
   *
   * @param update the update of this table
   * @return the number of rows that it updated
   */
  public Future<Long> execute(UpdateQuery update) {
    return Database.attempt(() -> {
      Execution<E> execution = execution(Invocation.Kind.UPDATE);
      return execution.run(execution.shape(setFields(update.statement(), execution))).map(rows -> (long) rows
          .rowCount());
    });
  }

  /**
   * Runs an update, and reads the rows that it updated. On a database that cannot return the rows of an update, the
   * table locks the rows of the filter, updates them by key and reads them, in one transaction.
   *
   * @param update the update of this table
   * @return the updated entities, as the table holds them after the update
   */
  public Future<List<E>> returning(UpdateQuery update) {
    return Database.attempt(() -> {
      Execution<E> execution = execution(Invocation.Kind.UPDATE);
      if (!mapping.driver().returnsUpdatedRows()) {
        return reread(execution, execution.shape(setFields(update.statement(), execution)));
      }
      Statement statement = execution.shape(setFields(update.returning(mapping.fields()).statement(), execution));
      Function<Row, E> reader = mapping.reader(statement);
      return execution.run(statement).compose(rows -> execution.entities(rows, reader));
    });
  }

  /**
   * Updates the fields of an AIP-134 update mask of the row of an entity's key.
   *
   * @param entity the entity, with the key of the row and the values of the fields
   * @param updateMask the fields to update, such as {@code "state,total_cents"}, or {@code "*"} for every field but
   * the key
   * @return the entity as the table holds it after the update, or nothing if no row has its key. The future fails if
   * the mask names a key field, or a field that the model does not have.
   */
  public Future<Optional<E>> update(E entity, String updateMask) {
    return Database.attempt(() -> {
      ReadMask mask = ReadMask.parse(updateMask);
      List<Binding> fields = mask.allFields() ? mapping.bindings().stream().filter(binding -> !binding.field().key())
          .toList() : mask.paths().stream().map(path -> {
            Binding binding = mapping.binding(path, "An update mask");
            if (binding.field().key()) {
              throw new IllegalArgumentException("An update cannot change the key field " + binding.name() + ".");
            }
            return binding;
          }).toList();
      UpdateQuery update = update();
      for (Binding binding : fields) {
        update = update.set(binding.name(), binding.get(entity));
      }
      return returning(update.where(model().keyFilterOf(entity))).map(updated -> updated.stream().findFirst());
    });
  }

  /**
   * Runs a delete.
   *
   * @param delete the delete of this table
   * @return the number of rows that it deleted
   */
  public Future<Long> execute(DeleteQuery delete) {
    return Database.attempt(() -> {
      Execution<E> execution = execution(Invocation.Kind.DELETE);
      return execution.run(execution.shape(delete.statement())).map(rows -> (long) rows.rowCount());
    });
  }

  /**
   * Runs a delete, and reads the rows that it deleted.
   *
   * @param delete the delete of this table
   * @return the deleted entities
   */
  public Future<List<E>> returning(DeleteQuery delete) {
    return Database.attempt(() -> {
      Execution<E> execution = execution(Invocation.Kind.DELETE);
      Statement statement = execution.shape(delete.returning(mapping.fields()).statement());
      Function<Row, E> reader = mapping.reader(statement);
      return execution.run(statement).compose(rows -> execution.entities(rows, reader));
    });
  }

  private Execution<E> execution(Invocation.Kind kind) {
    return new Execution<>(database, mapping, kind, parts);
  }

  private Query whereOf(Query query) {
    Statement statement = query.statement();
    mapping.requireTable(statement);
    return statement.find(Where.class).map(where -> query().where(where.filter())).orElseGet(this::query);
  }

  private List<Statement> insertStatements(Execution<E> execution, List<E> entities, Conflict conflict, boolean returning) {
    Map<List<String>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
    for (E entity : entities) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (Binding binding : mapping.bindings()) {
        row.put(binding.name(), binding.get(entity));
      }
      Map<String, Object> fields = execution.write(row);
      groups.computeIfAbsent(List.copyOf(fields.keySet()), ignored -> new ArrayList<>()).add(fields);
    }
    return groups.values().stream().map(rows -> mapping.collection().insert(rows))
        .map(insert -> execution.shape(onConflict(returning ? insert.returning(mapping.fields()) : insert, conflict)))
        .toList();
  }

  private Future<Long> runCounting(Execution<E> execution, List<Statement> statements) {
    Future<Long> done = Future.succeededFuture(0L);
    for (Statement statement : statements) {
      done = done.compose(count -> execution.run(statement).map(rows -> count + rows.rowCount()));
    }
    return done;
  }

  private Statement onConflict(InsertQuery insert, Conflict conflict) {
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

  private Future<List<E>> runReturning(Execution<E> execution, List<Statement> statements) {
    Future<List<E>> done = Future.succeededFuture(new ArrayList<>());
    for (Statement statement : statements) {
      Function<Row, E> reader = mapping.reader(statement);
      done = done.compose(entities -> execution.run(statement).compose(rows -> execution.entities(rows, reader))
          .map(read -> {
            entities.addAll(read);
            return entities;
          }));
    }
    return done;
  }

  private Statement setFields(Statement statement, Execution<E> execution) {
    Optional<Assignments> found = statement.find(Assignments.class);
    if (found.isEmpty()) {
      return statement;
    }
    Map<String, Object> row = new LinkedHashMap<>();
    for (Assignment assignment : found.get().assignments()) {
      row.put(assignment.column(), switch (assignment.value()) {
        case Value value -> value.value();
        case Null ignored -> null;
        default -> assignment.value();
      });
    }
    List<Assignment> assignments = new ArrayList<>();
    execution.write(row).forEach((field, value) -> assignments.add(new Assignment(field, Expression.value(value))));
    return statement.replace(found.get(), new Assignments(assignments));
  }

  private Future<List<E>> reread(Execution<E> execution, Statement update) {
    Filter where = update.find(Where.class).map(Where::filter).orElse(Filter.and());
    String[] keys = mapping.key().stream().map(Binding::column).toArray(String[]::new);
    Statement lockKeys = query().columns(keys).where(where).lock(LockMode.UPDATE).statement();
    return database.transaction(transaction -> execution.run(lockKeys).compose(found -> {
      if (found.size() == 0) {
        return Future.succeededFuture(List.<E> of());
      }
      Filter byKeys = keys(found);
      Statement byKey = update.find(Where.class).map(old -> update.replace(old, new Where(byKeys)))
          .orElseGet(() -> update.where(byKeys));
      Statement read = query().where(byKeys).statement();
      return execution.run(byKey).compose(updated -> execution.run(read)).compose(rows -> execution.entities(rows,
          mapping.reader(read)));
    }));
  }

  private Filter keys(Iterable<Row> rows) {
    List<Filter> found = new ArrayList<>();
    for (Row row : rows) {
      List<Filter> terms = new ArrayList<>();
      for (int i = 0; i < mapping.key().size(); i++) {
        terms.add(Filter.eq(Expression.column(mapping.key().get(i).column()), row.getValue(i)));
      }
      found.add(Filter.and(terms.toArray(Filter[]::new)));
    }
    return Filter.or(found.toArray(Filter[]::new));
  }

  private Filter requestFilter(String text) {
    Filter filter = Filter.of(text);
    try {
      mapping.check(filter);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidFilterException("%s", unknown.getMessage());
    }
    return filter;
  }

  private List<Sort> parse(String orderBy) {
    if (orderBy.isBlank()) {
      return List.of();
    }
    List<Sort> sorts = new ArrayList<>();
    for (var term : Order.parse(orderBy)) {
      Binding binding;
      try {
        binding = mapping.binding(term.fieldPath(), "An order");
      } catch (IllegalArgumentException unknown) {
        throw new InvalidOrderByException("%s", unknown.getMessage());
      }
      sorts.add(new Sort(new Column(binding.name()), term.descending() ? Direction.DESC : Direction.ASC));
    }
    return sorts;
  }

  private Filter after(List<Sort> order, List<String> cursor) {
    if (cursor.size() != order.size()) {
      throw new IllegalArgumentException("The page token has " + cursor.size() + " values for an order of "
          + order.size() + " fields.");
    }
    List<Filter> alternatives = new ArrayList<>();
    for (int i = 0; i < order.size(); i++) {
      List<Filter> terms = new ArrayList<>();
      for (int j = 0; j < i; j++) {
        terms.add(Filter.eq(order.get(j).value(), value(order.get(j), cursor.get(j))));
      }
      Object last = value(order.get(i), cursor.get(i));
      terms.add(order.get(i).direction() == Direction.DESC ? Filter.lt(order.get(i).value(), last)
          : Filter.gt(order.get(i).value(), last));
      alternatives.add(Filter.and(terms.toArray(Filter[]::new)));
    }
    return Filter.or(alternatives.toArray(Filter[]::new));
  }

  private Object value(Sort sort, String text) {
    return field(sort).fromCursor(mapping.driver(), text);
  }

  private List<String> cursor(List<Sort> order, E last) {
    return order.stream().map(sort -> field(sort).cursor(field(sort).get(last))).toList();
  }

  private Binding field(Sort sort) {
    return switch (sort.value()) {
      case Column column -> mapping.binding(column.name());
      case Reference reference -> mapping.binding(reference.name());
      default -> throw new IllegalArgumentException("A page orders by fields, so that its token can hold the values of"
          + " the last row, not by " + sort.value() + ".");
    };
  }

  private String fingerprint(Query query, PageRequest request) {
    RenderedStatement rendered = database.render(mapping.rewrite(mapping.expand(query.statement())));
    return PageToken.fingerprintOf(model().table(), rendered.text(), String.valueOf(rendered.params()),
        request.filter(), request.orderBy());
  }
}

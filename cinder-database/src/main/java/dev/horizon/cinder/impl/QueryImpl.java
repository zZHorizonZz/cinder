package dev.horizon.cinder.impl;

import dev.horizon.aquery.aip132.InvalidOrderByException;
import dev.horizon.aquery.aip132.Order;
import dev.horizon.aquery.aip132.OrderBy;
import dev.horizon.aquery.aip157.ReadMask;
import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.aquery.aip158.PageToken;
import dev.horizon.aquery.aip160.InvalidFilterException;
import dev.horizon.basepoint.Clause;
import dev.horizon.basepoint.Direction;
import dev.horizon.basepoint.Expression;
import dev.horizon.basepoint.Filter;
import dev.horizon.basepoint.Include;
import dev.horizon.basepoint.LockMode;
import dev.horizon.basepoint.Statement;
import dev.horizon.cinder.Call;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.Part;
import dev.horizon.cinder.Query;
import dev.horizon.cinder.Relation;
import dev.horizon.cinder.Sort;
import dev.horizon.cinder.Terminal;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Row;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

final class QueryImpl<E> implements Query<E> {

  private static final int DEFAULT_FETCH = 100;
  private static final String INCLUDE = "cinder_include_";
  private static final String PRESENT = "present";

  /**
   * The related rows of a relation, read into its field.
   *
   * @param relation the relation
   * @param rows the query of the related rows
   */
  private record Included(Relation<?> relation, QueryImpl<?> rows) {
  }

  /**
   * The state of a query. Its sorts name the fields of the model.
   *
   * @param mask the fields to read, or null for every field
   * @param lock the lock of the rows, or null for none
   */
  private record Steps(List<Filter> filters, List<Clause.Sort> sorts, Long limit, Long offset, LockMode lock,
      List<Binding> mask, List<Included> includes, List<Part> parts) {

    static final Steps EMPTY = new Steps(List.of(), List.of(), null, null, null, null, List.of(), List.of());

    Steps {
      filters = List.copyOf(filters);
      sorts = List.copyOf(sorts);
      mask = mask == null ? null : List.copyOf(mask);
      includes = List.copyOf(includes);
      parts = List.copyOf(parts);
    }

    Steps withFilters(List<Filter> more) {
      return new Steps(append(filters, more), sorts, limit, offset, lock, mask, includes, parts);
    }

    Steps withSorts(List<Clause.Sort> more) {
      return new Steps(filters, append(sorts, more), limit, offset, lock, mask, includes, parts);
    }

    Steps withLimit(Long rows) {
      return new Steps(filters, sorts, rows, offset, lock, mask, includes, parts);
    }

    Steps withOffset(Long rows) {
      return new Steps(filters, sorts, limit, rows, lock, mask, includes, parts);
    }

    Steps withLock(LockMode mode) {
      return new Steps(filters, sorts, limit, offset, mode, mask, includes, parts);
    }

    Steps withMask(List<Binding> fields) {
      return new Steps(filters, sorts, limit, offset, lock, fields, includes, parts);
    }

    Steps withInclude(Included included) {
      return new Steps(filters, sorts, limit, offset, lock, mask, append(includes, List.of(included)), parts);
    }

    Steps withPart(Part part) {
      return new Steps(filters, sorts, limit, offset, lock, mask, includes, append(parts, List.of(part)));
    }

    private static <T> List<T> append(List<T> list, List<T> items) {
      List<T> more = new ArrayList<>(list);
      more.addAll(items);
      return more;
    }
  }

  private final CinderDatabase database;
  private final Table<E> table;
  private final Steps steps;

  QueryImpl(CinderDatabase database, Table<E> table) {
    this(database, table, Steps.EMPTY);
  }

  private QueryImpl(CinderDatabase database, Table<E> table, Steps steps) {
    this.database = database;
    this.table = table;
    this.steps = steps;
  }

  @Override
  public Model<E> model() {
    return table.model();
  }

  @Override
  public Query<E> with(Part part) {
    return with(steps.withPart(part));
  }

  @Override
  public <R> R run(Terminal<? super E, R> terminal) {
    return terminal.run(execution(select(steps)));
  }

  @Override
  public Query<E> where(Filter filter) {
    table.check(filter);
    return with(steps.withFilters(List.of(filter)));
  }

  @Override
  public Query<E> where(String filter) {
    Filter parsed = Filter.of(filter);
    try {
      table.check(parsed);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidFilterException("%s", unknown.getMessage());
    }
    return with(steps.withFilters(List.of(parsed)));
  }

  @Override
  public Query<E> sort(Sort sort) {
    return with(steps.withSorts(sort.orders().stream().map(order -> sortOf(order.field(), order.direction()))
        .toList()));
  }

  @Override
  public Query<E> orderBy(String orderBy) {
    return with(steps.withSorts(parse(orderBy)));
  }

  @Override
  public Query<E> limit(long limit) {
    return with(steps.withLimit(limit));
  }

  @Override
  public Query<E> offset(long offset) {
    return with(steps.withOffset(offset));
  }

  @Override
  public Query<E> lock(LockMode mode) {
    return with(steps.withLock(mode));
  }

  @Override
  public Query<E> mask(String readMask) {
    ReadMask mask = ReadMask.parse(readMask);
    return with(steps.withMask(mask.allFields() ? null
        : mask.paths().stream()
            .map(path -> table.binding(path, "A read mask")).toList()));
  }

  @Override
  public Query<E> include(String relation) {
    return include(relation, rows -> rows);
  }

  @Override
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public Query<E> include(String relation, UnaryOperator<Query<?>> narrow) {
    Relation<?> found = table.model().relation(relation).orElseThrow(() -> new IllegalArgumentException("The model"
        + " of the table " + table.model().table() + " has no relation " + relation + "."));
    return include((Relation) found, (UnaryOperator) narrow);
  }

  @Override
  public <T> Query<E> include(Relation<T> relation, UnaryOperator<Query<T>> narrow) {
    Table<T> target = database.table(database.model(relation.target()));
    List<Filter> related = new ArrayList<>();
    for (int i = 0; i < relation.fields().size(); i++) {
      related.add(new Filter.Related(target.binding(relation.references().get(i)).column(),
          table.binding(relation.fields().get(i)).column()));
    }
    Query<T> narrowed = narrow.apply(new QueryImpl<>(database, target, Steps.EMPTY.withFilters(related)));
    if (!(narrowed instanceof QueryImpl<T> rows) || rows.table != target) {
      throw new IllegalArgumentException("The query of the related rows of " + relation.name() + " must narrow the"
          + " query that it gets.");
    }
    QueryImpl<T> included = relation.shape() == Relation.Shape.ONE ? rows.with(rows.steps.withLimit(1L)) : rows;
    return with(steps.withInclude(new Included(relation, included)));
  }

  @Override
  public Future<List<E>> list() {
    return list(steps);
  }

  @Override
  public Future<Optional<E>> first() {
    return list(steps.withLimit(1L)).map(entities -> entities.stream().findFirst());
  }

  @Override
  public Future<E> single() {
    return list(steps.withLimit(2L)).compose(entities -> switch (entities.size()) {
      case 0 -> Future.failedFuture(new NoSuchElementException("The query of the table " + table.model().table()
          + " has no row."));
      case 1 -> Future.succeededFuture(entities.getFirst());
      default -> Future.failedFuture(new IllegalStateException("The query of the table " + table.model().table()
          + " has more than one row."));
    });
  }

  @Override
  public Future<Page<E>> page(PageRequest request) {
    return Future.succeededFuture().compose(ignored -> {
      QueryImpl<E> query = request.filter().isEmpty() ? this : (QueryImpl<E>) where(request.filter());
      List<Clause.Sort> sorts = new ArrayList<>(query.steps.sorts());
      sorts.addAll(parse(request.orderBy()));
      List<Clause.Sort> order = keyset(sorts);
      String fingerprint = fingerprint(request);
      Optional<PageToken> token = database.tokens().decode(request.pageToken(), fingerprint);
      int size = Math.min(request.pageSize(), database.maxPageSize());
      Steps paged = new Steps(query.steps.filters(), order, (long) size + 1, null, steps.lock(),
          steps.mask(), steps.includes(), steps.parts());
      if (token.isPresent()) {
        paged = paged.withFilters(List.of(after(order, token.get().cursor())));
      }
      return list(paged).map(entities -> {
        if (entities.size() <= size) {
          return new Page<>(entities, "", 0);
        }
        List<E> items = entities.subList(0, size);
        return new Page<>(items, database.tokens().encode(new PageToken(cursor(order, items.getLast()),
            fingerprint)), 0);
      });
    });
  }

  @Override
  public Future<Long> count() {
    return Future.succeededFuture().compose(ignored -> {
      ExecutionImpl<E> execution = execution(filtered().count().statement());
      return execution.run(execution.statement()).map(rows -> ((Number) rows.iterator().next().getValue(0))
          .longValue());
    });
  }

  @Override
  public Future<Boolean> exists() {
    return Future.succeededFuture().compose(ignored -> {
      ExecutionImpl<E> execution = execution(filtered().select(PRESENT, new Expression.Literal(1)).limit(1)
          .statement());
      return execution.run(execution.statement()).map(rows -> rows.size() > 0);
    });
  }

  @Override
  public ReadStream<E> stream() {
    return stream(DEFAULT_FETCH);
  }

  @Override
  public ReadStream<E> stream(int fetch) {
    ExecutionImpl<E> execution = execution(select(steps));
    return new EntityStream<>(execution.stream(execution.statement(), fetch), execution, fetch);
  }

  /** Gives a reader of rows as entities, with the related rows of the includes in the fields of their relations. */
  private Function<Row, E> reader() {
    Function<Row, Map<String, Object>> values = table.values();
    return new Function<>() {
      private int[] positions;

      @Override
      public E apply(Row row) {
        Map<String, Object> read = values.apply(row);
        if (positions == null) {
          positions = steps.includes().stream().mapToInt(included -> row.getColumnIndex(INCLUDE + included
              .relation().name())).toArray();
        }
        for (int i = 0; i < positions.length; i++) {
          Included included = steps.includes().get(i);
          read.put(included.relation().name(), related(included, row.getValue(positions[i])));
        }
        return table.model().assemble(read);
      }
    };
  }

  /** Reads an entity of an object of the JSON of an include, with the related rows of its own includes. */
  private E read(JsonObject object) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (Binding binding : table.bindings()) {
      if (object.containsKey(binding.column())) {
        values.put(binding.name(), binding.read(database.driver(), object.getValue(binding.column()), true));
      }
    }
    for (Included included : steps.includes()) {
      values.put(included.relation().name(), related(included, object.getValue(INCLUDE + included.relation()
          .name())));
    }
    return table.model().assemble(values);
  }

  private static Object related(Included included, Object json) {
    Object parsed = switch (json) {
      case null -> null;
      case String text -> Json.decodeValue(text);
      case Buffer buffer -> Json.decodeValue(buffer);
      default -> json;
    };
    boolean many = included.relation().shape() == Relation.Shape.MANY;
    return switch (parsed) {
      case null -> many ? List.of() : null;
      case JsonArray array when many -> array.stream().map(object -> included.rows().read((JsonObject) object))
          .toList();
      case JsonArray array -> array.isEmpty() ? null : included.rows().read(array.getJsonObject(0));
      case JsonObject object when many -> List.of(included.rows().read(object));
      case JsonObject object -> included.rows().read(object);
      default -> throw new IllegalStateException("The include " + included.relation().name() + " gave " + parsed
          + ", not JSON.");
    };
  }

  private Future<List<E>> list(Steps listed) {
    QueryImpl<E> query = with(listed);
    return Future.succeededFuture().compose(ignored -> query.execution(query.select(listed)).entities());
  }

  private ExecutionImpl<E> execution(Statement statement) {
    return new ExecutionImpl<>(database, table, Call.Kind.QUERY, steps.parts(), reader()).of(statement);
  }

  private Statement select(Steps selected) {
    dev.horizon.basepoint.Query query = table.collection().query().columns((selected.mask() == null
        ? table.bindings()
        : selected.mask()).stream().map(Binding::name).toArray(String[]::new));
    for (Included included : selected.includes()) {
      query = query.select(INCLUDE + included.relation().name(),
          new Include(included.relation().shape() == Relation.Shape.ONE ? Include.Shape.ONE : Include.Shape.MANY,
              included.rows().related()));
    }
    query = conditions(query, selected);
    for (Clause.Sort sort : selected.sorts()) {
      query = query.orderBy(sort.value(), sort.direction());
    }
    if (selected.limit() != null) {
      query = query.limit(selected.limit());
    }
    if (selected.offset() != null) {
      query = query.offset(selected.offset());
    }
    if (selected.lock() != null) {
      query = query.lock(selected.lock());
    }
    return query.statement();
  }

  /** Gives the statement of the related rows of an include, after the parts of its model and the rewrite. */
  private Statement related() {
    return execution(select(steps)).statement();
  }

  private dev.horizon.basepoint.Query filtered() {
    return conditions(table.collection().query(), steps);
  }

  private static dev.horizon.basepoint.Query conditions(dev.horizon.basepoint.Query query, Steps conditioned) {
    dev.horizon.basepoint.Query filtered = query;
    for (Filter filter : conditioned.filters()) {
      filtered = filtered.where(filter);
    }
    return filtered;
  }

  private List<Clause.Sort> parse(String orderBy) {
    if (orderBy.isBlank()) {
      return List.of();
    }
    List<Clause.Sort> sorts = new ArrayList<>();
    for (OrderBy term : Order.parse(orderBy)) {
      Binding binding;
      try {
        binding = table.binding(term.fieldPath(), "An order");
      } catch (IllegalArgumentException unknown) {
        throw new InvalidOrderByException("%s", unknown.getMessage());
      }
      sorts.add(sortOf(binding.name(), term.descending() ? Direction.DESC : Direction.ASC));
    }
    return sorts;
  }

  private Clause.Sort sortOf(String field, Direction direction) {
    return new Clause.Sort(Expression.column(table.binding(field).name()), direction);
  }

  /** Gives the sorts with the key fields after them, so that the order of the rows is total. */
  private List<Clause.Sort> keyset(List<Clause.Sort> sorts) {
    List<Clause.Sort> total = new ArrayList<>(sorts);
    for (Binding key : table.key()) {
      if (total.stream().noneMatch(sort -> sort.value().equals(Expression.column(key.name())))) {
        total.add(new Clause.Sort(Expression.column(key.name()), Direction.ASC));
      }
    }
    return total;
  }

  /** Gives the filter of the rows after the last row of a page, in the order of the page. */
  private Filter after(List<Clause.Sort> order, List<String> cursor) {
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

  private Object value(Clause.Sort sort, String text) {
    return field(sort).fromCursor(database.driver(), text);
  }

  private List<String> cursor(List<Clause.Sort> order, E last) {
    return order.stream().map(sort -> field(sort).cursor(field(sort).get(last))).toList();
  }

  private Binding field(Clause.Sort sort) {
    return table.binding(((Expression.Column) sort.value()).name());
  }

  private String fingerprint(PageRequest request) {
    return PageToken.fingerprintOf(table.model().table(), String.valueOf(steps.filters()), String.valueOf(steps
        .sorts()), request.filter(), request.orderBy());
  }

  private QueryImpl<E> with(Steps next) {
    return new QueryImpl<>(database, table, next);
  }
}

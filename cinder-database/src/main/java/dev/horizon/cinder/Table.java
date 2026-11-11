package dev.horizon.cinder;

import dev.horizon.aquery.aip132.InvalidOrderByException;
import dev.horizon.aquery.aip157.ReadMask;
import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.aquery.aip160.InvalidFilterException;
import dev.horizon.basepoint.DeleteQuery;
import dev.horizon.basepoint.Query;
import dev.horizon.basepoint.UpdateQuery;
import dev.horizon.basepoint.expression.As;
import dev.horizon.basepoint.expression.Expression;
import dev.horizon.basepoint.expression.Literal;
import dev.horizon.basepoint.statement.Clause;
import dev.horizon.basepoint.statement.From;
import dev.horizon.basepoint.statement.Include;
import dev.horizon.basepoint.statement.Limit;
import dev.horizon.basepoint.statement.Lock;
import dev.horizon.basepoint.statement.Offset;
import dev.horizon.basepoint.statement.OrderBy;
import dev.horizon.basepoint.statement.Select;
import dev.horizon.basepoint.statement.Sort;
import dev.horizon.basepoint.statement.Source;
import dev.horizon.basepoint.statement.Statement;
import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import java.util.ArrayList;
import java.util.List;
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
  private final Readback readback;

  Table(Database database, Mapping<E> mapping, List<Part> parts) {
    this.database = database;
    this.mapping = mapping;
    this.parts = List.copyOf(parts);
    this.readback = Readback.of(mapping.driver());
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
    Query query = filter.isBlank() ? query() : query().where(mapping.requestFilter(filter));
    for (Sort sort : mapping.requestOrder(orderBy)) {
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
    Mapping<?> target = database.mapping(found.target());
    Relation.Link<?> link = found.through();
    Query rows = (link == null ? target : database.mapping(link.table())).collection().query();
    for (int i = 0; i < found.fields().size(); i++) {
      rows = rows.on(found.references().get(i), found.fields().get(i));
    }
    if (link == null) {
      return rows;
    }
    Query targetRows = target.collection().query();
    for (int i = 0; i < link.fields().size(); i++) {
      targetRows = targetRows.on(link.references().get(i), link.fields().get(i));
    }
    rows = rows.relation(found.name(), targetRows);
    for (Binding binding : target.bindings()) {
      rows = rows.select(binding.column(), Expression.column(found.name(), binding.name()));
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
   * Reads the entities of a query: a query of this table, or a union, an intersect or an except of such queries. A
   * value that the query selects under the name of a computed field of the model fills that field.
   *
   * @param query the query of this table
   * @return the entities, with the related rows of its includes. The future fails if the query selects a value under
   * the name of a stored field, or of no field, which the entities would lose.
   */
  public Future<List<E>> list(Query query) {
    return run(Invocation.Kind.QUERY, false, execution -> {
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
    return Future.future(promise -> first(query().where(model().keyFilter(key))).onComplete(promise));
  }

  /**
   * Counts the rows of a query, with its relations, groups and distinct values.
   *
   * @param query the query of this table. Its order, limit and offset do not count.
   * @return the number of rows
   */
  public Future<Long> count(Query query) {
    return run(Invocation.Kind.QUERY, false, execution -> {
      Statement rows = execution.shape(mapping.expand(rowsOf(query.statement())));
      Statement statement = new Statement(new Source.Rows(rows), List.of(new Select(false, List.of(new As(
          Expression.count(), "count"))), new From()));
      return execution.run(statement).map(result -> ((Number) result.iterator().next().getValue(0)).longValue());
    });
  }

  /**
   * Tells whether a query has a row.
   *
   * @param query the query of this table. Its order, limit and offset do not count.
   * @return whether a row exists
   */
  public Future<Boolean> exists(Query query) {
    return run(Invocation.Kind.QUERY, false, execution -> {
      Statement rows = execution.shape(mapping.expand(rowsOf(query.statement())));
      Statement statement = new Statement(new Source.Rows(rows), List.of(new Select(false, List.of(new As(
          new Literal(1), PRESENT))), new From(), new Limit(1)));
      return execution.run(statement).map(result -> result.size() > 0);
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
    return Future.future(promise -> {
      PageQuery<E> page = new PageQuery<>(database, mapping, query, request);
      list(page.query()).map(page::page).onComplete(promise);
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
    return run(Invocation.Kind.QUERY, false, execution -> execution.run(execution.shape(query.statement())));
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
   * @return with no conflict, the entities that the table inserted; with one, the rows of the entities, which
   * {@link Conflict#ignore()} kept or the insert wrote. Each as the table holds it, with the values that its triggers
   * wrote. An insert with a conflict on a database that does not return written rows reads them back by the values of
   * the conflict target, which each entity needs.
   */
  public Future<List<E>> insert(List<E> entities, Conflict conflict) {
    if (entities.isEmpty()) {
      return Future.succeededFuture(List.of());
    }
    return run(Invocation.Kind.INSERT, entities.size() > 1, execution -> readback.insert(new TableInsert<>(execution,
        entities, conflict)));
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
    return run(Invocation.Kind.INSERT, entities.size() > 1, execution -> new TableInsert<>(execution, entities,
        conflict).run());
  }

  /**
   * Runs an update.
   *
   * @param update the update of this table
   * @return the number of rows that it updated
   */
  public Future<Long> execute(UpdateQuery update) {
    return run(Invocation.Kind.UPDATE, false, execution -> new TableUpdate<>(execution, update).run());
  }

  /**
   * Runs an update, and reads the rows that it updated. On a database that cannot return the rows of an update, the
   * table locks the rows of the filter, updates them by key and reads them, in one transaction.
   *
   * @param update the update of this table
   * @return the updated entities, as the table holds them after the update
   */
  public Future<List<E>> returning(UpdateQuery update) {
    return run(Invocation.Kind.UPDATE, readback.needsTransaction(), execution -> readback.update(new TableUpdate<>(
        execution, update)));
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
    return Future.future(promise -> {
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
      returning(update.where(model().keyFilterOf(entity))).map(updated -> updated.stream().findFirst()).onComplete(promise);
    });
  }

  /**
   * Runs a delete.
   *
   * @param delete the delete of this table
   * @return the number of rows that it deleted
   */
  public Future<Long> execute(DeleteQuery delete) {
    return run(Invocation.Kind.DELETE, false, execution -> new TableDelete<>(execution, delete).run());
  }

  /**
   * Runs a delete, and reads the rows that it deleted.
   *
   * @param delete the delete of this table
   * @return the deleted entities
   */
  public Future<List<E>> returning(DeleteQuery delete) {
    return run(Invocation.Kind.DELETE, readback.needsTransaction(), execution -> readback.delete(new TableDelete<>(
        execution, delete)));
  }

  /**
   * Runs the work of an operation, in a transaction of its own if it needs one and the context has none. The
   * execution starts in the transaction, so that its context holds it. The future fails with what the work throws.
   */
  private <T> Future<T> run(Invocation.Kind kind, boolean transaction, Function<Execution<E>, Future<T>> work) {
    return Future.future(promise -> {
      Future<T> result = transaction && Transaction.current() == null ? database.transaction(ignored -> work.apply(
          execution(kind))) : work.apply(execution(kind));
      result.onComplete(promise);
    });
  }

  private Execution<E> execution(Invocation.Kind kind) {
    return new Execution<>(database, mapping, kind, parts);
  }

  private static Statement rowsOf(Statement statement) {
    List<Clause> clauses = new ArrayList<>();
    for (Clause clause : statement.clauses()) {
      switch (clause) {
        case OrderBy ignored -> {
        }
        case Limit ignored -> {
        }
        case Offset ignored -> {
        }
        case Lock ignored -> {
        }
        case Select select -> clauses.add(new Select(select.distinct(),
            select.columns().stream().filter(column -> !(column instanceof As(Include ignored, String name))).toList()));
        default -> clauses.add(clause);
      }
    }
    return new Statement(statement.source(), clauses);
  }
}

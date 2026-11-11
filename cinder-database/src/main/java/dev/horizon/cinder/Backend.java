package dev.horizon.cinder;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.basepoint.DeleteQuery;
import dev.horizon.basepoint.Query;
import dev.horizon.basepoint.UpdateQuery;
import dev.horizon.basepoint.filter.Filter;
import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The rows of one type of entity. An implementation gives an entity of the type and the database, and adds the
 * operations of its own domain. Every other operation has a default, which runs on the {@link #table() table} of the
 * type.
 *
 * <pre>{@code
 * public record Orders(Database database) implements Backend<Order, Long> {
 *
 *   public Order prototype() {
 *     return Order.getDefaultInstance();
 *   }
 *
 *   public Future<Boolean> cancel(long id, Instant now) {
 *     return execute(update()
 *         .set("state", "CANCELLED")
 *         .set("cancel_time", now)
 *         .increment("version", 1)
 *         .where(and(eq("id", id), eq("state", "OPEN"))))
 *         .map(updated -> updated > 0);
 *   }
 * }
 * }</pre>
 *
 * <p>
 * The key is the value of the key field. The operations by key take a table whose key has one column; a backend of a
 * table whose key has more columns selects its rows with a filter on each of them, such as
 * {@code list(query().where(and(eq("group_id", group), eq("user_id", user))))}.
 *
 * <p>
 * An extension adds operations of its own as an interface that extends this one, with default methods, such as a
 * {@code GraphBackend} with {@code descendants}. A backend implements the interfaces of the operations it wants.
 *
 * @param <E> the type of the entities
 * @param <K> the type of the key
 */
public interface Backend<E, K> {

  /**
   * Gives an entity of the type, whose model the backend runs on.
   *
   * @return the entity, such as {@code Order.getDefaultInstance()}
   */
  E prototype();

  /**
   * Gives the database of the backend.
   *
   * @return the database
   */
  Database database();

  /**
   * Gives the model of the type.
   *
   * @return the model
   */
  default Model<E> model() {
    return database().model(prototype());
  }

  /**
   * Gives the table of the type, which runs the Basepoint statements of the backend.
   *
   * @return the table
   */
  default Table<E> table() {
    return database().table(prototype());
  }

  /**
   * Starts a query of the rows.
   *
   * @return the query, which reads every field unless it names some
   * @see Table#query()
   */
  default Query query() {
    return table().query();
  }

  /**
   * Starts a query of the rows from the AIP-160 filter and the AIP-132 order of a request.
   *
   * @param filter the filter, such as {@code state = "OPEN"}, or empty for every row
   * @param orderBy the order, such as {@code create_time desc, id}, or empty for no order
   * @return the query
   * @see Table#query(String, String)
   */
  default Query query(String filter, String orderBy) {
    return table().query(filter, orderBy);
  }

  /**
   * Starts a query of the related rows of a relation, for {@code include} or {@code includeOne}.
   *
   * @param relation the name of the relation
   * @return the query of the related rows
   * @see Table#related(String)
   */
  default Query related(String relation) {
    return table().related(relation);
  }

  /**
   * Starts an update of the rows.
   *
   * @return the update
   */
  default UpdateQuery update() {
    return table().update();
  }

  /**
   * Starts a delete of the rows.
   *
   * @return the delete
   */
  default DeleteQuery delete() {
    return table().delete();
  }

  /**
   * Reads the entities of a query.
   *
   * @param query the query of the rows
   * @return the entities
   * @see Table#list(Query)
   */
  default Future<List<E>> list(Query query) {
    return table().list(query);
  }

  /**
   * Reads the first entity of a query.
   *
   * @param query the query of the rows
   * @return the first entity, or nothing
   * @see Table#first(Query)
   */
  default Future<Optional<E>> first(Query query) {
    return table().first(query);
  }

  /**
   * Reads the one entity of a query.
   *
   * @param query the query of the rows
   * @return the entity
   * @see Table#single(Query)
   */
  default Future<E> single(Query query) {
    return table().single(query);
  }

  /**
   * Counts the rows of a query.
   *
   * @param query the query of the rows
   * @return the number of rows
   * @see Table#count(Query)
   */
  default Future<Long> count(Query query) {
    return table().count(query);
  }

  /**
   * Tells whether a query has a row.
   *
   * @param query the query of the rows
   * @return whether a row exists
   * @see Table#exists(Query)
   */
  default Future<Boolean> exists(Query query) {
    return table().exists(query);
  }

  /**
   * Reads a page of a query.
   *
   * @param query the query of the rows
   * @param request the page request
   * @return the page
   * @see Table#page(Query, PageRequest)
   */
  default Future<Page<E>> page(Query query, PageRequest request) {
    return table().page(query, request);
  }

  /**
   * Streams the entities of a query.
   *
   * @param query the query of the rows
   * @return the stream
   * @see Table#stream(Query)
   */
  default ReadStream<E> stream(Query query) {
    return table().stream(query);
  }

  /**
   * Runs an update.
   *
   * @param update the update of the rows
   * @return the number of updated rows
   * @see Table#execute(UpdateQuery)
   */
  default Future<Long> execute(UpdateQuery update) {
    return table().execute(update);
  }

  /**
   * Runs an update, and reads the rows that it updated.
   *
   * @param update the update of the rows
   * @return the updated entities
   * @see Table#returning(UpdateQuery)
   */
  default Future<List<E>> returning(UpdateQuery update) {
    return table().returning(update);
  }

  /**
   * Runs a delete.
   *
   * @param delete the delete of the rows
   * @return the number of deleted rows
   * @see Table#execute(DeleteQuery)
   */
  default Future<Long> execute(DeleteQuery delete) {
    return table().execute(delete);
  }

  /**
   * Runs a delete, and reads the rows that it deleted.
   *
   * @param delete the delete of the rows
   * @return the deleted entities
   * @see Table#returning(DeleteQuery)
   */
  default Future<List<E>> returning(DeleteQuery delete) {
    return table().returning(delete);
  }

  /**
   * Reads the entity of a key.
   *
   * @param id the key
   * @return the entity, or nothing if no row has the key
   */
  default Future<Optional<E>> findById(K id) {
    return table().find(id);
  }

  /**
   * Reads the entities of keys.
   *
   * @param ids the keys
   * @return the entities of the keys that rows have, in no particular order
   */
  default Future<List<E>> findAllById(Collection<K> ids) {
    return Future.future(promise -> list(query().where(Filter.or(ids.stream().map(model()::keyFilter)
        .toArray(Filter[]::new)))).onComplete(promise));
  }

  /**
   * Tells whether a row has a key.
   *
   * @param id the key
   * @return whether a row has it
   */
  default Future<Boolean> existsById(K id) {
    return Future.future(promise -> exists(query().where(model().keyFilter(id))).onComplete(promise));
  }

  /**
   * Reads a page of every row, as the AIP-132 List method of a resource does.
   *
   * @param request the page size, the token, and the filter and the order of the request
   * @return the page
   * @see Table#page(Query, PageRequest)
   */
  default Future<Page<E>> list(PageRequest request) {
    return page(query(), request);
  }

  /**
   * Counts every row.
   *
   * @return the number of rows
   */
  default Future<Long> count() {
    return count(query());
  }

  /**
   * Inserts an entity.
   *
   * @param entity the entity
   * @return the entity as stored, with the values that the database gave its columns
   */
  default Future<E> insert(E entity) {
    return table().insert(entity);
  }

  /**
   * Inserts entities.
   *
   * @param entities the entities
   * @return the entities as stored, in their order
   */
  default Future<List<E>> insertAll(List<E> entities) {
    return table().insert(entities);
  }

  /**
   * Inserts an entity, or updates every field but the key of the row of its key.
   *
   * @param entity the entity
   * @return the entity as stored
   */
  default Future<E> upsert(E entity) {
    return table().insert(List.of(entity), Conflict.merge()).map(List::getFirst);
  }

  /**
   * Updates the fields of an AIP-134 update mask in the row of an entity, as the AIP-134 Update method of a resource
   * does.
   *
   * @param entity the entity, whose key selects the row
   * @param updateMask the paths of the fields to update, separated by commas, or {@code *} for every field but the
   * key
   * @return the entity as stored after the update, or empty if no row has its key
   */
  default Future<Optional<E>> update(E entity, String updateMask) {
    return table().update(entity, updateMask);
  }

  /**
   * Updates the row of a key:
   *
   * <pre>{@code
   * orders.updateById(42L, update -> update.set("state", "PAID").increment("version", 1));
   * }</pre>
   *
   * @param id the key
   * @param set sets the fields of the update
   * @return whether a row had the key
   */
  default Future<Boolean> updateById(K id, UnaryOperator<UpdateQuery> set) {
    return Future.future(promise -> execute(set.apply(update()).where(model().keyFilter(id))).map(updated -> updated > 0)
        .onComplete(promise));
  }

  /**
   * Deletes the row of a key.
   *
   * @param id the key
   * @return whether a row had the key
   */
  default Future<Boolean> deleteById(K id) {
    return Future.future(promise -> execute(delete().where(model().keyFilter(id))).map(deleted -> deleted > 0)
        .onComplete(promise));
  }
}

package dev.horizon.cinder;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.basepoint.Filter;
import io.vertx.core.Future;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The rows of one type of entity. An implementation gives the type and the database, and adds the operations of its
 * own domain. Every other operation has a default, which runs on the database.
 *
 * <p>
 * The key is the value of a single key field, or a record whose components name the key fields, for a table whose
 * key has more than one column.
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
   * Gives the type of the entities, which names the model of the backend.
   *
   * @return the type, such as {@code Order.class}
   */
  Class<E> type();

  /**
   * Gives the database of the rows.
   *
   * @return the database that runs every operation of the backend
   */
  Database database();

  /**
   * Gives the model of the type.
   *
   * @return the model that a source of the database gives the type
   * @throws IllegalArgumentException if no source of the database gives a model of the type
   */
  default Model<E> model() {
    return database().model(type());
  }

  /**
   * Reads the row of a key.
   *
   * @param id the key
   * @return the entity of the row, or empty if no row has the key
   */
  default Future<Optional<E>> findById(K id) {
    return find(model().keyFilter(id)).first();
  }

  /**
   * Reads the rows of some keys.
   *
   * @param ids the keys
   * @return the entities of the keys that rows have, in no particular order
   */
  default Future<List<E>> findAllById(Collection<K> ids) {
    return find(Filter.or(ids.stream().map(model()::keyFilter).toArray(Filter[]::new))).list();
  }

  /**
   * Tells if a row has a key.
   *
   * @param id the key
   * @return true if a row has the key
   */
  default Future<Boolean> existsById(K id) {
    return find(model().keyFilter(id)).exists();
  }

  /**
   * Starts a query of every row.
   *
   * @return the query, to narrow, sort and run
   */
  default Query<E> findAll() {
    return database().query(model());
  }

  /**
   * Starts a query of the rows that a filter selects.
   *
   * @param where the filter, such as {@code eq("state", "OPEN")}
   * @return the query, to narrow, sort and run
   */
  default Query<E> find(Filter where) {
    return findAll().where(where);
  }

  /**
   * Starts a query of the rows that an AIP-160 filter selects.
   *
   * @param filter the filter, whose names are fields of the model, such as
   * {@code state = "OPEN" AND total_cents > 100}
   * @return the query, to narrow, sort and run
   * @throws IllegalArgumentException if the filter is not valid, or names a field that the model does not have
   */
  default Query<E> find(String filter) {
    return findAll().where(filter);
  }

  /**
   * Lists a page as an AIP-132 and AIP-158 list request asks.
   *
   * @param request the filter, the order, the page size and the page token of the request
   * @return the page, with the token of the next page, which is empty on the last page
   */
  default Future<Page<E>> list(PageRequest request) {
    return findAll().page(request);
  }

  /**
   * Counts every row.
   *
   * @return the number of rows
   */
  default Future<Long> count() {
    return findAll().count();
  }

  /**
   * Inserts an entity.
   *
   * @param entity the entity
   * @return the entity as stored, with the values that the database computed, or a future that fails with a
   * {@link DuplicateKeyException} if a row has its key
   */
  default Future<E> insert(E entity) {
    return insert().values(entity).returning().map(List::getFirst);
  }

  /**
   * Inserts entities.
   *
   * @param entities the entities
   * @return the entities as stored, in the order given, or a future that fails with a {@link DuplicateKeyException}
   * if a row has the key of one
   */
  default Future<List<E>> insertAll(List<E> entities) {
    return insert().values(entities).returning();
  }

  /**
   * Inserts an entity, or updates the row of its key to the entity.
   *
   * @param entity the entity
   * @return the entity as stored, with the values that the database computed
   */
  default Future<E> upsert(E entity) {
    return insert().values(entity).onConflict(Conflict.merge()).returning().map(List::getFirst);
  }

  /**
   * Updates the fields of an AIP-134 update mask in the row of an entity.
   *
   * @param entity the entity, whose key selects the row
   * @param updateMask the paths of the fields to update, separated by commas, or {@code *} for every field but the
   * key
   * @return the entity as stored after the update, or empty if no row has its key
   */
  default Future<Optional<E>> update(E entity, String updateMask) {
    return update().where(model().keyFilterOf(entity)).set(entity, updateMask).returning()
        .map(updated -> updated.stream().findFirst());
  }

  /**
   * Updates the row of a key.
   *
   * @param id the key
   * @param set the changes to the fields of the row
   * @return true if a row had the key
   */
  default Future<Boolean> updateById(K id, Assignments set) {
    return update().where(model().keyFilter(id)).set(set).execute().map(updated -> updated > 0);
  }

  /**
   * Deletes the row of a key.
   *
   * @param id the key
   * @return true if a row had the key
   */
  default Future<Boolean> deleteById(K id) {
    return delete().where(model().keyFilter(id)).execute().map(deleted -> deleted > 0);
  }

  /**
   * Starts an insert, for conflict policies, parts and returned rows.
   *
   * @return the insert, without rows
   */
  default Insert<E> insert() {
    return database().insert(model());
  }

  /**
   * Starts an update, which needs a filter or {@link Update#all()}.
   *
   * @return the update, without conditions or changes
   */
  default Update<E> update() {
    return database().update(model());
  }

  /**
   * Starts a delete, which needs a filter or {@link Delete#all()}.
   *
   * @return the delete, without conditions
   */
  default Delete<E> delete() {
    return database().delete(model());
  }
}

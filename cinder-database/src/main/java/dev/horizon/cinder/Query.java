package dev.horizon.cinder;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.basepoint.Filter;
import dev.horizon.basepoint.LockMode;
import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * A query of the rows of a model. Each method gives a new query, and nothing runs until a method gives a future or a
 * stream.
 *
 * @param <E> the type of the entities
 */
public interface Query<E> extends Operation<E, Query<E>> {

  /**
   * Adds a condition. The conditions of a query must all hold.
   *
   * @param filter the condition, whose names are fields of the model, such as {@code gt("total_cents", 100)}
   * @return the query, with the condition
   */
  Query<E> where(Filter filter);

  /**
   * Adds the condition of an AIP-160 filter. The conditions of a query must all hold.
   *
   * @param filter the filter, whose names are fields of the model, such as {@code state = "OPEN"}
   * @return the query, with the condition
   * @throws IllegalArgumentException if the filter is not valid, or names a field that the model does not have
   */
  Query<E> where(String filter);

  /**
   * Sorts the rows, after the sorts that the query has.
   *
   * @param sort the fields and their directions
   * @return the query, with the sort
   */
  Query<E> sort(Sort sort);

  /**
   * Sorts the rows as an AIP-132 order, after the sorts that the query has.
   *
   * @param orderBy the order, whose names are fields of the model, such as {@code create_time desc, id}
   * @return the query, with the order
   * @throws IllegalArgumentException if the order is not valid, or names a field that the model does not have
   */
  Query<E> orderBy(String orderBy);

  /**
   * Reads at most some rows.
   *
   * @param limit the most rows
   * @return the query, with the limit
   */
  Query<E> limit(long limit);

  /**
   * Skips the first rows.
   *
   * @param offset the number of rows to skip
   * @return the query, with the offset
   */
  Query<E> offset(long offset);

  /**
   * Locks the rows that the query reads, until the transaction of the query ends.
   *
   * @param mode the lock
   * @return the query, with the lock
   */
  Query<E> lock(LockMode mode);

  /**
   * Reads only the fields of an AIP-157 read mask. The other fields keep their defaults.
   *
   * @param readMask the paths of the fields, separated by commas, such as {@code id,state}
   * @return the query, with the mask
   * @throws IllegalArgumentException if the mask names a field that the model does not have
   */
  Query<E> mask(String readMask);

  /**
   * Reads the related rows of a relation into its field, in the same statement.
   *
   * @param relation the name of the relation, such as {@code items}
   * @return the query, with the include
   * @throws IllegalArgumentException if the model has no relation of the name
   */
  Query<E> include(String relation);

  /**
   * Reads the related rows of a relation into its field, in the same statement, as a query of their own narrows them.
   * The query of the related rows can include their own relations.
   *
   * @param relation the name of the relation, such as {@code items}
   * @param narrow narrows the query of the related rows, such as
   * {@code items -> items.orderBy("position").limit(20)}
   * @return the query, with the include
   * @throws IllegalArgumentException if the model has no relation of the name
   */
  Query<E> include(String relation, UnaryOperator<Query<?>> narrow);

  /**
   * Reads the related rows of a relation into its field, in the same statement, as a query of their own narrows them.
   *
   * @param relation the relation, from {@link Model#relation(String)}
   * @param narrow narrows the query of the related rows
   * @param <T> the type of the related entities
   * @return the query, with the include
   */
  <T> Query<E> include(Relation<T> relation, UnaryOperator<Query<T>> narrow);

  /**
   * Reads every row of the query.
   *
   * @return the entities, in the order of the query
   */
  Future<List<E>> list();

  /**
   * Reads the first row of the query.
   *
   * @return the entity, or empty if the query has no row
   */
  Future<Optional<E>> first();

  /**
   * Reads the one row of the query.
   *
   * @return the entity
   * @throws java.util.NoSuchElementException in the future if the query has no row
   * @throws IllegalStateException in the future if the query has more than one row
   */
  Future<E> single();

  /**
   * Reads a page. The filter and the order of the request join those of the query, and its token continues the list
   * after the page that gave it. A page holds at most the page size of the request, and at most the
   * {@link Database.Builder#maxPageSize} of the database.
   *
   * @param request the filter, the order, the page size and the page token of an AIP-132 and AIP-158 list request
   * @return the page, with the token of the next page, which is empty on the last page
   */
  Future<Page<E>> page(PageRequest request);

  /**
   * Counts the rows of the query.
   *
   * @return the number of rows
   */
  Future<Long> count();

  /**
   * Tells if the query has a row.
   *
   * @return true if the query has at least one row
   */
  Future<Boolean> exists();

  /**
   * Reads the rows through a cursor, as they come from the database, with the back pressure of the stream. It reads
   * 100 rows at a time.
   *
   * @return the stream of the entities, in the order of the query
   * @see #stream(int)
   */
  ReadStream<E> stream();

  /**
   * Reads the rows through a cursor, as they come from the database, with the back pressure of the stream. In a
   * transaction, the cursor uses its connection. Without one, it takes a connection and a transaction of its own, and
   * gives them back when the stream ends or fails. The parts of the query complete the entities of each batch.
   *
   * @param fetch the number of rows that the cursor reads at a time
   * @return the stream of the entities, in the order of the query
   */
  ReadStream<E> stream(int fetch);
}

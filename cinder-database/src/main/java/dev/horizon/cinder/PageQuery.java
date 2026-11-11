package dev.horizon.cinder;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.aquery.aip158.PageToken;
import dev.horizon.basepoint.Direction;
import dev.horizon.basepoint.Query;
import dev.horizon.basepoint.RenderedStatement;
import dev.horizon.basepoint.expression.Column;
import dev.horizon.basepoint.expression.Reference;
import dev.horizon.basepoint.filter.Filter;
import dev.horizon.basepoint.statement.Offset;
import dev.horizon.basepoint.statement.OrderBy;
import dev.horizon.basepoint.statement.Sort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A page of a query of a table, as an AIP-158 request asks for it. Its query adds the filter and the order of the
 * request to the query of the table, ends the order with the key of the model, starts after the row of the token of
 * the request, and reads one row more than the page holds, which tells whether another page follows.
 *
 * @param <E> the type of the entities
 */
final class PageQuery<E> {

  private final Database database;
  private final Mapping<E> mapping;
  private final List<Sort> order = new ArrayList<>();
  private final String fingerprint;
  private final int size;
  private final Query query;

  PageQuery(Database database, Mapping<E> mapping, Query query, PageRequest request) {
    if (Mapping.compound(query.statement())) {
      throw new IllegalArgumentException("A page continues one query of the table, not a union, an intersect or an"
          + " except of queries.");
    }
    if (query.statement().has(Offset.class)) {
      throw new IllegalArgumentException("A page starts after the row of its token, so its query has no offset.");
    }
    this.database = database;
    this.mapping = mapping;
    this.fingerprint = fingerprint(query, request);
    this.size = Math.min(request.pageSize(), database.maxPageSize());
    if (!request.filter().isEmpty()) {
      query = query.where(mapping.requestFilter(request.filter()));
    }
    for (Sort sort : query.statement().find(OrderBy.class).map(OrderBy::sorts).orElse(List.of())) {
      order.add(new Sort(new Column(field(sort).name()), sort.direction()));
    }
    List<Sort> requestOrder = new ArrayList<>(mapping.requestOrder(request.orderBy()));
    for (Binding key : mapping.key()) {
      if (order.stream().noneMatch(sort -> field(sort) == key) && requestOrder.stream().noneMatch(sort -> field(
          sort) == key)) {
        requestOrder.add(new Sort(new Column(key.name()), Direction.ASC));
      }
    }
    for (Sort sort : requestOrder) {
      query = query.orderBy(sort.value(), sort.direction());
    }
    order.addAll(requestOrder);
    query = query.limit((long) size + 1);
    Optional<PageToken> token = database.tokens().decode(request.pageToken(), fingerprint);
    if (token.isPresent()) {
      query = query.where(after(token.get().cursor()));
    }
    this.query = query;
  }

  /**
   * Gives the query that reads the rows of the page.
   */
  Query query() {
    return query;
  }

  /**
   * Gives the page of the entities that the query read, with the token of the next page if the query read more
   * entities than the page holds.
   */
  Page<E> page(List<E> entities) {
    if (entities.size() <= size) {
      return new Page<>(entities, "", 0);
    }
    List<E> items = entities.subList(0, size);
    E last = items.getLast();
    List<String> cursor = order.stream().map(sort -> field(sort).cursor(field(sort).get(last))).toList();
    return new Page<>(items, database.tokens().encode(new PageToken(cursor, fingerprint)), 0);
  }

  private Filter after(List<String> cursor) {
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

  private Binding field(Sort sort) {
    return switch (sort.value()) {
      case Column column -> mapping.binding(column.name());
      case Reference reference -> mapping.binding(reference.name());
      default -> throw new IllegalArgumentException("A page orders by fields, so that its token can hold the values of"
          + " the last row, not by " + sort.value() + ".");
    };
  }

  private String fingerprint(Query query, PageRequest request) {
    RenderedStatement statement = database.render(mapping.rewrite(mapping.expand(query.statement())));
    return PageToken.fingerprintOf(mapping.model().table(), statement.text(), String.valueOf(statement.params()),
        request.filter(), request.orderBy());
  }
}

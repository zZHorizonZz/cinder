package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.Direction.DESC;
import static dev.horizon.basepoint.expression.Expression.add;
import static dev.horizon.basepoint.expression.Expression.column;
import static dev.horizon.basepoint.expression.Expression.concat;
import static dev.horizon.basepoint.expression.Expression.lower;
import static dev.horizon.basepoint.expression.Expression.upper;
import static dev.horizon.basepoint.expression.Expression.value;
import static dev.horizon.basepoint.expression.Expression.when;
import static dev.horizon.basepoint.filter.Filter.and;
import static dev.horizon.basepoint.filter.Filter.eq;
import static dev.horizon.basepoint.filter.Filter.gt;
import static dev.horizon.basepoint.filter.Filter.in;
import static dev.horizon.basepoint.filter.Filter.isNull;
import static dev.horizon.basepoint.filter.Filter.lt;
import static dev.horizon.basepoint.filter.Filter.or;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.horizon.aquery.aip132.InvalidOrderByException;
import dev.horizon.aquery.aip157.ReadMask;
import dev.horizon.aquery.aip160.InvalidFilterException;
import dev.horizon.basepoint.filter.Filter;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

public abstract class ReadContractTest extends ContractTest {

  static Stream<Arguments> filters() {
    return Stream.of(
        Arguments.of("equal", eq("state", "OPEN"), List.of(1L, 3L, 5L)),
        Arguments.of("greater", gt("total_cents", 15000L), List.of(3L, 5L)),
        Arguments.of("and", and(eq("customer_id", "anne"), eq("state", "PAID")), List.of(2L)),
        Arguments.of("or", or(eq("customer_id", "carl"), lt("total_cents", 6000L)), List.of(4L, 5L)),
        Arguments.of("in", in("id", List.of(2L, 4L)), List.of(2L, 4L)),
        Arguments.of("null", isNull("cancel_time"), List.of(1L, 2L, 3L, 5L)),
        Arguments.of("time", gt("create_time", day(2)), List.of(4L, 5L)),
        Arguments.of("not", Filter.not(eq("state", "OPEN")), List.of(2L, 4L)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("filters")
  @DisplayName("Finds the rows of a filter")
  void filter(String name, Filter filter, List<Long> expected) throws TimeoutException {
    assertThat(ids(orders.query().where(filter))).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      state = "OPEN"                                 | 1,3,5
      total_cents > 15000                            | 3,5
      customer_id = "anne" AND state != "OPEN"       | 2
      state = "OPEN" OR total_cents < 6000           | 1,3,4,5
      create_time > "2026-01-03T10:00:00Z"           | 4,5
      create_time >= "2026-01-03T10:00:00Z"          | 3,4,5
      NOT state = "OPEN"                             | 2,4
      """)
  @DisplayName("Finds the rows of a filter text")
  void filterText(String filter, String expected) throws TimeoutException {
    assertThat(ids(orders.query().where(filter))).isEqualTo(longs(expected));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      total_cents desc                 | 3,5,1,2,4
      customer_id, total_cents desc    | 1,2,3,4,5
      create_time desc                 | 5,4,3,2,1
      id desc                          | 5,4,3,2,1
      """)
  @DisplayName("Sorts the rows of an order text")
  void order(String orderBy, String expected) throws TimeoutException {
    assertThat(idsInOrder(orders.query("", orderBy))).isEqualTo(longs(expected));
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(delimiter = '|', textBlock = """
      state = "OPEN"           | total_cents desc    | 3,5,1
      ''                       | customer_id, id     | 1,2,3,4,5
      customer_id = "beth"     | id desc             | 4,3
      """)
  @DisplayName("Reads the query of the filter and the order of a request")
  void request(String filter, String orderBy, String expected) throws TimeoutException {
    assertThat(idsInOrder(orders.query(filter, orderBy))).isEqualTo(longs(expected));
  }

  @Test
  @DisplayName("Refuses the filter or the order of a request that does not fit the model")
  void refuseRequest() {
    assertThatThrownBy(() -> orders.query("budget > 3", "")).isInstanceOf(InvalidFilterException.class)
        .hasMessageContaining("has no field budget");
    assertThatThrownBy(() -> orders.query("state = ", "")).isInstanceOf(InvalidFilterException.class);
    assertThatThrownBy(() -> orders.query("", "budget")).isInstanceOf(InvalidOrderByException.class)
        .hasMessageContaining("has no field budget");
    assertThatThrownBy(() -> orders.query("", "state.name")).isInstanceOf(InvalidOrderByException.class)
        .hasMessageContaining("not the path state.name");
  }

  @Test
  @DisplayName("Finds the rows of a function of a field")
  void function() throws TimeoutException {
    assertThat(ids(orders.query().where(eq(upper(column("customer_id")), "ANNE")))).containsExactly(1L, 2L);
    assertThat(ids(orders.query().where(eq(concat(value("customer-"), column("customer_id")), "customer-beth"))))
        .containsExactly(3L, 4L);
  }

  @Test
  @DisplayName("Maps a field inside a function to its column of another name")
  void functionOfColumn() throws TimeoutException {
    assertThat(ids(orders.query().where(gt(add(column("create_time"), Duration.ofDays(1)), day(4)))))
        .containsExactly(5L);
    assertThat(ids(orders.query().where(eq(when(gt("create_time", day(2)), value("late"), value("early")),
        "late")))).containsExactly(4L, 5L);
  }

  @Test
  @DisplayName("Refuses a function of a field that the model does not have")
  void refuseFunction() throws TimeoutException {
    assertThat(failure(orders.list(orders.query().where(eq(lower(column("budget")), "x")))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The model of the table orders has no field budget.");
    assertThat(failure(orders.list(orders.query().where(eq(when(gt("budget", 1), value("a"), value("b")), "a")))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The model of the table orders has no field budget.");
  }

  @Test
  @DisplayName("Sorts an enum by the order of its values where the database stores enums")
  void enumOrder() throws TimeoutException {
    assumeTrue(testDatabase().storesEnums());

    assertThat(idsInOrder(orders.query().orderBy("state", DESC).orderBy("id"))).containsExactly(4L, 2L, 1L, 3L, 5L);
  }

  @Test
  @DisplayName("Sorts an enum by its text where the database stores text")
  void enumText() throws TimeoutException {
    assumeFalse(testDatabase().storesEnums());

    assertThat(idsInOrder(orders.query().orderBy("state", DESC).orderBy("id"))).containsExactly(2L, 1L, 3L, 5L, 4L);
  }

  @Test
  @DisplayName("Limits and skips rows")
  void limit() throws TimeoutException {
    assertThat(idsInOrder(orders.query().orderBy("id").limit(2).offset(1))).containsExactly(2L, 3L);
  }

  @Test
  @DisplayName("Reads every field of a row")
  void everyField() throws TimeoutException {
    assertThat(await(orders.find(4L))).contains(new Shop.Order(4L, "beth", "CANCELLED", 5000L, 2, day(3), day(4),
        null, List.of()));
  }

  @Test
  @DisplayName("Finds a row by its key, and nothing for a key that no row has")
  void key() throws TimeoutException {
    assertThat(await(orders.find(1L))).map(Shop.Order::customerId).contains("anne");
    assertThat(await(orders.find(9L))).isEmpty();
  }

  @Test
  @DisplayName("Counts the rows of the filter of a query, whatever its order and limit")
  void count() throws TimeoutException {
    assertThat(await(orders.count(orders.query()))).isEqualTo(5L);
    assertThat(await(orders.count(orders.query().where(eq("state", "OPEN")).orderBy("id").limit(1)))).isEqualTo(3L);
  }

  @Test
  @DisplayName("Tells whether the filter of a query keeps a row")
  void exists() throws TimeoutException {
    assertThat(await(orders.exists(orders.query().where(eq("state", "OPEN"))))).isTrue();
    assertThat(await(orders.exists(orders.query().where(eq("state", "LOST"))))).isFalse();
  }

  @Test
  @DisplayName("Reads the first row of a query, and nothing for a query without rows")
  void first() throws TimeoutException {
    assertThat(await(orders.first(orders.query().orderBy("total_cents", DESC)))).map(Shop.Order::id).contains(3L);
    assertThat(await(orders.first(orders.query().where(eq("state", "LOST"))))).isEmpty();
  }

  @Test
  @DisplayName("Reads the one row of a query, and refuses a query of none or of more")
  void single() throws TimeoutException {
    assertThat(await(orders.single(orders.query().where(eq("customer_id", "carl")))).id()).isEqualTo(5L);
    assertThat(failure(orders.single(orders.query().where(eq("customer_id", "dora")))))
        .isInstanceOf(NoSuchElementException.class).hasMessage("The query of the table orders has no row.");
    assertThat(failure(orders.single(orders.query().where(eq("customer_id", "anne")))))
        .isInstanceOf(IllegalStateException.class).hasMessage("The query of the table orders has more than one row.");
  }

  @Test
  @DisplayName("Reads only the fields of a read mask")
  void mask() throws TimeoutException {
    Shop.Order order = await(orders.single(orders.query(ReadMask.parse("id,state")).where(eq("id", 1L))));

    assertThat(order).isEqualTo(new Shop.Order(1L, null, "OPEN", null, null, null, null, null, List.of()));
  }

  @Test
  @DisplayName("Reads only the fields that a query names")
  void columns() throws TimeoutException {
    Shop.Order order = await(orders.single(orders.query().columns("id", "total_cents").where(eq("id", 1L))));

    assertThat(order).isEqualTo(new Shop.Order(1L, null, null, 15000L, null, null, null, null, List.of()));
  }

  @Test
  @DisplayName("Names a field by its column too")
  void columnName() throws TimeoutException {
    assertThat(ids(orders.query().where(gt("created_at", day(3))))).containsExactly(5L);
  }

  @Test
  @DisplayName("Refuses a filter, an order or a column of a field that the model does not have")
  void refuseField() throws TimeoutException {
    assertThat(failure(orders.list(orders.query().where(eq("colour", "red")))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The model of the table orders has no field colour.");
    assertThat(failure(orders.list(orders.query().orderBy("colour"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The model of the table orders has no field colour.");
    assertThat(failure(orders.list(orders.query().columns("id", "colour"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The model of the table orders has no field colour.");
  }

  @Test
  @DisplayName("Refuses a query of another table")
  void refuseTable() throws TimeoutException {
    assertThat(failure(orders.list(customers.query()))).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The statement is of the table customers, not of the table orders of the model.");
    assertThat(failure(orders.count(items.query()))).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The statement is of the table items, not of the table orders of the model.");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      total_cents = 10.5           | The value 10.5 is not a value of the field total_cents
      create_time > "yesterday"    | The value yesterday is not a value of the field create_time
      """)
  @DisplayName("Refuses a value that is not a value of its field")
  void refuseValue(String filter, String message) throws TimeoutException {
    assertThat(failure(orders.list(orders.query().where(filter)))).isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith(message);
  }
}

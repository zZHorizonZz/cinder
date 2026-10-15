package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.Filter.and;
import static dev.horizon.basepoint.Filter.eq;
import static dev.horizon.basepoint.Filter.gt;
import static dev.horizon.basepoint.Filter.in;
import static dev.horizon.basepoint.Filter.isNull;
import static dev.horizon.basepoint.Filter.lt;
import static dev.horizon.basepoint.Filter.or;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.horizon.basepoint.Direction;
import dev.horizon.basepoint.Filter;
import dev.horizon.cinder.Sort;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

public abstract class ReadContractTest extends ContractTest {

  static Stream<Arguments> filters() {
    return Stream.of(
        Arguments.of(eq("state", "OPEN"), List.of(1L, 3L, 5L)),
        Arguments.of(gt("total_cents", 15000L), List.of(3L, 5L)),
        Arguments.of(and(eq("customer_id", "anne"), eq("state", "PAID")), List.of(2L)),
        Arguments.of(or(eq("customer_id", "carl"), lt("total_cents", 6000L)), List.of(4L, 5L)),
        Arguments.of(in("id", List.of(2L, 4L)), List.of(2L, 4L)),
        Arguments.of(isNull("cancel_time"), List.of(1L, 2L, 3L, 5L)),
        Arguments.of(gt("create_time", day(2)), List.of(4L, 5L)),
        Arguments.of(Filter.not(eq("state", "OPEN")), List.of(2L, 4L)));
  }

  @ParameterizedTest
  @MethodSource("filters")
  void findsTheRowsOfAFilter(Filter filter, List<Long> expected) {
    assertThat(ids(orders.find(filter).orderBy("id").list().await())).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', textBlock = """
      state = "OPEN"                                 | 1,3,5
      total_cents > 15000                            | 3,5
      customer_id = "anne" AND state != "OPEN"       | 2
      state = "OPEN" OR total_cents < 6000           | 1,3,4,5
      create_time > "2026-01-03T10:00:00Z"           | 4,5
      create_time >= "2026-01-03T10:00:00Z"          | 3,4,5
      NOT state = "OPEN"                             | 2,4
      """)
  void findsTheRowsOfATextFilter(String filter, String expected) {
    assertThat(ids(orders.find(filter).orderBy("id").list().await())).isEqualTo(longs(expected));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', textBlock = """
      total_cents desc                 | 3,5,1,2,4
      customer_id, total_cents desc    | 1,2,3,4,5
      state desc, id                   | 2,1,3,5,4
      create_time desc                 | 5,4,3,2,1
      """)
  void sortsAsAnOrder(String orderBy, String expected) {
    assertThat(ids(orders.findAll().orderBy(orderBy).list().await())).isEqualTo(longs(expected));
  }

  @Test
  void sortsBySorts() {
    assertThat(ids(orders.findAll().sort(Sort.by("customer_id").descending().and("id")).list().await()))
        .containsExactly(5L, 3L, 4L, 1L, 2L);
  }

  @Test
  void limitsAndSkipsRows() {
    assertThat(ids(orders.findAll().orderBy("id").limit(2).offset(1).list().await())).containsExactly(2L, 3L);
  }

  @Test
  void readsEveryFieldOfARow() {
    Shop.Order order = orders.findById(4L).await().orElseThrow();
    assertThat(order).isEqualTo(new Shop.Order(4L, "beth", "CANCELLED", 5000L, 2, day(3), day(4), null,
        List.of()));
  }

  @Test
  void findsByKeys() {
    assertThat(orders.findById(9L).await()).isEmpty();
    assertThat(ids(orders.findAllById(List.of(1L, 5L, 9L)).await())).containsExactlyInAnyOrder(1L, 5L);
    assertThat(orders.existsById(3L).await()).isTrue();
    assertThat(orders.existsById(9L).await()).isFalse();
  }

  @Test
  void countsAndTellsIfRowsExist() {
    assertThat(orders.count().await()).isEqualTo(5L);
    assertThat(orders.find(eq("state", "OPEN")).count().await()).isEqualTo(3L);
    assertThat(orders.find(eq("state", "LOST")).exists().await()).isFalse();
  }

  @Test
  void readsTheFirstAndTheSingleRow() {
    assertThat(orders.findAll().orderBy("total_cents desc").first().await()).map(Shop.Order::id).contains(3L);
    assertThat(orders.find(eq("customer_id", "carl")).single().await().id()).isEqualTo(5L);
    assertThat(failure(orders.find(eq("customer_id", "dora")).single())).isInstanceOf(NoSuchElementException.class);
    assertThat(failure(orders.find(eq("customer_id", "anne")).single())).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void readsOnlyTheFieldsOfAMask() {
    Shop.Order order = orders.find(eq("id", 1L)).mask("id,state").single().await();
    assertThat(order.id()).isEqualTo(1L);
    assertThat(order.state()).isEqualTo("OPEN");
    assertThat(order.customerId()).isNull();
    assertThat(order.totalCents()).isNull();
  }

  @Test
  void refusesAFieldThatTheModelDoesNotHave() {
    assertThatThrownBy(() -> orders.find(eq("colour", "red"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> orders.findAll().orderBy("colour")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> orders.findAll().sort(Sort.by("colour"))).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', textBlock = """
      total_cents = 10.5           | total_cents
      create_time > "yesterday"    | create_time
      """)
  void refusesALiteralThatIsNotAValueOfTheField(String filter, String field) {
    assertThat(failure(orders.find(filter).list())).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(field);
  }

  @Test
  void namesAFieldByItsColumnToo() {
    assertThat(ids(orders.find(gt("created_at", day(3))).list().await())).containsExactly(5L);
  }

  @Test
  void sortsInBothDirections() {
    assertThat(ids(orders.findAll().sort(new Sort(List.of(new Sort.Order("id", Direction.DESC)))).list().await()))
        .containsExactly(5L, 4L, 3L, 2L, 1L);
  }

  private static List<Long> longs(String text) {
    return Arrays.stream(text.split(",")).map(Long::valueOf).toList();
  }
}

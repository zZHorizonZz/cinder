package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.filter.Filter.and;
import static dev.horizon.basepoint.filter.Filter.eq;
import static dev.horizon.basepoint.filter.Filter.in;
import static dev.horizon.basepoint.filter.Filter.or;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.horizon.aquery.aip158.InvalidPageTokenException;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.aquery.aip160.InvalidFilterException;
import dev.horizon.basepoint.filter.Filter;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public abstract class InputContractTest extends ContractTest {

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "Robert'); DROP TABLE customers;--", "O'Brien", "\"quoted\"", "back\\slash",
      "per%cent_under", "$1 ? :name" })
  @DisplayName("Stores a value that reads as SQL as text, and finds it")
  void sqlValue(String name) throws TimeoutException {
    await(customers.insert(new Shop.Customer("dora", "dora@example.com", name)));

    assertThat(await(customers.find("dora"))).map(Shop.Customer::name).contains(name);
    assertThat(await(customers.list(customers.query().where(eq("name", name))))).extracting(Shop.Customer::id)
        .containsExactly("dora");
    assertThat(await(customers.count(customers.query()))).isEqualTo(4L);
  }

  @Test
  @DisplayName("Finds a value with quotes by a filter text")
  void quotedText() throws TimeoutException {
    await(customers.insert(new Shop.Customer("dora", "dora@example.com", "O'Brien \"Dee\"")));

    assertThat(await(customers.list(customers.query().where("name = \"O'Brien \\\"Dee\\\"\""))))
        .extracting(Shop.Customer::id).containsExactly("dora");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "Zoë", "Łódź", "東京", "Ж", "café", "café", "noodles 🍜" })
  @DisplayName("Stores the text of every script, and finds it")
  void script(String name) throws TimeoutException {
    await(customers.insert(new Shop.Customer("dora", "dora@example.com", name)));

    assertThat(await(customers.find("dora"))).map(Shop.Customer::name).contains(name);
    assertThat(await(customers.list(customers.query().where(eq("name", name))))).extracting(Shop.Customer::id)
        .containsExactly("dora");
  }

  @Test
  @DisplayName("Stores an empty text, which is no null")
  void emptyText() throws TimeoutException {
    await(customers.insert(new Shop.Customer("dora", "dora@example.com", "")));

    assertThat(await(customers.find("dora"))).map(Shop.Customer::name).contains("");
    assertThat(await(customers.list(customers.query().where(eq("name", ""))))).extracting(Shop.Customer::id)
        .containsExactly("dora");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "name\" = name OR \"1", "id; DROP TABLE customers", "id--", "*" })
  @DisplayName("Refuses a name of a field that is SQL")
  void sqlField(String field) throws TimeoutException {
    String message = "The model of the table customers has no field " + field + ".";

    assertThat(failure(customers.list(customers.query().where(eq(field, "x"))))).hasMessage(message);
    assertThat(failure(customers.list(customers.query().orderBy(field)))).hasMessage(message);
    assertThat(failure(customers.execute(customers.update().set(field, "x").all()))).hasMessage(message);
    assertThat(await(customers.count(customers.query()))).isEqualTo(3L);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "name = \"x\" OR 1 = 1", "name = \"x\"; DROP TABLE customers" })
  @DisplayName("Refuses a filter text that is not AIP-160")
  void sqlFilter(String filter) throws TimeoutException {
    assertThatThrownBy(() -> customers.query(filter, "")).isInstanceOf(InvalidFilterException.class);
    assertThat(await(customers.count(customers.query()))).isEqualTo(3L);
  }

  @Test
  @DisplayName("Finds the rows of a filter nested 40 levels deep")
  void deepFilter() throws TimeoutException {
    Filter filter = eq("id", 3L);
    for (int depth = 0; depth < 40; depth++) {
      filter = depth % 2 == 0 ? or(filter, eq("id", 100L + depth)) : and(filter, Filter.not(eq("state", "LOST")));
    }

    assertThat(ids(orders.query().where(filter))).containsExactly(3L);
  }

  @Test
  @DisplayName("Finds the rows of a filter text nested 30 levels deep, and refuses one of 65")
  void deepFilterText() throws TimeoutException {
    assertThat(ids(orders.query("(".repeat(30) + "id = 3" + ")".repeat(30), ""))).containsExactly(3L);
    assertThatThrownBy(() -> orders.query("(".repeat(65) + "id = 3" + ")".repeat(65), ""))
        .isInstanceOf(InvalidFilterException.class);
  }

  @Test
  @DisplayName("Finds the rows of a list of 1000 values")
  void longList() throws TimeoutException {
    assertThat(ids(orders.query().where(in("id", LongStream.rangeClosed(1, 1000).boxed().toList()))))
        .containsExactly(1L, 2L, 3L, 4L, 5L);
  }

  @Test
  @DisplayName("Refuses a page token with a changed character")
  void changedToken() throws TimeoutException {
    String token = await(orders.page(orders.query(), new PageRequest("", "", 2, ""))).nextPageToken();
    int at = token.length() / 2;
    String changed = token.substring(0, at) + (token.charAt(at) == 'A' ? 'B' : 'A') + token.substring(at + 1);

    assertThat(failure(orders.page(orders.query(), new PageRequest("", "", 2, changed))))
        .isInstanceOf(InvalidPageTokenException.class).hasMessageContaining("not a token this service issued");
  }

  @Test
  @DisplayName("Finds no rows of a list of no values")
  void emptyList() throws TimeoutException {
    assertThat(ids(orders.query().where(in("id", List.of())))).isEmpty();
  }
}

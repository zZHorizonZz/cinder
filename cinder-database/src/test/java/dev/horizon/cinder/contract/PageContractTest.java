package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.Direction.DESC;
import static dev.horizon.basepoint.expression.Expression.column;
import static dev.horizon.basepoint.expression.Expression.upper;
import static dev.horizon.basepoint.filter.Filter.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.horizon.aquery.aip132.InvalidOrderByException;
import dev.horizon.aquery.aip158.InvalidPageTokenException;
import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.aquery.aip160.InvalidFilterException;
import dev.horizon.basepoint.Query;
import dev.horizon.cinder.Database;
import dev.horizon.cinder.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public abstract class PageContractTest extends ContractTest {

  @ParameterizedTest(name = "filter {0}, order {1}, size {2}")
  @CsvSource(delimiter = '|', textBlock = """
      ''               | ''                     | 2 | 1,2,3,4,5
      ''               | total_cents desc       | 2 | 3,5,1,2,4
      state = "OPEN"   | ''                     | 1 | 1,3,5
      ''               | customer_id desc, id   | 3 | 5,3,4,1,2
      ''               | create_time desc       | 4 | 5,4,3,2,1
      """)
  @DisplayName("Lists every row once, page by page")
  void pages(String filter, String orderBy, int size, String expected) throws TimeoutException {
    assertThat(listed(orders, orders.query(), filter, orderBy, size)).isEqualTo(longs(expected));
  }

  @Test
  @DisplayName("Joins the filter of the query to that of the request")
  void queryFilter() throws TimeoutException {
    Page<Shop.Order> page = await(orders.page(orders.query().where(eq("customer_id", "anne")), new PageRequest(
        "state = \"PAID\"", "", 10, "")));

    assertThat(ids(page.items())).containsExactly(2L);
    assertThat(page.nextPageToken()).isEmpty();
  }

  @Test
  @DisplayName("Orders by the order of the query, then by that of the request")
  void queryOrder() throws TimeoutException {
    assertThat(listed(orders, orders.query().orderBy("customer_id", DESC), "", "total_cents", 2))
        .containsExactly(5L, 4L, 3L, 2L, 1L);
  }

  @Test
  @DisplayName("Holds at most the most rows of a page that the database allows")
  void maxSize() throws TimeoutException {
    Table<Shop.Order> capped = Database.builder(pool, driver).extension(new Shop()).maxPageSize(2).build()
        .table(Shop.ORDER);

    Page<Shop.Order> page = await(capped.page(capped.query(), new PageRequest("", "id", 100, "")));

    assertThat(ids(page.items())).containsExactly(1L, 2L);
    assertThat(ids(await(capped.page(capped.query(), new PageRequest("", "id", 100, page.nextPageToken())))
        .items())).containsExactly(3L, 4L);
  }

  @Test
  @DisplayName("Refuses a most rows of a page below one")
  void refuseMaxSize() {
    assertThatThrownBy(() -> Database.builder(pool, driver).maxPageSize(0))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("A page holds at least one row, not 0.");
  }

  @Test
  @DisplayName("Refuses a token of another list, or a token that the database did not give")
  void refuseToken() throws TimeoutException {
    String token = await(orders.page(orders.query(), new PageRequest("", "", 2, ""))).nextPageToken();

    assertThat(failure(orders.page(orders.query(), new PageRequest("state = \"OPEN\"", "", 2, token))))
        .isInstanceOf(InvalidPageTokenException.class).hasMessageContaining("issued for different arguments");
    assertThat(failure(orders.page(orders.query().where(eq("state", "OPEN")), new PageRequest("", "", 2, token))))
        .isInstanceOf(InvalidPageTokenException.class).hasMessageContaining("issued for different arguments");
    assertThat(failure(orders.page(orders.query(), new PageRequest("", "", 2, "forged"))))
        .isInstanceOf(InvalidPageTokenException.class).hasMessageContaining("not a token this service issued");
  }

  @Test
  @DisplayName("Refuses a filter or an order of the request that does not fit the model")
  void refuseRequest() throws TimeoutException {
    assertThat(failure(orders.page(orders.query(), new PageRequest("budget > 3", "", 2, ""))))
        .isInstanceOf(InvalidFilterException.class).hasMessageContaining("has no field budget");
    assertThat(failure(orders.page(orders.query(), new PageRequest("", "budget", 2, ""))))
        .isInstanceOf(InvalidOrderByException.class).hasMessageContaining("has no field budget");
    assertThat(failure(orders.page(orders.query(), new PageRequest("", "state.name", 2, ""))))
        .isInstanceOf(InvalidOrderByException.class).hasMessageContaining("not the path state.name");
  }

  @Test
  @DisplayName("Refuses a query with an offset or an order by an expression")
  void refuseQuery() throws TimeoutException {
    assertThat(failure(orders.page(orders.query().offset(1), new PageRequest("", "", 2, ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A page starts after the row of its token, so its query has no offset.");
    assertThat(failure(orders.page(orders.query().orderBy(upper(column("state")), DESC), new PageRequest("", "", 2,
        "")))).isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("A page orders by fields, so that its token can hold the values of the last row");
  }

  private List<Long> listed(Table<Shop.Order> table, Query query, String filter, String orderBy, int size)
      throws TimeoutException {
    List<Long> listed = new ArrayList<>();
    String token = "";
    do {
      Page<Shop.Order> page = await(table.page(query, new PageRequest(filter, orderBy, size, token)));
      assertThat(page.items()).hasSizeLessThanOrEqualTo(size);
      listed.addAll(ids(page.items()));
      token = page.nextPageToken();
    } while (!token.isEmpty());
    return listed;
  }
}

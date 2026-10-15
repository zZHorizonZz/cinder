package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.Filter.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.horizon.aquery.InvalidQueryException;
import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.cinder.Database;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public abstract class PageContractTest extends ContractTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', textBlock = """
      ''               | ''                     | 2 | 1,2,3,4,5
      ''               | total_cents desc       | 2 | 3,5,1,2,4
      state = "OPEN"   | ''                     | 1 | 1,3,5
      ''               | customer_id desc, id   | 3 | 5,3,4,1,2
      ''               | create_time desc       | 4 | 5,4,3,2,1
      """)
  void listsEveryRowPageByPage(String filter, String orderBy, int size, String expected) {
    List<Long> listed = new ArrayList<>();
    String token = "";
    do {
      Page<Shop.Order> page = orders.list(new PageRequest(filter, orderBy, size, token)).await();
      assertThat(page.items().size()).isLessThanOrEqualTo(size);
      listed.addAll(ids(page.items()));
      token = page.nextPageToken();
    } while (!token.isEmpty());
    assertThat(listed).containsExactlyElementsOf(java.util.Arrays.stream(expected.split(",")).map(Long::valueOf)
        .toList());
  }

  @Test
  void joinsTheFilterOfTheQuery() {
    Page<Shop.Order> page = orders.find(eq("customer_id", "anne")).page(new PageRequest("state = \"PAID\"",
        "", 10, "")).await();
    assertThat(ids(page.items())).containsExactly(2L);
    assertThat(page.nextPageToken()).isEmpty();
  }

  @Test
  void holdsAtMostTheMaxPageSize() {
    Shop.Orders capped = new Shop.Orders(Database.builder(pool, driver).extension(new Shop()).maxPageSize(2)
        .build());
    Page<Shop.Order> page = capped.list(new PageRequest("", "id", 100, "")).await();
    assertThat(ids(page.items())).containsExactly(1L, 2L);
    assertThat(ids(capped.list(new PageRequest("", "id", 100, page.nextPageToken())).await().items()))
        .containsExactly(3L, 4L);
    assertThatThrownBy(() -> Database.builder(pool, driver).maxPageSize(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesATokenOfAnotherList() {
    String token = orders.list(new PageRequest("", "", 2, "")).await().nextPageToken();
    assertThat(failure(orders.list(new PageRequest("state = \"OPEN\"", "", 2, token))))
        .isInstanceOf(InvalidQueryException.class);
    assertThat(failure(orders.list(new PageRequest("", "", 2, "forged")))).isInstanceOf(InvalidQueryException.class);
  }
}

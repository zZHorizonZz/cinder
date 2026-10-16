package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.Filter.eq;
import static org.assertj.core.api.Assertions.assertThat;

import dev.horizon.cinder.Aggregate;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

public abstract class AggregateContractTest extends ContractTest {

  @Test
  void measuresTheGroupsOfTheRows() throws TimeoutException {
    List<Aggregate.Group> groups = orders.find(eq("state", "OPEN"))
        .run(Aggregate.by("customer_id").sum("total_cents").count()).await(TIMEOUT);
    assertThat(groups.stream().sorted(Comparator.comparing(group -> (String) group.keys().get("customer_id")))
        .map(group -> group.keys().get("customer_id") + "=" + number(group.values().get("sum_total_cents")) + "/"
            + number(group.values().get("count"))))
        .containsExactly("anne=15000/1", "beth=30000/1", "carl=22000/1");
  }

  @Test
  void measuresEveryRowAsOneGroup() throws TimeoutException {
    List<Aggregate.Group> groups = orders.findAll().run(Aggregate.by().min("total_cents").max("total_cents")
        .count()).await(TIMEOUT);
    assertThat(groups).hasSize(1);
    assertThat(number(groups.getFirst().values().get("min_total_cents"))).isEqualTo(5000L);
    assertThat(number(groups.getFirst().values().get("max_total_cents"))).isEqualTo(30000L);
    assertThat(number(groups.getFirst().values().get("count"))).isEqualTo(5L);
  }

  private static long number(Object value) {
    return ((Number) value).longValue();
  }
}

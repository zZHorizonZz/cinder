package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.expression.Expression.column;
import static dev.horizon.basepoint.expression.Expression.count;
import static dev.horizon.basepoint.expression.Expression.max;
import static dev.horizon.basepoint.expression.Expression.min;
import static dev.horizon.basepoint.expression.Expression.sum;
import static dev.horizon.basepoint.filter.Filter.eq;
import static dev.horizon.basepoint.filter.Filter.gt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public abstract class AggregateContractTest extends ContractTest {

  @Test
  @DisplayName("Measures each group of the rows of a filter")
  void groups() throws TimeoutException {
    RowSet<Row> groups = await(orders.rows(orders.query().where(eq("state", "OPEN"))
        .select("customer_id", column("customer_id"))
        .select("total", sum("total_cents"))
        .select("orders", count())
        .groupBy("customer_id")
        .orderBy("customer_id")));

    assertThat(measures(groups, "customer_id", "total", "orders")).containsExactly(tuple("anne", 15000L, 1L),
        tuple("beth", 30000L, 1L), tuple("carl", 22000L, 1L));
  }

  @Test
  @DisplayName("Measures every row as one group")
  void oneGroup() throws TimeoutException {
    RowSet<Row> groups = await(orders.rows(orders.query()
        .select("least", min("total_cents"))
        .select("most", max("total_cents"))
        .select("orders", count())));

    assertThat(measures(groups, "least", "most", "orders")).containsExactly(tuple(5000L, 30000L, 5L));
  }

  @Test
  @DisplayName("Measures the rows of a filter of a field of another column name")
  void columnName() throws TimeoutException {
    RowSet<Row> groups = await(orders.rows(orders.query().select("orders", count()).where(gt("create_time",
        day(2)))));

    assertThat(measures(groups, "orders")).containsExactly(tuple(2L));
  }

  @Test
  @DisplayName("Refuses a measure of a field that the model does not have")
  void refuseField() throws TimeoutException {
    assertThat(failure(orders.rows(orders.query().select("total", sum("budget")))))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("The model of the table orders has no field budget.");
  }

  private static List<Tuple> measures(RowSet<Row> rows, String... names) {
    List<Tuple> measured = new ArrayList<>();
    for (Row row : rows) {
      Object[] values = new Object[names.length];
      for (int i = 0; i < names.length; i++) {
        Object value = row.getValue(names[i]);
        values[i] = value instanceof Number number ? (Object) number.longValue() : value;
      }
      measured.add(tuple(values));
    }
    return measured;
  }
}

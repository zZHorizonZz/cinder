package dev.horizon.cinder;

import dev.horizon.basepoint.Clause;
import dev.horizon.basepoint.Expression;
import dev.horizon.basepoint.Statement;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Groups the rows of a query and measures each group, as {@code GROUP BY} does. Without fields to group by, the whole
 * query is one group.
 *
 * <pre>{@code
 * orders.find(eq("state", "PAID")).run(Aggregate.by("customer").sum("total_cents").count())
 * }</pre>
 *
 * <p>
 * It is a terminal like any an extension can write: it makes its statement of the statement of the query, and keeps
 * its conditions.
 */
public final class Aggregate implements Terminal<Object, Future<List<Aggregate.Group>>> {

  private static final String COUNT = "count";

  private final List<String> by;
  private final List<Measure> measures;

  private Aggregate(List<String> by, List<Measure> measures) {
    this.by = List.copyOf(by);
    this.measures = List.copyOf(measures);
  }

  /**
   * One group and its measures.
   *
   * @param keys the values of the fields of the group, by field name
   * @param values the measures, by name: {@code count}, or the function and the field, such as
   * {@code sum_total_cents}
   */
  public record Group(Map<String, Object> keys, Map<String, Object> values) {

    public Group {
      keys = Map.copyOf(keys);
      values = Map.copyOf(values);
    }
  }

  private record Measure(String name, String field, Function<String, Expression> expression) {
  }

  /**
   * Groups the rows by fields.
   *
   * @param fields the fields, such as {@code customer}, or none for one group of every row
   * @return the aggregate, without measures
   */
  public static Aggregate by(String... fields) {
    return new Aggregate(List.of(fields), List.of());
  }

  /**
   * Counts the rows of each group, as the measure {@code count}.
   *
   * @return the aggregate, with the measure
   */
  public Aggregate count() {
    return measure(new Measure(COUNT, null, ignored -> Expression.count()));
  }

  /**
   * Sums a field in each group, as the measure {@code sum_<field>}.
   *
   * @param field the field, such as {@code total_cents}
   * @return the aggregate, with the measure
   */
  public Aggregate sum(String field) {
    return measure(new Measure("sum_" + field, field, Expression::sum));
  }

  /**
   * Averages a field in each group, as the measure {@code avg_<field>}.
   *
   * @param field the field
   * @return the aggregate, with the measure
   */
  public Aggregate avg(String field) {
    return measure(new Measure("avg_" + field, field, Expression::avg));
  }

  /**
   * Gives the least value of a field in each group, as the measure {@code min_<field>}.
   *
   * @param field the field
   * @return the aggregate, with the measure
   */
  public Aggregate min(String field) {
    return measure(new Measure("min_" + field, field, Expression::min));
  }

  /**
   * Gives the greatest value of a field in each group, as the measure {@code max_<field>}.
   *
   * @param field the field
   * @return the aggregate, with the measure
   */
  public Aggregate max(String field) {
    return measure(new Measure("max_" + field, field, Expression::max));
  }

  /**
   * Groups the rows of the query and measures each group.
   *
   * @param execution the query
   * @return the groups, with the values as the database gives them, without the codecs of the fields
   * @throws IllegalArgumentException in the future if a field is not in the model
   */
  @Override
  public Future<List<Group>> run(Execution<?> execution) {
    Statement query = execution.statement();
    List<Expression> columns = new ArrayList<>();
    List<Expression> groups = new ArrayList<>();
    try {
      for (String field : by) {
        Expression column = Expression.column(column(execution, field));
        groups.add(column);
        columns.add(new Expression.As(column, field));
      }
      for (Measure measure : measures) {
        columns.add(new Expression.As(measure.expression().apply(measure.field() == null ? null
            : column(execution, measure.field())), measure.name()));
      }
    } catch (IllegalArgumentException unknown) {
      return Future.failedFuture(unknown);
    }
    List<Clause> clauses = new ArrayList<>(List.of(new Clause.Select(false, columns), new Clause.From()));
    query.find(Clause.Where.class).ifPresent(clauses::add);
    if (!groups.isEmpty()) {
      clauses.add(new Clause.GroupBy(groups));
    }
    return execution.run(new Statement(query.source(), clauses)).map(rows -> {
      List<Group> found = new ArrayList<>();
      for (Row row : rows) {
        found.add(new Group(values(row, by), values(row, measures.stream().map(Measure::name).toList())));
      }
      return found;
    });
  }

  private Aggregate measure(Measure measure) {
    List<Measure> more = new ArrayList<>(measures);
    more.add(measure);
    return new Aggregate(by, more);
  }

  private String column(Execution<?> execution, String field) {
    return execution.model().field(field).orElseThrow(() -> new IllegalArgumentException("the model of "
        + execution.model().table() + " has no field " + field)).column();
  }

  private Map<String, Object> values(Row row, List<String> names) {
    Map<String, Object> values = new LinkedHashMap<>();
    names.forEach(name -> values.put(name, row.getValue(name)));
    return values;
  }
}

package dev.horizon.cinder.impl;

import dev.horizon.aquery.aip132.FieldPath;
import dev.horizon.basepoint.Clause;
import dev.horizon.basepoint.CollectionReference;
import dev.horizon.basepoint.Expression;
import dev.horizon.basepoint.Filter;
import dev.horizon.basepoint.Statement;
import dev.horizon.basepoint.UpdateQuery;
import dev.horizon.cinder.Assignments;
import dev.horizon.cinder.Driver;
import dev.horizon.cinder.Field;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.Part;
import dev.horizon.cinder.TypeCodec;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A model as a database runs it: the codecs of each field, the parts of every operation, and the rewrite of the
 * statements of its operations.
 *
 * <p>
 * The statements of an operation name the fields of the model in their expressions, and the columns in their clauses
 * of names, such as {@code RETURNING} and {@code SET}. The rewrite, after the parts, turns the fields of the
 * expressions into their columns and binds the values of the filters through the codecs of their fields. A name that
 * is a field and another field's column means the field. A key of a JSON object names a field that the database
 * stores as a JSON object, such as a map or a message.
 */
final class Table<E> {

  private final Model<E> model;
  private final Driver driver;
  private final CollectionReference collection;
  private final List<Part> parts;
  private final List<Binding> bindings = new ArrayList<>();
  private final Map<String, Binding> names = new LinkedHashMap<>();
  private final List<Binding> key;
  private final String[] columns;

  Table(Model<E> model, Driver driver, List<TypeCodec> codecs, List<Part> parts) {
    this.model = model;
    this.driver = driver;
    this.collection = driver.dialect().collection(model.table());
    this.parts = List.copyOf(parts);
    for (Field<E, ?> field : model.fields()) {
      Binding binding = Binding.of(field, codecs);
      bindings.add(binding);
      names.putIfAbsent(field.column(), binding);
    }
    bindings.forEach(binding -> names.put(binding.name(), binding));
    this.key = model.key().stream().map(field -> binding(field.name())).toList();
    this.columns = bindings.stream().map(Binding::column).toArray(String[]::new);
  }

  Model<E> model() {
    return model;
  }

  CollectionReference collection() {
    return collection;
  }

  List<Part> parts() {
    return parts;
  }

  List<Binding> bindings() {
    return bindings;
  }

  List<Binding> key() {
    return key;
  }

  /** Gives every column of the table, in the order of the fields, as {@code RETURNING} names them. */
  String[] columns() {
    return columns.clone();
  }

  Binding binding(String name) {
    Binding binding = names.get(name);
    if (binding == null) {
      throw new IllegalArgumentException("The model of the table " + model.table() + " has no field " + name + ".");
    }
    return binding;
  }

  /**
   * Gives the field of a path of a mask or an order.
   *
   * @param what what the path is of, such as {@code A read mask}, for the message
   * @throws IllegalArgumentException if the path has more than one segment, or names no field of the model
   */
  Binding binding(FieldPath path, String what) {
    if (path.segments().size() != 1) {
      throw new IllegalArgumentException(what + " of the table " + model.table() + " names fields of the table, not"
          + " the path " + path + ".");
    }
    return binding(path.segments().getFirst());
  }

  /** Gives a reader of rows, which finds the columns of the fields in the first row and reuses them. */
  Function<Row, Map<String, Object>> values() {
    return new Function<>() {
      private int[] positions;

      @Override
      public Map<String, Object> apply(Row row) {
        if (positions == null) {
          positions = bindings.stream().mapToInt(binding -> row.getColumnIndex(binding.column())).toArray();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < positions.length; i++) {
          if (positions[i] >= 0) {
            values.put(bindings.get(i).name(), bindings.get(i).read(driver, row.getValue(positions[i]), false));
          }
        }
        return values;
      }
    };
  }

  /** Gives a reader of rows as entities. */
  Function<Row, E> reader() {
    Function<Row, Map<String, Object>> values = values();
    return row -> model.assemble(values.apply(row));
  }

  /** Gives the row of an entity: a value for each field. */
  Assignments row(E entity) {
    List<Assignments.Change> changes = new ArrayList<>(bindings.size());
    for (Binding binding : bindings) {
      changes.add(new Assignments.SetValue(binding.name(), binding.get(entity)));
    }
    return new Assignments(changes);
  }

  /** Gives the values of a row of an insert, by column: a bound value, or the expression of a value to compute. */
  Map<String, Object> values(Assignments row) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (Map.Entry<Binding, Assignments.Change> entry : last(row).entrySet()) {
      Binding binding = entry.getKey();
      switch (entry.getValue()) {
        case Assignments.SetValue set -> values.put(binding.column(), binding.bind(driver, set.value()));
        case Assignments.Compute compute -> values.put(binding.column(), compute.expression());
        case Assignments.Unset unset -> {
        }
        case Assignments.Increment increment -> throw new IllegalArgumentException("An insert cannot increment the"
            + " field " + increment.field() + ", because the row has no value yet.");
      }
    }
    return values;
  }

  /** Sets the changes of a row of an update, by column. */
  UpdateQuery set(UpdateQuery update, Assignments row) {
    UpdateQuery set = update;
    for (Map.Entry<Binding, Assignments.Change> entry : last(row).entrySet()) {
      Binding binding = entry.getKey();
      set = set.set(binding.column(), switch (entry.getValue()) {
        case Assignments.SetValue value -> binding.bind(driver, value.value());
        case Assignments.Compute compute -> compute.expression();
        case Assignments.Increment increment -> new Expression.Plus(Expression.column(binding.name()),
            Expression.value(driver.bind(increment.amount())));
        case Assignments.Unset unset -> binding.field().nullable() ? binding.bind(driver, null) : new Default();
      });
    }
    return set;
  }

  /** Rewrites a statement: its fields into their columns, and the values of its filters through the codecs. */
  Statement rewrite(Statement statement) {
    return new Statement(statement.source(), statement.clauses().stream().map(this::clause).toList());
  }

  /** Checks that the names of a filter are fields of the model, without binding its values. */
  void check(Filter filter) {
    rewrite(filter, false);
  }

  private Map<Binding, Assignments.Change> last(Assignments row) {
    Map<Binding, Assignments.Change> last = new LinkedHashMap<>();
    for (Assignments.Change change : row.changes()) {
      Binding binding = binding(change.field());
      last.remove(binding);
      last.put(binding, change);
    }
    return last;
  }

  private Filter rewrite(Filter filter, boolean bind) {
    return switch (filter) {
      case Filter.Compare compare -> new Filter.Compare(expression(compare.left()), compare.operator(),
          value(compare.left(), compare.right(), bind));
      case Filter.IsNull isNull -> new Filter.IsNull(expression(isNull.operand()), isNull.negated());
      case Filter.In in -> new Filter.In(expression(in.operand()), bind ? in.values().stream()
          .map(value -> bind(in.operand(), value)).toList() : in.values(), in.negated());
      case Filter.Between between -> new Filter.Between(expression(between.operand()),
          value(between.operand(), between.low(), bind), value(between.operand(), between.high(), bind));
      case Filter.Match match -> new Filter.Match(expression(match.operand()), match.parts(), match.negated());
      case Filter.Like like -> new Filter.Like(expression(like.operand()), like.pattern());
      case Filter.And and -> new Filter.And(and.operands().stream().map(operand -> rewrite(operand, bind)).toList());
      case Filter.Or or -> new Filter.Or(or.operands().stream().map(operand -> rewrite(operand, bind)).toList());
      case Filter.Not not -> new Filter.Not(rewrite(not.operand(), bind));
      default -> filter;
    };
  }

  private Clause clause(Clause clause) {
    return switch (clause) {
      case Clause.Select select -> new Clause.Select(select.distinct(), select.columns().stream()
          .map(this::expression).toList());
      case Clause.Where where -> new Clause.Where(rewrite(where.filter(), true));
      case Clause.GroupBy groupBy -> new Clause.GroupBy(groupBy.columns().stream().map(this::expression).toList());
      case Clause.Having having -> new Clause.Having(rewrite(having.filter(), true));
      case Clause.OrderBy orderBy -> new Clause.OrderBy(orderBy.sorts().stream()
          .map(sort -> new Clause.Sort(expression(sort.value()), sort.direction())).toList());
      case Clause.Set set -> new Clause.Set(set.assignments().stream()
          .map(assignment -> new Clause.Assignment(assignment.column(), expression(assignment.value()))).toList());
      case Clause.Values values -> new Clause.Values(values.rows().stream()
          .map(row -> row.stream().map(this::expression).toList()).toList());
      default -> clause;
    };
  }

  private Expression expression(Expression expression) {
    return switch (expression) {
      case null -> null;
      case Expression.Column column -> Expression.column(binding(column.name()).column());
      case Expression.JsonKey key -> {
        Binding binding = binding(key.column());
        if (binding.stored() != JsonObject.class) {
          throw new IllegalArgumentException("The field " + key.column() + " of the model of the table " + model.table()
              + " is not stored as a JSON object, so it has no key " + key.key() + ".");
        }
        yield new Expression.JsonKey(binding.column(), key.key());
      }
      case Expression.Plus plus -> new Expression.Plus(expression(plus.left()), expression(plus.right()));
      case Expression.Aggregate aggregate -> new Expression.Aggregate(aggregate.function(),
          expression(aggregate.argument()));
      case Expression.As as -> new Expression.As(expression(as.value()), as.name());
      default -> expression;
    };
  }

  private Expression value(Expression operand, Expression value, boolean bind) {
    return switch (value) {
      case Expression.Value plain when bind -> new Expression.Value(bind(operand, plain.value()));
      case Expression.Value plain -> plain;
      default -> expression(value);
    };
  }

  private Object bind(Expression operand, Object value) {
    return operand instanceof Expression.Column column ? binding(column.name()).bind(driver, value)
        : driver.bind(value);
  }
}

package dev.horizon.cinder.impl;

import dev.horizon.aquery.aip132.FieldPath;
import dev.horizon.basepoint.CollectionReference;
import dev.horizon.basepoint.UpdateQuery;
import dev.horizon.basepoint.expression.Aggregate;
import dev.horizon.basepoint.expression.Arithmetic;
import dev.horizon.basepoint.expression.As;
import dev.horizon.basepoint.expression.Call;
import dev.horizon.basepoint.expression.Case;
import dev.horizon.basepoint.expression.Column;
import dev.horizon.basepoint.expression.Concat;
import dev.horizon.basepoint.expression.Expression;
import dev.horizon.basepoint.expression.JsonKey;
import dev.horizon.basepoint.expression.Length;
import dev.horizon.basepoint.expression.Shift;
import dev.horizon.basepoint.expression.Value;
import dev.horizon.basepoint.filter.And;
import dev.horizon.basepoint.filter.Between;
import dev.horizon.basepoint.filter.Compare;
import dev.horizon.basepoint.filter.Filter;
import dev.horizon.basepoint.filter.In;
import dev.horizon.basepoint.filter.IsNull;
import dev.horizon.basepoint.filter.Like;
import dev.horizon.basepoint.filter.Match;
import dev.horizon.basepoint.filter.Not;
import dev.horizon.basepoint.filter.Or;
import dev.horizon.basepoint.filter.Text;
import dev.horizon.basepoint.statement.Assignment;
import dev.horizon.basepoint.statement.Clause;
import dev.horizon.basepoint.statement.GroupBy;
import dev.horizon.basepoint.statement.Having;
import dev.horizon.basepoint.statement.OrderBy;
import dev.horizon.basepoint.statement.Select;
import dev.horizon.basepoint.statement.Sort;
import dev.horizon.basepoint.statement.Statement;
import dev.horizon.basepoint.statement.Values;
import dev.horizon.basepoint.statement.Where;
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
import java.util.Set;
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
        case Assignments.Increment increment -> Expression.add(Expression.column(binding.name()),
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
      case Compare compare -> new Compare(expression(compare.left()), compare.operator(),
          value(compare.left(), compare.right(), bind));
      case IsNull isNull -> new IsNull(expression(isNull.operand()), isNull.negated());
      case In in -> new In(expression(in.operand()), bind ? in.values().stream()
          .map(value -> bind(in.operand(), value)).toList() : in.values(), in.negated());
      case Between between -> new Between(expression(between.operand()),
          value(between.operand(), between.low(), bind), value(between.operand(), between.high(), bind));
      case Match match -> new Match(expression(match.operand()), match.parts(), match.negated());
      case Like like -> new Like(expression(like.operand()), like.pattern());
      case And and -> new And(and.operands().stream().map(operand -> rewrite(operand, bind)).toList());
      case Or or -> new Or(or.operands().stream().map(operand -> rewrite(operand, bind)).toList());
      case Not not -> new Not(rewrite(not.operand(), bind));
      case Text text -> rewrite(text.translate(Set.of()), bind);
      default -> filter;
    };
  }

  private Clause clause(Clause clause) {
    return switch (clause) {
      case Select select -> new Select(select.distinct(), select.columns().stream()
          .map(this::expression).toList());
      case Where where -> new Where(rewrite(where.filter(), true));
      case GroupBy groupBy -> new GroupBy(groupBy.columns().stream().map(this::expression).toList());
      case Having having -> new Having(rewrite(having.filter(), true));
      case OrderBy orderBy -> new OrderBy(orderBy.sorts().stream()
          .map(sort -> new Sort(expression(sort.value()), sort.direction())).toList());
      case dev.horizon.basepoint.statement.Assignments set ->
        new dev.horizon.basepoint.statement.Assignments(set.assignments().stream()
            .map(assignment -> new Assignment(assignment.column(), expression(assignment.value()))).toList());
      case Values values -> new Values(values.rows().stream()
          .map(row -> row.stream().map(this::expression).toList()).toList());
      default -> clause;
    };
  }

  private Expression expression(Expression expression) {
    return switch (expression) {
      case null -> null;
      case Column column -> Expression.column(binding(column.name()).column());
      case JsonKey key -> {
        Binding binding = binding(key.column());
        if (binding.stored() != JsonObject.class) {
          throw new IllegalArgumentException("The field " + key.column() + " of the model of the table " + model.table()
              + " is not stored as a JSON object, so it has no key " + key.key() + ".");
        }
        yield new JsonKey(binding.column(), key.key());
      }
      case Arithmetic arithmetic -> new Arithmetic(expression(arithmetic.left()), arithmetic.operator(),
          expression(arithmetic.right()));
      case Concat concat -> new Concat(concat.texts().stream().map(this::expression).toList());
      case Call call -> new Call(call.function(), call.arguments().stream().map(this::expression).toList());
      case Length length -> new Length(expression(length.text()));
      case Shift shift -> new Shift(expression(shift.timestamp()), shift.duration());
      case Case choice -> new Case(rewrite(choice.condition(), true), expression(choice.then()),
          expression(choice.otherwise()));
      case Aggregate aggregate -> new Aggregate(aggregate.function(), expression(aggregate.argument()));
      case As as -> new As(expression(as.value()), as.name());
      default -> expression;
    };
  }

  private Expression value(Expression operand, Expression value, boolean bind) {
    return switch (value) {
      case Value plain when bind -> new Value(bind(operand, plain.value()));
      case Value plain -> plain;
      default -> expression(value);
    };
  }

  private Object bind(Expression operand, Object value) {
    return operand instanceof Column column ? binding(column.name()).bind(driver, value)
        : driver.bind(value);
  }
}

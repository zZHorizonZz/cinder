package dev.horizon.cinder;

import dev.horizon.aquery.aip132.FieldPath;
import dev.horizon.basepoint.CollectionReference;
import dev.horizon.basepoint.expression.Aggregate;
import dev.horizon.basepoint.expression.AllColumns;
import dev.horizon.basepoint.expression.Arithmetic;
import dev.horizon.basepoint.expression.As;
import dev.horizon.basepoint.expression.Call;
import dev.horizon.basepoint.expression.Case;
import dev.horizon.basepoint.expression.Column;
import dev.horizon.basepoint.expression.Concat;
import dev.horizon.basepoint.expression.Expression;
import dev.horizon.basepoint.expression.JsonKey;
import dev.horizon.basepoint.expression.Length;
import dev.horizon.basepoint.expression.Null;
import dev.horizon.basepoint.expression.Reference;
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
import dev.horizon.basepoint.filter.Related;
import dev.horizon.basepoint.filter.Text;
import dev.horizon.basepoint.statement.Assignment;
import dev.horizon.basepoint.statement.Assignments;
import dev.horizon.basepoint.statement.Clause;
import dev.horizon.basepoint.statement.GroupBy;
import dev.horizon.basepoint.statement.Having;
import dev.horizon.basepoint.statement.Include;
import dev.horizon.basepoint.statement.InsertInto;
import dev.horizon.basepoint.statement.OnConflict;
import dev.horizon.basepoint.statement.OrderBy;
import dev.horizon.basepoint.statement.Returning;
import dev.horizon.basepoint.statement.Select;
import dev.horizon.basepoint.statement.Sort;
import dev.horizon.basepoint.statement.Source;
import dev.horizon.basepoint.statement.Statement;
import dev.horizon.basepoint.statement.Values;
import dev.horizon.basepoint.statement.Where;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The columns of a model: the binding of each field, and the rewrite of a statement that names fields into one that
 * names columns and holds the values that the driver sends.
 *
 * @param <E> the type of the entities
 */
final class Mapping<E> {

  private final Database database;
  private final Model<E> model;
  private final Driver driver;
  private final CollectionReference collection;
  private final List<Part> parts;
  private final List<Binding> bindings = new ArrayList<>();
  private final Map<String, Binding> names = new LinkedHashMap<>();
  private final List<Binding> key;

  Mapping(Database database, Model<E> model, List<TypeCodec> codecs, List<Part> parts) {
    this.database = database;
    this.model = model;
    this.driver = database.driver();
    this.collection = database.dialect().collection(model.table());
    this.parts = List.copyOf(parts);
    for (Field<E, ?> field : model.fields()) {
      Binding binding = Binding.of(field, codecs);
      bindings.add(binding);
      names.putIfAbsent(field.column(), binding);
    }
    bindings.forEach(binding -> names.put(binding.name(), binding));
    this.key = model.key().stream().map(field -> binding(field.name())).toList();
  }

  Model<E> model() {
    return model;
  }

  Driver driver() {
    return driver;
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

  String[] fields() {
    return bindings.stream().map(Binding::name).toArray(String[]::new);
  }

  Binding binding(String name) {
    Binding binding = names.get(name);
    if (binding == null) {
      throw new IllegalArgumentException("The model of the table " + model.table() + " has no field " + name + ".");
    }
    return binding;
  }

  Binding binding(FieldPath path, String what) {
    if (path.segments().size() != 1) {
      throw new IllegalArgumentException(what + " of the table " + model.table() + " names fields of the table, not"
          + " the path " + path + ".");
    }
    return binding(path.segments().getFirst());
  }

  /**
   * Checks that a statement reads or writes the table of the model.
   */
  void requireTable(Statement statement) {
    String table = switch (statement.source()) {
      case Source.Table read -> read.name();
      case Source.Target target -> target.name();
      default -> null;
    };
    if (!model.table().equals(table)) {
      throw new IllegalArgumentException("The statement " + (table == null ? "reads no table"
          : "is of the table "
              + table)
          + ", not of the table " + model.table() + " of the model.");
    }
  }

  /**
   * Checks the fields of a filter, without binding its values.
   */
  void check(Filter filter) {
    rewrite(filter, false, null);
  }

  /**
   * Gives a query that reads every field when it names none, and the fields that it names otherwise.
   */
  Statement expand(Statement statement) {
    Select select = statement.find(Select.class).orElseThrow();
    boolean named = select.columns().stream().anyMatch(column -> column instanceof Column
        || column instanceof AllColumns);
    List<Expression> columns = new ArrayList<>();
    if (!named) {
      bindings.forEach(binding -> columns.add(new Column(binding.name())));
    }
    for (Expression column : select.columns()) {
      if (column instanceof AllColumns) {
        bindings.forEach(binding -> columns.add(new Column(binding.name())));
      } else {
        columns.add(column);
      }
    }
    return statement.replace(select, new Select(select.distinct(), columns));
  }

  Statement rewrite(Statement statement) {
    return rewrite(statement, null);
  }

  private Statement rewrite(Statement statement, Mapping<?> outer) {
    List<Binding> insertColumns = statement.find(InsertInto.class).map(into -> into.columns().stream()
        .map(this::binding).toList()).orElse(List.of());
    Set<String> selectLabels = statement.find(Select.class).map(select -> select.columns().stream()
        .filter(As.class::isInstance).map(column -> ((As) column).name()).collect(Collectors.toSet()))
        .orElse(Set.of());
    List<Clause> clauses = new ArrayList<>();
    for (Clause clause : statement.clauses()) {
      clauses.add(switch (clause) {
        case Select select -> new Select(select.distinct(), select.columns().stream().map(this::mapSelectColumn).toList());
        case Where where -> new Where(rewrite(where.filter(), true, outer));
        case GroupBy groupBy -> new GroupBy(groupBy.columns().stream().map(this::expression).toList());
        case Having having -> new Having(rewrite(having.filter(), true, outer));
        case OrderBy orderBy -> new OrderBy(orderBy.sorts().stream()
            .map(sort -> new Sort(mapSort(sort.value(), selectLabels), sort.direction())).toList());
        case Assignments set -> new Assignments(set.assignments().stream().map(assignment -> {
          Binding binding = binding(assignment.column());
          return new Assignment(binding.column(), bindValue(binding, assignment.value()));
        }).toList());
        case InsertInto into -> new InsertInto(insertColumns.stream().map(Binding::column).toList());
        case Values values -> new Values(values.rows().stream().map(row -> bindRow(insertColumns, row)).toList());
        case OnConflict conflict -> new OnConflict(columns(conflict.key()), columns(conflict.updates()));
        case Returning returning -> new Returning(columns(returning.columns()));
        default -> clause;
      });
    }
    return new Statement(statement.source(), clauses);
  }

  private List<Expression> bindRow(List<Binding> columns, List<Expression> row) {
    List<Expression> values = new ArrayList<>(row.size());
    for (int i = 0; i < row.size(); i++) {
      values.add(bindValue(columns.get(i), row.get(i)));
    }
    return values;
  }

  private List<String> columns(List<String> fields) {
    return fields.stream().map(field -> binding(field).column()).toList();
  }

  private Expression bindValue(Binding binding, Expression value) {
    return switch (value) {
      case Value plain -> Expression.value(binding.bind(driver, plain.value()));
      case Null ignored -> Expression.value(binding.bind(driver, null));
      default -> expression(value);
    };
  }

  private Expression mapSort(Expression value, Set<String> selectLabels) {
    if (value instanceof Reference reference) {
      return selectLabels.contains(reference.name()) ? reference : new Column(binding(reference.name()).column());
    }
    return expression(value);
  }

  private Expression mapSelectColumn(Expression column) {
    if (column instanceof As(Include include, String name)) {
      return new As(include(include, name), name);
    }
    return expression(column);
  }

  private Include include(Include include, String name) {
    Relation<?> relation = relation(name);
    boolean one = relation.shape() == Relation.Shape.ONE;
    if (one != include instanceof Include.One) {
      throw new IllegalArgumentException("The relation " + name + " of the model of the table " + model.table()
          + " has " + (one ? "one row. Include it with includeOne()." : "many rows. Include it with include()."));
    }
    Mapping<?> target = database.mapping(relation.target());
    Statement rows = target.rewrite(target.expand(include.rows()), this);
    return one ? new Include.One(rows) : new Include.Many(rows);
  }

  private Relation<?> relation(String name) {
    return model.relation(name).orElseThrow(() -> new IllegalArgumentException("The model of the table "
        + model.table() + " has no relation " + name + "."));
  }

  private Filter rewrite(Filter filter, boolean bind, Mapping<?> outer) {
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
      case And and -> new And(and.operands().stream().map(operand -> rewrite(operand, bind, outer)).toList());
      case Or or -> new Or(or.operands().stream().map(operand -> rewrite(operand, bind, outer)).toList());
      case Not not -> new Not(rewrite(not.operand(), bind, outer));
      case Text text -> rewrite(text.translate(Set.of()), bind, outer);
      case Related related when outer != null -> new Related(binding(related.column()).column(),
          outer.binding(related.outerColumn()).column());
      default -> filter;
    };
  }

  private Expression expression(Expression expression) {
    return switch (expression) {
      case null -> null;
      case Column column -> new Column(binding(column.name()).column());
      case JsonKey key -> {
        Binding binding = binding(key.column());
        if (binding.stored() != JsonObject.class) {
          throw new IllegalArgumentException("The field " + key.column() + " of the model of the table "
              + model.table() + " is not stored as a JSON object, so it has no key " + key.key() + ".");
        }
        yield new JsonKey(binding.column(), key.key());
      }
      case Arithmetic arithmetic -> new Arithmetic(expression(arithmetic.left()), arithmetic.operator(),
          expression(arithmetic.right()));
      case Concat concat -> new Concat(concat.texts().stream().map(this::expression).toList());
      case Call call -> new Call(call.function(), call.arguments().stream().map(this::expression).toList());
      case Length length -> new Length(expression(length.text()));
      case Shift shift -> new Shift(expression(shift.timestamp()), shift.duration());
      case Case choice -> new Case(rewrite(choice.condition(), true, null), expression(choice.then()),
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
    return operand instanceof Column column ? binding(column.name()).bind(driver, value) : driver.bind(value);
  }

  /**
   * Gives the reader of the rows of a statement after its rewrite, which reads the fields that the statement selects
   * and the related rows of its includes.
   */
  Function<Row, E> reader(Statement statement) {
    List<Included> includes = includes(statement);
    return new Function<>() {

      private int[] positions;
      private int[] related;

      @Override
      public E apply(Row row) {
        if (positions == null) {
          positions = bindings.stream().mapToInt(binding -> row.getColumnIndex(binding.column())).toArray();
          related = includes.stream().mapToInt(included -> row.getColumnIndex(included.name())).toArray();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < positions.length; i++) {
          if (positions[i] >= 0) {
            values.put(bindings.get(i).name(), bindings.get(i).read(driver, row.getValue(positions[i]), false));
          }
        }
        for (int i = 0; i < related.length; i++) {
          values.put(includes.get(i).name(), includes.get(i).read(row.getValue(related[i])));
        }
        return model.assemble(values);
      }
    };
  }

  private List<Included> includes(Statement statement) {
    List<Included> includes = new ArrayList<>();
    statement.find(Select.class).ifPresent(select -> {
      for (Expression column : select.columns()) {
        if (column instanceof As(Include include, String name)) {
          Mapping<?> target = database.mapping(relation(name).target());
          includes.add(new Included(name, include instanceof Include.Many, target, target.includes(include
              .rows())));
        }
      }
    });
    return includes;
  }

  private E read(JsonObject object, List<Included> includes) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (Binding binding : bindings) {
      if (object.containsKey(binding.column())) {
        values.put(binding.name(), binding.read(driver, object.getValue(binding.column()), true));
      }
    }
    for (Included included : includes) {
      values.put(included.name(), included.read(object.getValue(included.name())));
    }
    return model.assemble(values);
  }

  /**
   * An include of a statement: the relation that it reads, and the includes of its rows.
   */
  private record Included(String name, boolean many, Mapping<?> target, List<Included> includes) {

    Object read(Object json) {
      Object parsed = switch (json) {
        case null -> null;
        case String text -> Json.decodeValue(text);
        case Buffer buffer -> Json.decodeValue(buffer);
        default -> json;
      };
      return switch (parsed) {
        case null -> many ? List.of() : null;
        case JsonArray array when many -> array.stream().map(object -> target.read((JsonObject) object, includes))
            .toList();
        case JsonArray array -> array.isEmpty() ? null : target.read(array.getJsonObject(0), includes);
        case JsonObject object when many -> List.of(target.read(object, includes));
        case JsonObject object -> target.read(object, includes);
        default -> throw new IllegalStateException("The include " + name + " gave " + parsed + ", not JSON.");
      };
    }
  }
}

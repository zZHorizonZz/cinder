package dev.horizon.cinder;

import dev.horizon.aquery.aip132.FieldPath;
import dev.horizon.aquery.aip132.InvalidOrderByException;
import dev.horizon.aquery.aip132.Order;
import dev.horizon.aquery.aip160.InvalidFilterException;
import dev.horizon.basepoint.CollectionReference;
import dev.horizon.basepoint.Direction;
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
import dev.horizon.basepoint.expression.Path;
import dev.horizon.basepoint.expression.Reference;
import dev.horizon.basepoint.expression.Shift;
import dev.horizon.basepoint.expression.Subquery;
import dev.horizon.basepoint.expression.Value;
import dev.horizon.basepoint.filter.And;
import dev.horizon.basepoint.filter.Between;
import dev.horizon.basepoint.filter.Compare;
import dev.horizon.basepoint.filter.Exists;
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
import dev.horizon.basepoint.statement.Join;
import dev.horizon.basepoint.statement.OnConflict;
import dev.horizon.basepoint.statement.Operand;
import dev.horizon.basepoint.statement.OrderBy;
import dev.horizon.basepoint.statement.Returning;
import dev.horizon.basepoint.statement.Select;
import dev.horizon.basepoint.statement.Sort;
import dev.horizon.basepoint.statement.Source;
import dev.horizon.basepoint.statement.Statement;
import dev.horizon.basepoint.statement.Values;
import dev.horizon.basepoint.statement.Where;
import dev.horizon.basepoint.statement.With;
import dev.horizon.basepoint.statement.WithQuery;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The columns of a model: the binding of each field, and the rewrite of a statement that names fields into one that
 * names columns and holds the values that the driver sends. A statement inside a statement, such as a subquery, an
 * {@code exists}, a relation, a WITH query or an include, is rewritten with the model of its own table.
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
  private final Map<String, Binding> computed = new LinkedHashMap<>();
  private final List<Binding> key;
  private final KeyFields keyFields;

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
    for (Field<E, ?> field : model.computed()) {
      computed.put(field.name(), Binding.of(field, codecs));
    }
    this.key = model.key().stream().map(field -> binding(field.name())).toList();
    this.keyFields = new KeyFields(key);
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

  KeyFields keyFields() {
    return keyFields;
  }

  List<Binding> key() {
    return key;
  }

  String[] fields() {
    return bindings.stream().map(Binding::name).toArray(String[]::new);
  }

  Binding binding(String name) {
    Binding binding = names.get(name);
    if (binding != null) {
      return binding;
    }
    if (computed.containsKey(name)) {
      throw new IllegalArgumentException("The field " + name + " of the model of the table " + model.table()
          + " is computed, so a statement names it only as the name of a value that it selects, such as select(\""
          + name + "\", value).");
    }
    throw new IllegalArgumentException("The model of the table " + model.table() + " has no field " + name + ".");
  }

  Binding binding(FieldPath path, String what) {
    if (path.segments().size() != 1) {
      throw new IllegalArgumentException(what + " of the table " + model.table() + " names fields of the table, not"
          + " the path " + path + ".");
    }
    return binding(path.segments().getFirst());
  }

  static boolean compound(Statement statement) {
    return statement.source() instanceof Source.None && statement.has(Operand.class);
  }

  static List<Statement> operands(Statement statement) {
    return statement.findAll(Operand.class).stream().map(Operand::statement).toList();
  }

  /**
   * Checks that a statement reads or writes the table of the model: each query of a union, or a WITH query of its
   * rows.
   */
  void requireTable(Statement statement) {
    if (compound(statement)) {
      operands(statement).forEach(this::requireTable);
      return;
    }
    String table = switch (statement.source()) {
      case Source.Table read -> read.name();
      case Source.Target target -> target.name();
      default -> null;
    };
    if (model.table().equals(table) || readsWith(statement, table)) {
      return;
    }
    throw new IllegalArgumentException("The statement " + (table == null ? "reads no table"
        : "is of the table "
            + table)
        + ", not of the table " + model.table() + " of the model.");
  }

  private boolean readsWith(Statement statement, String table) {
    return statement.find(With.class).stream().flatMap(with -> with.queries().stream())
        .anyMatch(query -> query.name().equals(table) && model.table().equals(tableOf(query.rows())));
  }

  private static String tableOf(Statement statement) {
    Statement rows = compound(statement) ? operands(statement).getFirst() : statement;
    return switch (rows.source()) {
      case Source.Table read -> read.name();
      case Source.Target target -> target.name();
      default -> null;
    };
  }

  /**
   * Gives the AIP-160 filter of a request, whose fields the model has.
   *
   * @throws InvalidFilterException if the text is not AIP-160, or names a field that the model does not have
   */
  Filter requestFilter(String text) {
    Filter filter = Filter.of(text);
    try {
      mapFilter(filter, false, Scope.TOP);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidFilterException("%s", unknown.getMessage());
    }
    return filter;
  }

  /**
   * Gives the AIP-132 order of a request, by fields of the model.
   *
   * @throws InvalidOrderByException if the text is not AIP-132, or names a field that the model does not have
   */
  List<Sort> requestOrder(String text) {
    if (text.isBlank()) {
      return List.of();
    }
    List<Sort> sorts = new ArrayList<>();
    for (var term : Order.parse(text)) {
      Binding binding;
      try {
        binding = binding(term.fieldPath(), "An order");
      } catch (IllegalArgumentException unknown) {
        throw new InvalidOrderByException("%s", unknown.getMessage());
      }
      sorts.add(new Sort(new Column(binding.name()), term.descending() ? Direction.DESC : Direction.ASC));
    }
    return sorts;
  }

  /**
   * Gives a query that reads every field when it names none, and the fields that it names otherwise. A query that
   * groups its rows reads the fields that it groups by.
   */
  Statement expand(Statement statement) {
    if (compound(statement)) {
      return new Statement(statement.source(),
          statement.clauses().stream()
              .map(clause -> clause instanceof Operand(Statement operand) ? (Clause) new Operand(expand(operand)) : clause)
              .toList());
    }
    Optional<Select> found = statement.find(Select.class);
    if (found.isEmpty()) {
      return statement;
    }
    Select select = found.get();
    boolean namesFields = select.columns().stream().anyMatch(column -> column instanceof Column
        || column instanceof AllColumns || column instanceof Path);
    List<Expression> columns = new ArrayList<>();
    if (!namesFields) {
      if (statement.has(GroupBy.class)) {
        for (Expression grouped : statement.find(GroupBy.class).orElseThrow().columns()) {
          if (grouped instanceof Column column && names.containsKey(column.name())) {
            columns.add(new Column(column.name()));
          }
        }
      } else {
        bindings.forEach(binding -> columns.add(new Column(binding.name())));
      }
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
    return rewrite(statement, null, Map.of());
  }

  /**
   * What a statement can name besides the fields of its model: the row of the query around it, the tables of its
   * relations, and its WITH queries.
   */
  private record Scope(Mapping<?> outer, Map<String, Mapping<?>> relations, Map<String, Mapping<?>> withs) {

    static final Scope TOP = new Scope(null, Map.of(), Map.of());
  }

  private Statement rewrite(Statement statement, Mapping<?> outer, Map<String, Mapping<?>> withs) {
    Map<String, Mapping<?>> withTables = new LinkedHashMap<>(withs);
    statement.find(With.class).ifPresent(with -> with.queries().forEach(query -> withTables.put(query.name(),
        mappingOf(query.rows(), withTables))));
    if (compound(statement)) {
      return new Statement(statement.source(), statement.clauses().stream().map(clause -> switch (clause) {
        case With with -> withQueries(with, withTables);
        case Operand(Statement operand) -> new Operand(mappingOf(operand, withTables).rewrite(operand, outer, withTables));
        case OrderBy orderBy -> new OrderBy(orderBy.sorts().stream().map(sort -> new Sort(
            sort.value() instanceof Reference(String name) && names.containsKey(name) ? new Reference(binding(name).column())
                : sort.value(),
            sort.direction())).toList());
        default -> clause;
      }).toList());
    }
    Map<String, Mapping<?>> relations = new LinkedHashMap<>();
    for (Join join : statement.findAll(Join.class)) {
      relations.put(join.name(), tableMapping(join.table(), withTables));
    }
    Scope scope = new Scope(outer, relations, withTables);
    List<Binding> insertColumns = statement.find(InsertInto.class).map(into -> into.columns().stream()
        .map(this::binding).toList()).orElse(List.of());
    Set<String> selectLabels = statement.find(Select.class).map(select -> select.columns().stream()
        .filter(As.class::isInstance).map(column -> ((As) column).name()).collect(Collectors.toSet()))
        .orElse(Set.of());
    List<Clause> clauses = new ArrayList<>();
    for (Clause clause : statement.clauses()) {
      clauses.add(switch (clause) {
        case With with -> withQueries(with, withTables);
        case Select select -> new Select(select.distinct(), select.columns().stream()
            .map(column -> mapSelectColumn(column, scope)).toList());
        case Join join -> new Join(join.name(), join.table(), relations.get(join.name()).mapFilter(join.condition(),
            true, new Scope(this, Map.of(), withTables)), join.inner());
        case Where where -> new Where(mapFilter(where.filter(), true, scope));
        case GroupBy groupBy -> new GroupBy(groupBy.columns().stream().map(value -> mapExpression(value, scope))
            .toList());
        case Having having -> new Having(mapFilter(having.filter(), true, scope));
        case OrderBy orderBy -> new OrderBy(orderBy.sorts().stream()
            .map(sort -> new Sort(mapSort(sort.value(), selectLabels, scope), sort.direction())).toList());
        case Assignments set -> new Assignments(set.assignments().stream().map(assignment -> {
          Binding binding = binding(assignment.column());
          return new Assignment(binding.column(), bindValue(binding, assignment.value(), scope));
        }).toList());
        case InsertInto into -> new InsertInto(insertColumns.stream().map(Binding::column).toList());
        case Values values -> new Values(values.rows().stream().map(row -> bindRow(insertColumns, row, scope))
            .toList());
        case OnConflict conflict -> new OnConflict(columns(conflict.key()), columns(conflict.updates()));
        case Returning returning -> new Returning(columns(returning.columns()));
        default -> clause;
      });
    }
    return new Statement(statement.source(), clauses);
  }

  private With withQueries(With with, Map<String, Mapping<?>> withTables) {
    return new With(with.queries().stream().map(query -> new WithQuery(query.name(), mappingOf(query.rows(), withTables)
        .rewrite(query.rows(), null, withTables), query.recursive())).toList());
  }

  private Mapping<?> mappingOf(Statement statement, Map<String, Mapping<?>> withTables) {
    String table = tableOf(statement);
    if (table == null) {
      throw new IllegalArgumentException("A statement inside the statement of the table " + model.table()
          + " reads no table.");
    }
    return tableMapping(table, withTables);
  }

  private Mapping<?> tableMapping(String table, Map<String, Mapping<?>> withTables) {
    if (withTables.containsKey(table)) {
      return withTables.get(table);
    }
    if (model.table().equals(table)) {
      return this;
    }
    return database.mappingOf(table);
  }

  private List<Expression> bindRow(List<Binding> columns, List<Expression> row, Scope scope) {
    List<Expression> values = new ArrayList<>(row.size());
    for (int i = 0; i < row.size(); i++) {
      values.add(bindValue(columns.get(i), row.get(i), scope));
    }
    return values;
  }

  private List<String> columns(List<String> fields) {
    return fields.stream().map(field -> binding(field).column()).toList();
  }

  private Expression bindValue(Binding binding, Expression value, Scope scope) {
    return switch (value) {
      case Value plain -> Expression.value(binding.bind(driver, plain.value()));
      case Null ignored -> Expression.value(binding.bind(driver, null));
      default -> mapExpression(value, scope);
    };
  }

  private Expression mapSort(Expression value, Set<String> selectLabels, Scope scope) {
    if (value instanceof Reference reference) {
      return selectLabels.contains(reference.name()) ? reference : new Column(binding(reference.name()).column());
    }
    return mapExpression(value, scope);
  }

  private Expression mapSelectColumn(Expression column, Scope scope) {
    if (column instanceof As(Include include, String name)) {
      return new As(include(include, name, scope), name);
    }
    return mapExpression(column, scope);
  }

  private Include include(Include include, String name, Scope scope) {
    Relation<?> relation = model.relation(name).orElseThrow(() -> new IllegalArgumentException("The model of the"
        + " table " + model.table() + " has no relation " + name + ", so the rows of the include have no field to"
        + " go to."));
    boolean one = relation.shape() == Relation.Shape.ONE;
    if (one != include instanceof Include.One) {
      throw new IllegalArgumentException("The relation " + name + " of the model of the table " + model.table()
          + " has " + (one ? "one row. Include it with includeOne()." : "many rows. Include it with include()."));
    }
    Mapping<?> rows = mappingOf(include.rows(), scope.withs());
    Mapping<?> target = database.mapping(relation.target());
    Statement statement = rows.rewrite(rows == target ? target.expand(include.rows()) : include.rows(), this,
        scope.withs());
    return one ? new Include.One(statement) : new Include.Many(statement);
  }

  private Relation<?> relation(String name) {
    return model.relation(name).orElseThrow(() -> new IllegalArgumentException("The model of the table "
        + model.table() + " has no relation " + name + "."));
  }

  private Filter mapFilter(Filter filter, boolean bind, Scope scope) {
    return switch (filter) {
      case Compare compare -> new Compare(mapExpression(compare.left(), scope), compare.operator(),
          mapValue(compare.left(), compare.right(), bind, scope));
      case IsNull isNull -> new IsNull(mapExpression(isNull.operand(), scope), isNull.negated());
      case In in -> new In(mapExpression(in.operand(), scope), bind ? in.values().stream()
          .map(value -> bindOperand(in.operand(), value, scope)).toList() : in.values(), in.negated());
      case Between between -> new Between(mapExpression(between.operand(), scope),
          mapValue(between.operand(), between.low(), bind, scope),
          mapValue(between.operand(), between.high(), bind, scope));
      case Match match -> new Match(pattern(match.operand(), scope), match.parts(), match.negated());
      case Like like -> new Like(pattern(like.operand(), scope), like.pattern());
      case And and -> new And(and.operands().stream().map(operand -> mapFilter(operand, bind, scope)).toList());
      case Or or -> new Or(or.operands().stream().map(operand -> mapFilter(operand, bind, scope)).toList());
      case Not not -> new Not(mapFilter(not.operand(), bind, scope));
      case Text text -> mapFilter(text.translate(Set.of()), bind, scope);
      case Related related when scope.outer() != null -> new Related(binding(related.column()).column(),
          scope.outer().binding(related.outerColumn()).column());
      case Exists exists -> new Exists(mappingOf(exists.rows(), scope.withs()).rewrite(exists.rows(), this,
          scope.withs()));
      default -> filter;
    };
  }

  private Expression pattern(Expression operand, Scope scope) {
    Binding binding = switch (operand) {
      case Column column -> binding(column.name());
      case Path path -> relationMapping(path.relation(), scope).binding(path.name());
      default -> null;
    };
    if (binding != null && binding.type() == String.class && binding.stored() == String.class
        && !binding.codecs().isEmpty()) {
      throw new IllegalArgumentException("The field " + binding.name() + " has codecs that change its text, so a"
          + " pattern does not match what its column holds.");
    }
    return mapExpression(operand, scope);
  }

  private Expression mapExpression(Expression expression, Scope scope) {
    return switch (expression) {
      case null -> null;
      case Column column -> new Column(binding(column.name()).column());
      case Path path -> new Path(path.relation(), relationMapping(path.relation(), scope).binding(path.name())
          .column());
      case JsonKey key -> {
        Binding binding = binding(key.column());
        if (binding.stored() != JsonObject.class) {
          throw new IllegalArgumentException("The field " + key.column() + " of the model of the table "
              + model.table() + " is not stored as a JSON object, so it has no key " + key.key() + ".");
        }
        yield new JsonKey(binding.column(), key.key());
      }
      case Arithmetic arithmetic -> new Arithmetic(mapExpression(arithmetic.left(), scope), arithmetic.operator(),
          mapExpression(arithmetic.right(), scope));
      case Concat concat -> new Concat(concat.texts().stream().map(text -> mapExpression(text, scope)).toList());
      case Call call -> new Call(call.function(), call.arguments().stream().map(argument -> mapExpression(argument,
          scope)).toList());
      case Length length -> new Length(mapExpression(length.text(), scope));
      case Shift shift -> new Shift(mapExpression(shift.timestamp(), scope), shift.duration());
      case Case choice -> new Case(mapFilter(choice.condition(), true, scope), mapExpression(choice.then(), scope),
          mapExpression(choice.otherwise(), scope));
      case Aggregate aggregate -> new Aggregate(aggregate.function(), mapExpression(aggregate.argument(), scope));
      case As as -> new As(mapExpression(as.value(), scope), as.name());
      case Subquery subquery -> new Subquery(mappingOf(subquery.statement(), scope.withs()).rewrite(subquery
          .statement(), this, scope.withs()));
      default -> expression;
    };
  }

  private Mapping<?> relationMapping(String relation, Scope scope) {
    Mapping<?> found = scope.relations().get(relation);
    if (found == null) {
      throw new IllegalArgumentException("The query has no relation withTables " + relation + ". Name it with relation(\""
          + relation + "\", rows) on the query that reads the path.");
    }
    return found;
  }

  private Expression mapValue(Expression operand, Expression value, boolean bind, Scope scope) {
    return switch (value) {
      case Value plain when bind -> new Value(bindOperand(operand, plain.value(), scope));
      case Value plain -> plain;
      default -> mapExpression(value, scope);
    };
  }

  private Object bindOperand(Expression operand, Object value, Scope scope) {
    return switch (operand) {
      case Column column -> binding(column.name()).bind(driver, value);
      case Path path -> relationMapping(path.relation(), scope).binding(path.name()).bind(driver, value);
      default -> driver.bind(value);
    };
  }

  /**
   * Gives the reader of the rows of a statement after its rewrite, which reads the fields that the statement selects,
   * the values that it selects under the names of computed fields, and the related rows of its includes.
   */
  Function<Row, E> reader(Statement statement) {
    Statement rows = compound(statement) ? operands(statement).getFirst() : statement;
    List<Included> includes = includes(rows);
    List<Binding> values = computedOf(rows, false);
    return new Function<>() {

      private int[] positions;
      private int[] related;
      private int[] computedPositions;

      @Override
      public E apply(Row row) {
        if (positions == null) {
          positions = bindings.stream().mapToInt(binding -> row.getColumnIndex(binding.column())).toArray();
          related = includes.stream().mapToInt(included -> row.getColumnIndex(included.name())).toArray();
          computedPositions = values.stream().mapToInt(binding -> row.getColumnIndex(binding.name())).toArray();
        }
        Map<String, Object> read = new LinkedHashMap<>();
        for (int i = 0; i < positions.length; i++) {
          if (positions[i] >= 0) {
            read.put(bindings.get(i).name(), bindings.get(i).read(driver, row.getValue(positions[i]), false));
          }
        }
        for (int i = 0; i < computedPositions.length; i++) {
          read.put(values.get(i).name(), values.get(i).read(driver, row.getValue(computedPositions[i]), false));
        }
        for (int i = 0; i < related.length; i++) {
          read.put(includes.get(i).name(), includes.get(i).read(row.getValue(related[i])));
        }
        return model.assemble(read);
      }
    };
  }

  /**
   * Gives the computed fields that a select fills, and refuses a value under any other name, which the entity would
   * lose. The query of a relation through a link table names the columns of its target with paths.
   */
  private List<Binding> computedOf(Statement statement, boolean throughLink) {
    List<Binding> found = new ArrayList<>();
    statement.find(Select.class).ifPresent(select -> {
      for (Expression column : select.columns()) {
        if (!(column instanceof As(Expression value, String name)) || value instanceof Include) {
          continue;
        }
        if (throughLink && value instanceof Path && names.containsKey(name)) {
          continue;
        }
        Binding binding = computed.get(name);
        if (binding == null) {
          throw new IllegalArgumentException("The query selects a value under the name " + name + ", which is "
              + (names.containsKey(name) ? "a stored field" : "no field") + " of the model of the table "
              + model.table() + ". Give the value the name of a computed field, or read it with rows().");
        }
        found.add(binding);
      }
    });
    return found;
  }

  private List<Included> includes(Statement statement) {
    List<Included> includes = new ArrayList<>();
    statement.find(Select.class).ifPresent(select -> {
      for (Expression column : select.columns()) {
        if (column instanceof As(Include include, String name)) {
          Relation<?> relation = relation(name);
          Mapping<?> target = database.mapping(relation.target());
          includes.add(new Included(name, include instanceof Include.Many, target, target.includes(include.rows()),
              target.computedOf(include.rows(), relation.through() != null)));
        }
      }
    });
    return includes;
  }

  private E read(JsonObject object, List<Included> includes, List<Binding> values) {
    Map<String, Object> read = new LinkedHashMap<>();
    for (Binding binding : bindings) {
      if (object.containsKey(binding.column())) {
        read.put(binding.name(), binding.read(driver, object.getValue(binding.column()), true));
      }
    }
    for (Binding binding : values) {
      if (object.containsKey(binding.name())) {
        read.put(binding.name(), binding.read(driver, object.getValue(binding.name()), true));
      }
    }
    for (Included included : includes) {
      read.put(included.name(), included.read(object.getValue(included.name())));
    }
    return model.assemble(read);
  }

  /**
   * An include of a statement: the relation that it reads, the includes of its rows, and the computed fields that
   * its rows select.
   */
  private record Included(String name, boolean many, Mapping<?> target, List<Included> includes,
      List<Binding> computed) {

    Object read(Object json) {
      Object parsed = switch (json) {
        case null -> null;
        case String text -> Json.decodeValue(text);
        case Buffer buffer -> Json.decodeValue(buffer);
        default -> json;
      };
      return switch (parsed) {
        case null -> many ? List.of() : null;
        case JsonArray array when many -> array.stream().map(object -> target.read((JsonObject) object, includes,
            computed)).toList();
        case JsonArray array -> array.isEmpty() ? null : target.read(array.getJsonObject(0), includes, computed);
        case JsonObject object when many -> List.of(target.read(object, includes, computed));
        case JsonObject object -> target.read(object, includes, computed);
        default -> throw new IllegalStateException("The include " + name + " gave " + parsed + ", not JSON.");
      };
    }
  }
}

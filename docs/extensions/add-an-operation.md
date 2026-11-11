# Add an operation

An operation is a method that builds a Basepoint statement and runs it on a table. A backend has the operations of
its own domain as methods, and an extension gives an operation to every backend that wants it, as an interface that
extends `Backend`. This guide adds a `descendants` operation, which reads a subtree of a tree of categories.

## Before you begin

The table of the tree names the parent of each row:

```proto
message Category {
  option (leadseal.v1.table) = {name: "categories"};

  string id = 1 [(leadseal.v1.column).primary_key = true];
  optional string parent_id = 2 [(leadseal.v1.column).foreign_key = {message: "shop.v1.Category"}];
  string name = 3;
}
```

## Add the operation

1. Write the filter. A Basepoint filter can be any node, so the recursive query of a subtree is a filter of its own.
   Cinder does not know this node, so it renders the columns that it gets as they are:

   ```java
   public record InTree(String table, String key, String parent, Object root, int depth) implements Filter {

     @Override
     public void render(StatementWriter sql) {
       sql.column(key).append(" IN (WITH RECURSIVE tree (id, depth) AS (SELECT ").name(key).append(", 0 FROM ")
           .table(table).append(" WHERE ").name(key).append(" = ").param(root)
           .append(" UNION ALL SELECT c.").name(key).append(", t.depth + 1 FROM ").table(table)
           .append(" c JOIN tree t ON c.").name(parent).append(" = t.id WHERE t.depth < ").param(depth)
           .append(") SELECT id FROM tree)");
     }
   }
   ```

2. Give the operation to backends, with an interface that extends `Backend`. It reads the columns of the key and the
   parent from the model, so the caller names fields:

   ```java
   public interface GraphBackend<E, K> extends Backend<E, K> {

     default Future<List<E>> descendants(K root, String parent, int depth) {
       Model<E> model = model();
       return list(query().where(new InTree(model.table(), model.key().getFirst().column(),
           model.field(parent).orElseThrow().column(), root, depth)));
     }
   }
   ```

3. Implement the interface in a backend, and call the operation:

   ```java
   public record CategoriesBackend(Database database) implements GraphBackend<Category, String> {

     @Override
     public Category prototype() {
       return Category.getDefaultInstance();
     }
   }

   Future<List<Category>> subtree = categories.descendants("clothing", "parent_id", 5);
   ```

## Verify

`descendants` gives the root and every category below it, at most five levels down. A backend that does not implement
`GraphBackend` does not have the method.

SQL Server writes `WITH` without `RECURSIVE`, and has no `WITH` in a subquery. To support it, give a decorator in
`decorator()` of an extension that writes the query in the syntax of SQL Server.

## Lock optimistically

An operation can run a part of its own on a table with `table().with(part)`. An optimistic lock is a part of an
update that adds the expected version to the filter, and the next version to the fields that it sets:

```java
record Expected(long version) implements Part {

  @Override
  public Statement shape(Statement statement, Context<?> context) {
    return statement.where(eq("version", version));
  }

  @Override
  public Map<String, Object> write(Map<String, Object> row, Context<?> context) {
    Map<String, Object> next = new LinkedHashMap<>(row);
    next.put("version", version + 1);
    return next;
  }
}

Future<Boolean> saved = orders.table().with(new Expected(order.getVersion()))
    .execute(orders.update().set("state", "PAID").where(eq("id", order.getId())))
    .map(updated -> updated > 0);
```

`saved` is false when another write changed the row after it was read.

## Return a result of another shape

An operation that needs the rows as the database gives them, such as an aggregate or an export, runs its query with
`rows` of the table, or reads it through `stream`:

```java
Future<RowSet<Row>> totals = orders.table().rows(orders.query()
    .select("customer_id", column("customer_id"))
    .select("total", sum("total_cents"))
    .groupBy("customer_id"));
```

## Related

- [Extensions](README.md)
- [Write an extension](write-an-extension.md)
- [Models](../models/README.md)

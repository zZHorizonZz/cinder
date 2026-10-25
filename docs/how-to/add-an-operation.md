# Add an operation

The methods of a query, an insert, an update and a delete cover what every database does. An extension adds
operations of its own through three open points, and needs no registration:

| Point                               | Does                                                                           | For example                          |
| ----------------------------------- | ------------------------------------------------------------------------------ | ------------------------------------ |
| `Part`                              | Changes the rows, the statement and the entities of one operation              | Graph filters, optimistic locks      |
| `Terminal`                          | Runs the statement for a result of its own shape, such as a future or a stream | Aggregates, exports, search rankings |
| An interface that extends `Backend` | Adds methods to the backends that implement it                                 | `descendants`, `search`              |

This guide adds a `descendants` operation, which reads a subtree of a tree of categories.

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

1. Write the filter. A Basepoint filter can be any node, so the recursive query of a subtree is a filter of its own:

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

2. Write a part that adds the filter to a query. It reads the columns of the key and the parent from the model, so
   the caller names fields:

   ```java
   public record Descendants(String parent, Object root, int depth) implements Part {

     @Override
     public Statement shape(Statement statement, Context<?> context) {
       Model<?> model = context.model();
       return statement.where(new InTree(model.table(), model.key().getFirst().column(),
           model.field(parent).orElseThrow().column(), root, depth));
     }
   }
   ```

3. Give the operation to backends, with an interface that extends `Backend`:

   ```java
   public interface GraphBackend<E, K> extends Backend<E, K> {

     default Future<List<E>> descendants(K root, String parent, int depth) {
       return findAll().with(new Descendants(parent, root, depth)).list();
     }
   }
   ```

4. Implement the interface in a backend, and call the operation:

   ```java
   public record CategoriesBackend(Database database) implements GraphBackend<Category, String> {

     @Override
     public Category prototype() {
       return Category.getDefaultInstance();
     }
   }

   Future<List<Category>> subtree = categories.descendants("clothing", "parent_id", 5);
   ```

SQL Server writes `WITH` without `RECURSIVE`, and has no `WITH` in a subquery. To support it, give a decorator in
`decorator()` of the extension that writes the query in the syntax of SQL Server.

## Lock optimistically

A write takes parts too. An optimistic lock is a part of an update that adds `version = <expected>` to the filter in
`shape`, and `row.increment("version", 1)` to the changes in `write`.

## Return a result of another shape

A terminal runs the statement of an operation for a result of its own shape. An operation runs it with
`run(terminal)`, as `Aggregate` runs:

```java
Future<List<Aggregate.Group>> totals = orders.findAll().run(Aggregate.by("customer_id").sum("total_cents"));
```

A terminal gets the execution of the operation. It runs the statement for a future, or reads it through the cursor
of `execution.stream(statement, fetch)` for a stream.

## Related resources

- [Extensions](../concepts/extensions.md)
- [Write an extension](write-an-extension.md)

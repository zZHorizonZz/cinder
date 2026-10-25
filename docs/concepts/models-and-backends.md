# Models and backends

A database reads and writes the rows of a table through the model of its type. A backend gives that type the
operations of its domain, on top of the common ones.

## Models

A `Model` maps a type of entity to a table: the table name, the fields and their columns, the key, the relations,
and how to build an entity from the values of a row. Filters, orders and masks name the fields of the model, and the
model maps each one to its column. A filter can also name a column, such as `created_at` for the field `create_time`.

A `ModelSource` gives the model of a type. A source has one method, `<E> Optional<Model<E>> model(E prototype)`: it
gets an entity of the type, such as `Order.getDefaultInstance()`, and reads what it needs from the entity, never from
the class, so it reads the types of a module that does not open its packages. The database asks the sources of its
extensions in order, and uses the first model it gets. The protobuf extension is the model source of messages with the
`leadseal.v1.table` option. A module for Java records or for another schema language is a model source in the same
way.

A model has no state of its own. The database resolves each model once, with the codecs of its fields and the parts
of its operations.

## Drivers

A `Driver` knows what the Basepoint dialect does not: how the Vert.x client of a database binds and reads values, and
how the database begins, ends and nests transactions. Each driver module has one driver, such as `PostgresDriver`.
For what each driver does, refer to [Drivers](../reference/drivers.md).

## Backends

A `Backend<E, K>` gives an entity of its type and its database:

```java
public record OrdersBackend(Database database) implements Backend<Order, Long> {

  @Override
  public Order prototype() {
    return Order.getDefaultInstance();
  }
}
```

Every other operation of `Backend` has a default, which runs on the database: `findById`, `find`, `list`, `insert`,
`upsert`, `update`, `deleteById` and the rest. A backend adds the operations of its own domain as methods that call
them:

```java
public Future<Boolean> cancel(long id, Instant now) {
  return update().where(and(eq("id", id), eq("state", "OPEN")))
      .set(Assignments.none().set("state", "CANCELLED").set("cancel_time", now).increment("version", 1))
      .execute().map(updated -> updated > 0);
}
```

The key `K` is the value of the key field. The operations by key, such as `findById` and `deleteById`, take a table
whose key has one column. A backend of a table whose key has more columns selects its rows with a filter on each of
them. For a table keyed by `group_id` and `user_id`:

```java
find(and(eq("group_id", group), eq("user_id", user))).first()
```

## Operations

A query, an insert, an update and a delete are each an operation of their own. An operation is immutable: each method
returns a new operation, and nothing runs until a terminal method, such as `list`, `execute` or `returning`. An update
or a delete needs a filter or `all()`, so that none changes every row by mistake.

Outside a transaction, each statement commits as it runs. In the work of `database.transaction`, every operation on
any backend of the database runs in that transaction. For more information, refer to
[Run in a transaction](../how-to/run-in-a-transaction.md).

## An admin API

A backend runs what its caller asks. It does not check who the caller is, and a filter or an order can name any field
of the model. To serve a client, put a layer above the backends that checks the caller and limits the fields that
the client can filter and sort by. A page has at most `Database.MAX_PAGE_SIZE` rows, or the number that
`Database.Builder.maxPageSize` sets.

## Related resources

- [Extensions](extensions.md)
- [Map protobuf messages](../how-to/map-protobuf-messages.md)

# Models

A model maps a type of entity to a table. A backend gives the type the operations of its domain, and a table runs
the Basepoint statements of the model.

## Models and model sources

A `Model` has the table name, the fields and their columns, the key, the relations, and how to build an entity from
the values of a row. Statements name the fields of the model, and Cinder maps each one to its column. A statement can
also name a column, such as `created_at` for the field `create_time`.

A `ModelSource` gives the model of a type, from an entity of the type, such as `Order.getDefaultInstance()`. It reads
what it needs from the entity, never from the class, so it reads the types of a module that does not open its
packages. The database asks the sources of its extensions in order, and uses the first model it gets. The protobuf
extension is the model source of messages with the `leadseal.v1.table` option. See
[Map protobuf messages](map-protobuf-messages.md).

## Backends

A `Backend<E, K>` gives an entity of its type and its database:

```java
public record OrdersBackend(Database database) implements Backend<Order, Long> {

  @Override
  public Order prototype() {
    return Order.getDefaultInstance();
  }

  public Future<Boolean> cancel(long id, Instant now) {
    return execute(update()
            .set("state", "CANCELLED")
            .set("cancel_time", now)
            .increment("version", 1)
            .where(and(eq("id", id), eq("state", "OPEN"))))
        .map(updated -> updated > 0);
  }
}
```

Every other method of `Backend` has a default:

- **By key:** `findById`, `findAllById`, `existsById`, `updateById` and `deleteById`.
- **As a resource:** `list(PageRequest)` for an AIP List method, and `update(entity, updateMask)` for an AIP Update
  method.
- **Writes of entities:** `insert`, `insertAll` and `upsert`.
- **Statements:** `query()`, `update()` and `delete()` start Basepoint's builders, and `list`, `first`, `single`,
  `count`, `exists`, `page`, `stream`, `execute` and `returning` run them.

The key `K` is the value of the key field. The methods by key take a table whose key has one column. A backend of a
table whose key has more columns selects its rows with a filter on each of them, such as
`list(query().where(and(eq("group_id", group), eq("user_id", user))))`.

An extension adds operations to backends as an interface that extends `Backend`, with default methods. See
[Add an operation](../extensions/add-an-operation.md).

## Tables

`backend.table()`, or `database.table(prototype)`, gives the `Table<E>` of a type. A table runs Basepoint statements
for the model: it checks that a statement is of its table, runs the parts of the model, maps the fields to their
columns, sends each value through the codecs of its field, and reads the rows into entities. A backend runs its
statements on its table.

## An admin API

A backend runs what its caller asks. It does not check who the caller is, and a filter or an order can name any field
of the model. To serve a client, put a layer above the backends that checks the caller and limits the fields that
the client can filter and sort by. A page has at most `Database.MAX_PAGE_SIZE` rows, or the number that
`Database.Builder.maxPageSize` sets.

The errors of models are in [Troubleshooting models](troubleshooting-models.md).

## Related

- [Map protobuf messages](map-protobuf-messages.md)
- [Protobuf types](protobuf-types.md)
- [Reads](../reads/README.md)

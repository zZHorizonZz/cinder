# Troubleshooting models

The errors of models and their sources, the most frequent first.

## No model source of the database knows the type ...

```text
java.lang.IllegalArgumentException: No model source of the database knows the type shop.v1.Order. Add the extension of its models, such as the protobuf extension for messages.
```

**Cause:** `database.table(prototype)`, `database.model(prototype)` or a backend of a type that no extension of the
database maps. A protobuf message needs the protobuf extension, and the `leadseal.v1.table` option.

**Fix:** Add the extension to the builder, `Database.builder(pool, driver).extension(new ProtobufExtension())`, and
give the message the `leadseal.v1.table` option.

**Verify:** `database.model(Order.getDefaultInstance()).table()` gives the name of the table.

## The table message ... has no primary key

```text
java.lang.IllegalArgumentException: The table message shop.v1.Order has no primary key. Set (leadseal.v1.column).primary_key on its key fields.
```

**Cause:** A message with the `leadseal.v1.table` option and no field with the `primary_key` option. Cinder finds
rows by their key, so every table needs one.

**Fix:** Set `(leadseal.v1.column).primary_key = true` on the key field, or on each field of a key of more fields.

**Verify:** `database.model(Order.getDefaultInstance()).key()` lists the key fields.

## The relation ... needs one foreign key between ...

```text
java.lang.IllegalArgumentException: The relation shop.v1.Order.items needs one foreign key between shop.v1.Order and shop.v1.OrderItem, but there are 0.
```

**Cause:** A field whose type is another table message, with the `ignore` option, and no foreign key between the two
messages, or more than one. The foreign key tells which fields join the rows.

**Fix:** Give the field of the other table the `foreign_key` option, such as
`int64 order_id = 2 [(leadseal.v1.column).foreign_key = {message: "shop.v1.Order"}]`. With more than one foreign key
between the two messages, keep one, or map the relation by hand with a model source of your own.

**Verify:** `database.model(Order.getDefaultInstance()).relation("items")` is present.

## The key of the table ... has 2 fields

```text
java.lang.IllegalArgumentException: The key of the table memberships has 2 fields, so a filter on each of them selects its rows, not a single key.
```

**Cause:** A method by key, such as `findById` or `deleteById`, of a table whose key has more than one field.

**Fix:** Select the row with a filter on each key field, such as
`first(query().where(and(eq("group_id", group), eq("user_id", user))))`.

**Verify:** The query gives the row of the two keys.

## The enum ... has no value ...

```text
java.lang.IllegalArgumentException: The enum shop.v1.State has no value LOST.
```

**Cause:** A filter or a write with an enum value by a name that the enum does not have, such as a typo, or a value
that a newer version of the proto has.

**Fix:** Use a value of the enum, such as `State.OPEN`, rather than its name as text. A filter of a client that names
a value of no enum is an error of the client, and a service gives it back as such.

**Verify:** The statement runs, and finds the rows of the value.

## Related

- [Models](README.md)
- [Map protobuf messages](map-protobuf-messages.md)
- [Troubleshooting reads](../reads/troubleshooting-reads.md)

# Write rows

Insert entities, merge them into the rows of their keys, update and delete rows, and read back the rows you wrote.

## Before you begin

The examples use an `OrdersBackend` of `Order` messages, as [Get started](../get-started.md) writes it, with the
filters of `dev.horizon.basepoint.filter.Filter` imported statically.

## Write by key

1. Insert entities. Each gives the entity as stored, with the values that the database gave its columns:

   ```java
   Future<Order> created = orders.insert(order);
   Future<List<Order>> all = orders.insertAll(List.of(first, second));
   Future<Order> saved = orders.upsert(order);
   ```

   `upsert` updates every field but the key of the row of its key, if a row has it.

2. Update and delete the row of a key:

   ```java
   Future<Boolean> paid = orders.updateById(42L, update -> update.set("state", "PAID").increment("version", 1));
   Future<Boolean> gone = orders.deleteById(42L);
   ```

   Each gives whether a row had the key.

3. Update the fields of the update mask of an AIP Update request:

   ```java
   Future<Optional<Order>> patched = orders.update(request.getOrder(),
       String.join(",", request.getUpdateMask().getPathsList()));
   ```

   It gives the row as stored after the update, or nothing if no row has the key of the entity. An update mask
   cannot name a key field.

## Verify

`findById` gives each row as the writes left it. An insert of a row whose key or unique fields another row has fails
with a `DuplicateKeyException`.

## Insert with conflicts

Give `insert` of a table a `Conflict`:

```java
Future<List<Order>> added = orders.table().insert(List.of(first, second), Conflict.ignore());
Future<List<Customer>> merged = customers.table().insert(List.of(customer), Conflict.merge("name").on("email"));
```

`Conflict.ignore()` skips each entity whose key, or whose fields of `on`, a row already has. `Conflict.merge()`
updates that row instead, with every field, or with the fields that `merge` names. The future gives the rows that the
insert wrote. MariaDB skips a row by setting its key to itself, so there the future also gives the rows that
`Conflict.ignore()` kept, unchanged.

To insert without reading the rows back, use `execute`, which gives the number of rows that the database changed:

```java
Future<Long> count = orders.table().execute(List.of(first, second), Conflict.ignore());
```

## Update and delete rows of a filter

Build an update or a delete with Basepoint, in which you name fields, and run it:

```java
Future<Long> shipped = orders.execute(orders.update()
    .set("state", "SHIPPED")
    .increment("version", 1)
    .where(eq("state", "PAID")));
Future<List<Order>> archived = orders.returning(orders.update()
    .set("state", "ARCHIVED")
    .unset("cancel_time")
    .where("state = \"DELIVERED\""));
Future<Long> removed = orders.execute(orders.delete().where(lt("create_time", cutoff)));
Future<Long> cleared = orders.execute(orders.delete().all());
```

`execute` gives the number of rows. `returning` gives the rows as stored after the write. `set` sets a value,
`increment` adds to the value of the row, and `unset` sets a field to null, which a column that cannot be null
refuses.

On MariaDB, which has no `RETURNING` for an update before version 13, `returning` of an update locks the rows of its
filter in a transaction, updates them by their keys, and reads them again. Cinder does this on every version of
MariaDB.

## Related

- [Run in a transaction](../transactions/run-in-a-transaction.md)
- [Troubleshooting writes](troubleshooting-writes.md)
- [Basepoint updates](https://github.com/zZHorizonZz/basepoint/blob/main/docs/writes/update-rows.md)

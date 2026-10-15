# Write rows

A backend has a method for each common write. For anything more, an insert, an update or a delete is an operation of
its own, like a query. The examples use an `OrdersBackend` of `Order` messages, with the filters of
`dev.horizon.basepoint.Filter` imported statically.

## Write with the common methods

```java
Future<Order> created = orders.insert(order);
Future<List<Order>> all = orders.insertAll(List.of(first, second));
Future<Order> saved = orders.upsert(order);
Future<Optional<Order>> patched = orders.update(request.getOrder(),
    String.join(",", request.getUpdateMask().getPathsList()));
Future<Boolean> renamed = orders.updateById(42L, Assignments.none().set("customer_id", "beth"));
Future<Boolean> gone = orders.deleteById(42L);
```

An insert of a row whose key another row has fails with a `DuplicateKeyException`. `update` takes the entity and the
update mask of an AIP-134 update request, and gives the row as stored, or empty if no row has its key. An update mask
cannot name a key field.

## Insert rows

An insert sets what to do with conflicts, and reads back the rows it wrote with `returning()`:

```java
Future<Long> added = orders.insert().values(List.of(first, second)).onConflict(Conflict.ignore()).execute();
Future<List<Customer>> merged = customers.insert().values(customer).onConflict(Conflict.merge().on("email"))
    .returning();
```

`Conflict.ignore()` skips each row whose key, or whose fields of `on`, another row has. `Conflict.merge()` updates
that row instead, with every field, or with the fields that `merge` names.

## Update rows

An update changes the rows of a filter, with the changes of `Assignments`:

```java
Future<List<Order>> archived = orders.update().where(eq("state", "DELIVERED"))
    .set(Assignments.none().set("state", "ARCHIVED")).returning();
Future<Long> paid = orders.update().where(eq("id", 42L))
    .set(Assignments.none().set("state", "PAID").increment("version", 1).unset("cancel_time"))
    .execute();
```

`set` sets a value, `increment` adds to the value that the row has, and `unset` sets a field to null, or to the
default of its column if it cannot be null.

## Delete rows

```java
Future<Long> removed = orders.delete().where(lt("create_time", cutoff)).execute();
Future<Long> cleared = orders.delete().all().execute();
```

An update or a delete needs a filter or `all()`, so that none changes every row by mistake.

## Read back what changed

`returning()` gives the rows that a write changed, as stored. The database reads them back with `RETURNING`. MariaDB
has no `RETURNING` for an update, so on MariaDB an update that returns its rows locks the rows of its filter in a
transaction, updates them by their keys, and reads them again.

## Related resources

- [Run in a transaction](run-in-a-transaction.md)
- [Read rows](read-rows.md)

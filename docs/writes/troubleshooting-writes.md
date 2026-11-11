# Troubleshooting writes

The errors of inserts, updates and deletes, the most frequent first.

## DuplicateKeyException

```text
dev.horizon.cinder.DuplicateKeyException: ERROR: duplicate key value violates unique constraint "orders_pkey" (23505)
```

**Cause:** An insert of a row whose key or unique fields another row has. The message is that of the database, so it
differs on each database. `exception.model()` gives the model of the table.

**Fix:** To skip such rows or merge them, insert with a `Conflict`, such as `Conflict.ignore()` or
`Conflict.merge().on("email")`. To fail, give the error back to the caller, such as an AIP `ALREADY_EXISTS`.

**Verify:** The insert gives the rows that it wrote.

## The update has no filter. Add a filter with where(), or call all() to...

```text
dev.horizon.basepoint.UnfilteredWriteException: The update has no filter. Add a filter with where(), or call all() to update every row.
```

**Cause:** An update or a delete without `where` and without `all()`. Basepoint refuses it, so that a forgotten
filter does not change every row of the table. The delete has the same error: `The delete has no filter.`

**Fix:** Add the filter of the rows to change with `where`. If you mean every row, call `all()`.

**Verify:** `execute` gives the number of rows of the filter.

## The update sets no columns. Call set(), increment() or unset().

```text
java.lang.IllegalStateException: The update sets no columns. Call set(), increment() or unset().
```

**Cause:** An update without `set`, `increment` or `unset`, such as `updateById(42L, update -> update)`.

**Fix:** Set at least one field, or do not run the update when there is nothing to set.

**Verify:** `execute` gives the number of updated rows.

## The model of the table ... has no field ...

```text
java.lang.IllegalArgumentException: The model of the table orders has no field colour.
```

**Cause:** An update that sets a field that the model does not have, a filter of one, or a `Conflict` whose `on` or
`merge` names one.

**Fix:** Name a field of the model, or its column.

**Verify:** The write runs, and changes the rows of its filter.

## An update cannot change the key field ...

```text
java.lang.IllegalArgumentException: An update cannot change the key field id.
```

**Cause:** `update(entity, updateMask)` with a mask that names a key field. The key selects the row, so the update
cannot change it.

**Fix:** Leave the key fields out of the mask. To change a key, insert a new row and delete the old one, in one
transaction.

**Verify:** `update` gives the row as stored after the update.

## A value that the column refuses

```text
io.vertx.pgclient.PgException: ERROR: null value in column "state" of relation "orders" violates not-null constraint (23502)
```

**Cause:** A write of a value that the column refuses, such as `unset` of a field whose column cannot be null, or a
text longer than its column. The message is that of the database.

**Fix:** Set a value that the column holds. A field without a value in an insert keeps the default of its column if
the parts of the model leave it out, as the protobuf extension does for the columns with a Leadseal default.

**Verify:** The write runs, and the row has the value.

## Related

- [Write rows](write-rows.md)
- [Troubleshooting reads](../reads/troubleshooting-reads.md)
- [Basepoint troubleshooting writes](https://github.com/zZHorizonZz/basepoint/blob/main/docs/writes/troubleshooting-writes.md)

# Writes

A write inserts entities, or runs a Basepoint update or delete of a table, in which you name the fields of the model.
Cinder sends each value through the codecs of its field, runs the parts of the model, and reads back the rows you
wrote when you ask for them.

## Features

**Writes by key.** Insert, upsert, update and delete one row by its key, and update the fields of an AIP-134 update
mask. See [Write rows](write-rows.md).

**Conflicts.** Skip the entities whose key or unique fields a row already has, or merge them into that row. See
[Write rows](write-rows.md#insert-with-conflicts).

**Updates and deletes of a filter.** Change or delete every row of a filter, and read back the rows that changed. See
[Write rows](write-rows.md#update-and-delete-rows-of-a-filter).

An update or a delete needs a filter or `all()`, so that none changes every row by mistake. The errors of writes are
in [Troubleshooting writes](troubleshooting-writes.md).

## Related

- [Reads](../reads/README.md)
- [Transactions](../transactions/README.md)
- [Basepoint writes](https://github.com/zZHorizonZz/basepoint/blob/main/docs/writes/README.md)

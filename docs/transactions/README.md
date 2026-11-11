# Transactions

A transaction runs the operations of some work, on any backend of the database, as one. The database commits it when
the future of the work succeeds and rolls it back when the future fails, so the work never commits or rolls back by
itself. Outside a transaction, each statement commits as it runs.

## Features

**Commit and roll back.** Run work in a transaction, roll back work that succeeds, and run actions after the commit
or the rollback, such as one that publishes an event. See [Run in a transaction](run-in-a-transaction.md).

**Savepoints.** A transaction in the work of another runs as a savepoint, so that only the changes of failed inner
work roll back. See [Run in a transaction](run-in-a-transaction.md#nest-transactions).

**Isolation and access.** Set the isolation level and the read-only mode of a transaction. Each database does its own
with them; see [Databases](../databases/README.md).

The transaction of some work is held on its Vert.x context, so the operations of the work find it without a
parameter. The errors of transactions are in [Troubleshooting transactions](troubleshooting-transactions.md).

## Related

- [Writes](../writes/README.md)
- [Stream rows](../reads/stream-rows.md)

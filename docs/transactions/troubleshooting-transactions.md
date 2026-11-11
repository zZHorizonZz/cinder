# Troubleshooting transactions

The errors of transactions, the most frequent first.

## A statement of the work runs outside the transaction

**Cause:** An operation of the work runs on another Vert.x context than the work, such as in the callback of a timer,
of an HTTP client or of another library that completes its future on its own context. The transaction is held on the
context of the work, so an operation on another context does not find it, and commits as it runs.

**Fix:** Start each operation of the work from the futures of the work, such as in `compose`, and not from a callback
of another context. To run an operation after the transaction, add it with `onCommit`.

**Verify:** When the work fails, none of its rows are in the table.

## The transaction has ended.

```text
java.lang.IllegalStateException: The transaction has ended.
```

**Cause:** `onCommit`, `onRollback` or `rollbackOnly` after the future of the work completed, such as in an action
that runs after the commit, or from a callback that runs later.

**Fix:** Add the actions and mark the rollback while the work runs, before its future completes.

**Verify:** The actions run after the commit, or after the rollback.

## The pool gives its connections on no Vert.x context, so a transaction has no context to run on.

```text
java.lang.IllegalStateException: The pool gives its connections on no Vert.x context, so a transaction has no context to run on.
```

**Cause:** A transaction started off every Vert.x context, with a pool that gives its connections on none either. A
transaction keeps its state on a Vert.x context.

**Fix:** Start the transaction on a Vert.x context, such as in a verticle, or with
`vertx.getOrCreateContext().runOnContext(...)`.

**Verify:** The transaction commits, and its rows are in the table.

## A read-only transaction writes on SQL Server

**Cause:** SQL Server has no read-only transactions, so a transaction with `withReadOnly(true)` can write there.

**Fix:** Do not count on the access mode to refuse writes on SQL Server. Give the work a login that cannot write, if
it must not.

**Verify:** The work of the transaction only reads.

## Related

- [Run in a transaction](run-in-a-transaction.md)
- [Databases](../databases/README.md)

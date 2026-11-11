# Run in a transaction

Every operation in the work of a transaction, on any backend of the database, runs in it. The database commits the
transaction when the future of the work succeeds, and rolls it back when the future fails, so the work never commits
or rolls back by itself. Outside a transaction, each statement commits as it runs.

## Before you begin

The examples use backends of orders and payments, with the filters of `dev.horizon.basepoint.filter.Filter` imported
statically. Start a transaction on a Vert.x context, such as in a verticle or a handler. Off every context, it runs on
the context of the connections of the pool.

## Run work in a transaction

1. Give the work to `database.transaction`:

   ```java
   Future<Boolean> paid = database.transaction(tx -> orders.single(orders.query().where(eq("id", 42L))
           .lock(LockMode.UPDATE))
       .compose(order -> payments.insert(payment(order)))
       .compose(payment -> orders.updateById(42L, update -> update.set("state", "PAID"))));
   ```

2. To run an action once the transaction commits, such as one that publishes an event, add it with `onCommit`. Then
   nothing sees a change that rolls back:

   ```java
   database.transaction(tx -> orders.updateById(42L, update -> update.set("state", "PAID"))
       .andThen(done -> tx.onCommit(() -> events.publish("order.paid", 42L))));
   ```

   `onRollback` runs an action once the transaction rolls back instead. The actions run after the transaction ends,
   on its Vert.x context. A statement of an action runs outside the transaction, on its own connection or in a
   transaction of its own.

3. To roll back work that succeeds, such as for a dry run, call `tx.rollbackOnly()`. The future of the transaction
   still gives the result of the work.

A transaction ends before its actions run. After it ends, `onCommit`, `onRollback` and `rollbackOnly` throw an
`IllegalStateException`.

## Verify

The rows that the work wrote are in the table after the future succeeds, and none of them after it fails. The actions
of `onCommit` run after the future succeeds.

## Nest transactions

A transaction in the work of another runs as a savepoint. When the inner work fails, only its changes roll back, and
the outer work goes on if it handles the failure:

```java
database.transaction(tx -> orders.insert(order)
    .compose(created -> database.transaction(inner -> loyalty.award(created)).recover(failed -> succeededFuture()))
    .mapEmpty());
```

The actions of a savepoint run when its outermost transaction commits, and not if the savepoint rolls back.

## Set the isolation level and the access mode

```java
database.transaction(TransactionOptions.defaults().withIsolation(Isolation.SERIALIZABLE).withReadOnly(true),
    tx -> report());
```

A savepoint keeps the options of its outermost transaction. For what each database does with the options, see
[Databases](../databases/README.md).

## Related

- [Write rows](../writes/write-rows.md)
- [Troubleshooting transactions](troubleshooting-transactions.md)
- [Databases](../databases/README.md)

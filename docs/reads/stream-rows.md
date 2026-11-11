# Stream rows

A stream reads the rows of a query through a cursor, as they come from the database, with the back pressure of a
Vert.x stream. It holds at most one batch in memory.

## Before you begin

The examples use an `OrdersBackend` of `Order` messages, with the filters of `dev.horizon.basepoint.filter.Filter`
imported statically. Run them on a Vert.x context.

## Stream a query

1. Start the stream of a query, with the rows that its cursor fetches at a time:

   ```java
   ReadStream<Order> open = orders.table().stream(orders.query().where(eq("state", "OPEN")).orderBy("id"), 500);
   ```

2. Handle each entity, and the end of the stream:

   ```java
   open.handler(order -> ship(order))
       .exceptionHandler(failed -> log(failed))
       .endHandler(done -> System.out.println("all shipped"));
   ```

## Verify

The handler gets each row of the query once, in its order, and the end handler runs after the last one. `pause` and
`fetch` of the stream limit the rows that the handler gets.

## Streams and transactions

In a transaction, the cursor uses its connection. Without one, the stream takes a connection and a read-only
transaction of its own, and gives them back when it ends or fails.

## Includes and parts

The includes of the query and the parts of the model complete each batch of rows, as they complete the rows of a
list.

## Related

- [Read rows](read-rows.md)
- [Run in a transaction](../transactions/run-in-a-transaction.md)

# Read rows

Find rows by key or by a filter, sort them, read some of their fields, count them and lock them.

## Before you begin

The examples use an `OrdersBackend` of `Order` messages, as [Get started](../get-started.md) writes it, with these
imports:

```java
import static dev.horizon.basepoint.Direction.DESC;
import static dev.horizon.basepoint.expression.Expression.*;
import static dev.horizon.basepoint.filter.Filter.*;
```

## Find rows

1. Find rows by key:

   ```java
   Future<Optional<Order>> order = orders.findById(42L);
   Future<List<Order>> some = orders.findAllById(List.of(1L, 2L, 3L));
   Future<Boolean> exists = orders.existsById(42L);
   ```

2. Find rows by a filter, as a Basepoint filter or as AIP-160 text, and sort them:

   ```java
   Future<List<Order>> large = orders.list(orders.query().where(gt("total_cents", 10_000)).limit(50));
   Future<List<Order>> open = orders.list(orders.query().where("state = \"OPEN\" AND total_cents > 10000")
       .orderBy("create_time", DESC)
       .orderBy("id"));
   ```

3. Read one row, a count or whether rows exist:

   ```java
   Future<Order> only = orders.single(orders.query().where(eq("customer_id", "anne")));
   Future<Optional<Order>> latest = orders.first(orders.query().orderBy("create_time", DESC));
   Future<Long> count = orders.count(orders.query().where(eq("state", "OPEN")));
   Future<Boolean> any = orders.exists(orders.query().where(eq("state", "OPEN")));
   ```

   `single` fails with a `NoSuchElementException` if the query has no row, and an `IllegalStateException` if it has
   more than one. `count` and `exists` read the filter of the query, not its order or its limit.

## Verify

Each future gives the rows of its filter, as entities. A statement that names a field that the model does not have
fails its future with an `IllegalArgumentException`, before anything runs. See
[Troubleshooting reads](troubleshooting-reads.md).

## Filter with the text of a request

`query(filter, orderBy)` takes the AIP-160 filter and the AIP-132 order of a request, and checks their fields at once.
A filter or an order that does not fit the model throws an `InvalidFilterException` or an `InvalidOrderByException`,
which a service gives back to its client as an invalid argument:

```java
Query requested = orders.query(request.getFilter(), request.getOrderBy());
```

A value of a text filter must be a value of its field. A filter such as `total_cents = 10.5` fails with an
`IllegalArgumentException`.

A field that the database stores as a JSON object, such as a protobuf map or message, can be filtered by one of its
keys, whose values compare as text: `labels.env = "prod"`, or `eq(key("labels", "env"), "prod")`. `labels:env` finds
the rows whose map has the key `env`.

## Filter by a function of a field

Basepoint's expressions name fields too, and Cinder maps them inside `concat`, the functions, `length`, moved
timestamps and `when`:

```java
orders.list(orders.query().where(eq(upper(column("customer_id")), "ANNE")));
orders.list(orders.query().where(gt(add(column("create_time"), Duration.ofDays(30)), Instant.now())));
```

## Sort an enum

An enum field sorts by the order of its values on PostgreSQL and MariaDB, which store it as an enum type, and by the
text of its values on SQL Server and SQLite. `orderBy("state")` gives `OPEN` before `CANCELLED` on the first two, if
the enum lists `OPEN` first, and `CANCELLED` first on the others.

## Read some fields

Name the fields to read with `columns`. The other fields of the entities are unset:

```java
Future<List<Order>> summaries = orders.list(orders.query().columns("id", "state", "total_cents"));
```

`table.query(ReadMask)` starts a query of the fields of an AIP-157 read mask:

```java
Future<List<Order>> masked = orders.list(orders.table().query(ReadMask.parse(String.join(",",
    request.getReadMask().getPathsList()))));
```

## Aggregate rows

Run a query of aggregates with `rows`, which gives the rows as the database gives them:

```java
Future<RowSet<Row>> totals = orders.table().rows(orders.query()
    .where(eq("state", "PAID"))
    .select("customer_id", column("customer_id"))
    .select("total", sum("total_cents"))
    .select("orders", count())
    .groupBy("customer_id"));
```

## Lock rows

In a transaction, a query can lock the rows it reads:

```java
database.transaction(tx -> orders.single(orders.query().where(eq("id", 42L)).lock(LockMode.UPDATE))
    .compose(order -> pay(order)));
```

A queue of jobs takes the next free row with `LockMode.UPDATE_SKIP_LOCKED`, so that two workers never take the same
one.

## Related

- [Include related rows](include-related-rows.md)
- [List pages](list-pages.md)
- [Basepoint filters](https://github.com/zZHorizonZz/basepoint/blob/main/docs/filters/filter-rows.md)

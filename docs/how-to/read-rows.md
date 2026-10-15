# Read rows

A query reads the rows of a model. It starts from a backend, with `findAll()`, `find(filter)` or a method that
finds by key, and runs with a terminal method, such as `list`. The examples use an `OrdersBackend` of `Order`
messages, with the filters of `dev.horizon.basepoint.Filter` imported statically.

## Find rows

1. Find rows by key:

   ```java
   Future<Optional<Order>> order = orders.findById(42L);
   Future<List<Order>> some = orders.findAllById(List.of(1L, 2L, 3L));
   Future<Boolean> exists = orders.existsById(42L);
   ```

2. Find rows by a filter, as a Basepoint filter or as AIP-160 text:

   ```java
   Future<List<Order>> large = orders.find(gt("total_cents", 10_000)).limit(50).list();
   Future<List<Order>> open = orders.find("state = \"OPEN\" AND total_cents > 10000")
       .orderBy("create_time desc, id")
       .list();
   ```

   A value of a text filter must be a value of its field. A filter such as `total_cents = 10.5` fails with an
   `IllegalArgumentException`.

3. Read one row, a count or whether rows exist:

   ```java
   Future<Order> only = orders.find(eq("customer_id", "anne")).single();
   Future<Optional<Order>> latest = orders.findAll().orderBy("create_time desc").first();
   Future<Long> count = orders.find(eq("state", "OPEN")).count();
   Future<Boolean> any = orders.find(eq("state", "OPEN")).exists();
   ```

   `single` fails with a `NoSuchElementException` if the query has no row, and an `IllegalStateException` if it has
   more than one.

## Read some fields

A read mask, as AIP-157 has it, reads only some fields. The other fields of the entities are unset:

```java
Future<List<Order>> summaries = orders.findAll().mask("id,state,total_cents").list();
```

## Include related rows

An include reads the rows of a relation into its field, in the same statement. Each database aggregates them with
its own JSON functions, so a list of orders with their items is one statement, not one per order:

```java
Future<List<Order>> withItems = orders.find(eq("state", "OPEN"))
    .include("customer")
    .include("items", items -> items.orderBy("position").limit(20))
    .list();
```

The query of the related rows narrows them as any query does, and can include their own relations. The parts of the
related model narrow the related rows too.

## List pages

`list` takes an AIP-132 and AIP-158 list request as is: its filter, its order, its page size and its page token:

```java
Future<Page<Order>> page = orders.list(new PageRequest("state = \"OPEN\"", "create_time desc", 20,
    request.getPageToken()));

page.onSuccess(found -> respond(found.items(), found.nextPageToken()));
```

The page has the token of the next page, which is empty on the last page. A page has at most
`Database.MAX_PAGE_SIZE` rows, or the number that `Database.Builder.maxPageSize` sets, whatever the request asks for.

A token works only for the list that gave it. Give every database that serves the same clients the same
`pageTokenKey`, so that a token of one is good at the others.

## Stream rows

A stream reads the rows through a cursor, as they come from the database, with the back pressure of the stream. It
holds at most one batch in memory:

```java
orders.find(eq("state", "OPEN")).stream(500)
    .handler(order -> ship(order))
    .endHandler(done -> System.out.println("all shipped"));
```

In a transaction, the cursor uses its connection. Without one, the stream takes a connection and a read-only
transaction of its own, and gives them back when it ends or fails. Includes and the parts of the query complete each
batch.

## Aggregate rows

An aggregate groups the rows of a query and measures each group:

```java
Future<List<Aggregate.Group>> totals = orders.find(eq("state", "PAID"))
    .run(Aggregate.by("customer_id").sum("total_cents").count());

totals.onSuccess(groups -> groups.forEach(group -> System.out.println(group.keys().get("customer_id") + ": "
    + group.values().get("sum_total_cents") + " in " + group.values().get("count") + " orders")));
```

## Lock rows

In a transaction, a query can lock the rows it reads:

```java
database.transaction(tx -> orders.find(eq("id", 42L)).lock(LockMode.UPDATE).single()
    .compose(order -> pay(order)));
```

`LockMode` is Basepoint's, and each dialect writes the lock in the syntax of its database. A queue of jobs takes the
next free row with `LockMode.UPDATE_SKIP_LOCKED`, so that two workers never take the same one.

## Related resources

- [Write rows](write-rows.md)
- [Basepoint filters](https://github.com/zZHorizonZz/basepoint/blob/main/docs/reference/filters.md)

# Get started

In this guide, you add Cinder for PostgreSQL to a Maven project, map a protobuf message to a table, and read and
write its rows through a backend.

## Prerequisites

- Java 25 or later, Maven and Vert.x 5.
- A GitHub account. GitHub Packages requires a token, also for public packages.
- The Java classes of your protos, and a table made by the migrations that
  [Leadseal](https://github.com/zZHorizonZz/leadseal) makes of the same protos. Cinder never runs DDL.
- One of these databases, with its Vert.x client:

  | Database   | Minimum version | Vert.x client        |
  | ---------- | --------------- | -------------------- |
  | PostgreSQL | 13              | `vertx-pg-client`    |
  | MariaDB    | 11.4            | `vertx-mysql-client` |
  | SQL Server | 2017            | `vertx-mssql-client` |
  | SQLite     | 3.44            | `vertx-jdbc-client`  |

## 1. Add the dependencies

1. Add the GitHub Packages repository, the driver for your database and the protobuf extension to your `pom.xml`.
   This guide uses PostgreSQL:

   <!-- x-release-please-start-version -->
   ```xml
   <repositories>
     <repository>
       <id>github</id>
       <url>https://maven.pkg.github.com/zZHorizonZz/cinder</url>
     </repository>
   </repositories>

   <dependencies>
     <dependency>
       <groupId>dev.horizon</groupId>
       <artifactId>cinder-database-postgres</artifactId>
       <version>0.6.0</version>
     </dependency>
     <dependency>
       <groupId>dev.horizon</groupId>
       <artifactId>cinder-extension-protobuf</artifactId>
       <version>0.6.0</version>
     </dependency>
   </dependencies>
   ```
   <!-- x-release-please-end -->

   Also add the Vert.x client of your database, such as `io.vertx:vertx-pg-client`.

2. Create a personal access token with the `read:packages` scope, and add it to `~/.m2/settings.xml`:

   ```xml
   <settings>
     <servers>
       <server>
         <id>github</id>
         <username>YOUR_GITHUB_USERNAME</username>
         <password>YOUR_TOKEN</password>
       </server>
     </servers>
   </settings>
   ```

For MariaDB, SQL Server or SQLite, use `cinder-database-mariadb`, `cinder-database-sqlserver` or
`cinder-database-sqlite`.

## 2. Map a message

Give the message the `leadseal.v1.table` option, and its key field the `primary_key` option:

```proto
message Order {
  option (leadseal.v1.table) = {name: "orders"};

  int64 id = 1 [(leadseal.v1.column).primary_key = true];
  string state = 2 [(leadseal.v1.column).text = {length: 20}];
  int64 total_cents = 3;
}
```

## 3. Open a database

Open a database on a Vert.x pool and the driver of the database, with the protobuf extension:

```java
Pool pool = PgBuilder.pool().connectingTo("postgresql://shop:secret@localhost:5432/shop").using(vertx).build();
Database database = Database.builder(pool, new PostgresDriver())
    .extension(new ProtobufExtension())
    .build();
```

## 4. Write a backend

A backend gives an entity of its type and its database, and gets every common operation from `Backend`. Add the
operations of your domain as methods:

```java
public record OrdersBackend(Database database) implements Backend<Order, Long> {

  @Override
  public Order prototype() {
    return Order.getDefaultInstance();
  }

  public Future<List<Order>> open() {
    return list(query().where(eq("state", "OPEN")).orderBy("id"));
  }
}
```

`query()` gives a Basepoint `Query` of the table, in which you name fields. `list` runs it and reads the entities.

## 5. Read and write rows

```java
OrdersBackend orders = new OrdersBackend(database);

orders.insert(Order.newBuilder().setId(42).setState("OPEN").setTotalCents(15000).build())
    .compose(created -> orders.updateById(42L, update -> update.set("state", "PAID")))
    .compose(paid -> orders.findById(42L))
    .onSuccess(order -> System.out.println(order.orElseThrow().getState()));
```

## Verify

Run the code. It prints `PAID`, and the table has the row:

```sql
SELECT id, state, total_cents FROM orders WHERE id = 42;
```

## Next steps

- [Read rows](reads/read-rows.md): filters, orders, masks and counts.
- [List pages](reads/list-pages.md): the List method of a resource.
- [Write rows](writes/write-rows.md): inserts with conflicts, updates and deletes.
- [Map protobuf messages](models/map-protobuf-messages.md): relations, defaults and custom options.

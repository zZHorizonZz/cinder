# Cinder

Cinder is a Java library that gives each type of entity a backend on a SQL database: the rows of its table, with
queries, includes, pages, writes and transactions. It runs on PostgreSQL, MariaDB, SQL Server and SQLite, through the
Vert.x SQL clients. [Basepoint](https://github.com/zZHorizonZz/basepoint) writes the SQL of each database.

The core knows no schema language. A model source maps types to tables. The protobuf extension maps the messages that
have the `leadseal.v1` options, which [Leadseal](https://github.com/zZHorizonZz/leadseal) makes migrations of, so one
proto is both the schema and the mapping. Cinder never runs DDL.

```java
Database database = Database.builder(pool, new PostgresDriver()).extension(new ProtobufExtension()).build();
OrdersBackend orders = new OrdersBackend(database);

Future<List<Order>> open = orders.find("state = \"OPEN\" AND total_cents > 10000")
    .include("items", items -> items.orderBy("position"))
    .orderBy("create_time desc")
    .list();
```

## Requirements

- Java 25 or later.
- Vert.x 5.
- One of these databases, with its Vert.x client:

  | Database   | Minimum version | Vert.x client        |
  | ---------- | --------------- | -------------------- |
  | PostgreSQL | 13              | `vertx-pg-client`    |
  | MariaDB    | 11.4            | `vertx-mysql-client` |
  | SQL Server | 2017            | `vertx-mssql-client` |
  | SQLite     | 3.44            | `vertx-jdbc-client`  |

## Get started

1. Add the GitHub Packages repository, the driver for your database and the protobuf extension to your `pom.xml`.
   This example uses PostgreSQL:

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
       <version>0.4.1-SNAPSHOT</version>
     </dependency>
     <dependency>
       <groupId>dev.horizon</groupId>
       <artifactId>cinder-extension-protobuf</artifactId>
       <version>0.4.1-SNAPSHOT</version>
     </dependency>
   </dependencies>
   ```
   <!-- x-release-please-end -->

   Also add the Vert.x client of your database, such as `io.vertx:vertx-pg-client`.

2. GitHub Packages requires a token, also for public packages. Create a personal access token with the
   `read:packages` scope, and add it to `~/.m2/settings.xml`:

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

3. Define a table as a message with the `leadseal.v1.table` option, and create the table with your migrations:

   ```proto
   message Order {
     option (leadseal.v1.table) = {name: "orders"};

     int64 id = 1 [(leadseal.v1.column).primary_key = true];
     string state = 2 [(leadseal.v1.column).text = {length: 20}];
     int64 total_cents = 3;
   }
   ```

4. Open a database on a Vert.x pool and the driver of the database:

   ```java
   Pool pool = PgBuilder.pool().connectingTo("postgresql://shop:secret@localhost:5432/shop").using(vertx).build();
   Database database = Database.builder(pool, new PostgresDriver())
       .extension(new ProtobufExtension())
       .build();
   ```

5. Write a backend. It gives an entity of its type and its database, and gets every common operation from `Backend`:

   ```java
   public record OrdersBackend(Database database) implements Backend<Order, Long> {

     @Override
     public Order prototype() {
       return Order.getDefaultInstance();
     }

     public Future<List<Order>> open() {
       return find(eq("state", "OPEN")).orderBy("id").list();
     }
   }
   ```

6. Read and write rows:

   ```java
   OrdersBackend orders = new OrdersBackend(database);

   orders.insert(Order.newBuilder().setId(42).setState("OPEN").setTotalCents(15000).build())
       .compose(created -> orders.updateById(42L, Assignments.none().set("state", "PAID")))
       .compose(paid -> orders.findById(42L))
       .onSuccess(order -> System.out.println(order.orElseThrow().getState()));
   ```

## Modules

| Module                      | Package                        | Contents                                                                         |
| --------------------------- | ------------------------------ | -------------------------------------------------------------------------------- |
| `cinder-database`           | `dev.horizon.cinder`           | Models, backends, operations, extensions, and the database                       |
| `cinder-database-postgres`  | `dev.horizon.cinder.postgres`  | `PostgresDriver`, on the PostgreSQL client of Vert.x                             |
| `cinder-database-mariadb`   | `dev.horizon.cinder.mariadb`   | `MariaDbDriver`, on the MySQL client of Vert.x                                   |
| `cinder-database-sqlserver` | `dev.horizon.cinder.sqlserver` | `SqlServerDriver`, on the SQL Server client of Vert.x                            |
| `cinder-database-sqlite`    | `dev.horizon.cinder.sqlite`    | `SqliteDriver`, on the JDBC client of Vert.x                                     |
| `cinder-extension-protobuf` | `dev.horizon.cinder.protobuf`  | The models of protobuf messages, the codec of their fields, and `ProtobufMapper` |

Each driver module depends on `cinder-database`. Add only the driver that you use. On the module path, each module is
named after its package, such as `dev.horizon.cinder.postgres`.

## Documentation

- Concept: [Models and backends](docs/concepts/models-and-backends.md)
- Concept: [Extensions](docs/concepts/extensions.md)
- How to: [Map protobuf messages](docs/how-to/map-protobuf-messages.md)
- How to: [Read rows](docs/how-to/read-rows.md)
- How to: [Write rows](docs/how-to/write-rows.md)
- How to: [Run in a transaction](docs/how-to/run-in-a-transaction.md)
- How to: [Write an extension](docs/how-to/write-an-extension.md)
- How to: [Add an operation](docs/how-to/add-an-operation.md)
- Reference: [Drivers](docs/reference/drivers.md)
- Reference: [Protobuf types](docs/reference/protobuf-types.md)

## Run the tests

Each driver module runs the contract tests of `cinder-database` against its database. The contract tests are in the
test JAR of `cinder-database`. Testcontainers starts PostgreSQL, MariaDB and SQL Server in Docker, and SQLite runs in
process. Start Docker, then run:

```sh
mvn verify
```

To test another server version, set its image with a system property:

| Property                      | Default                                      |
| ----------------------------- | -------------------------------------------- |
| `cinder.test.postgres.image`  | `postgres:17-alpine`                         |
| `cinder.test.mariadb.image`   | `mariadb:11.8`                               |
| `cinder.test.sqlserver.image` | `mcr.microsoft.com/mssql/server:2022-latest` |

For example: `mvn verify -Dcinder.test.mariadb.image=mariadb:11.4`.

## Resources

- [Basepoint](https://github.com/zZHorizonZz/basepoint), which writes the SQL of each database
- [Leadseal](https://github.com/zZHorizonZz/leadseal), which makes the migrations of the protobuf tables, and has
  their options
- [Vert.x SQL clients](https://vertx.io/docs/vertx-sql-client/java/), which run the statements
- [AIP-132](https://google.aip.dev/132), [AIP-158](https://google.aip.dev/158) and
  [AIP-160](https://google.aip.dev/160), the order, the pages and the filters of list requests
- [AIP-134](https://google.aip.dev/134) and [AIP-157](https://google.aip.dev/157), update masks and read masks

## License

Apache License 2.0. For more information, refer to [LICENSE](LICENSE).

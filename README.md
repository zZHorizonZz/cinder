# Cinder

Cinder is a Java library that gives each type of entity a backend on a SQL database: the rows of its table, with
queries, includes, pages, writes and transactions. It runs on PostgreSQL, MariaDB, SQL Server and SQLite, through the
Vert.x SQL clients. You build each statement with [Basepoint](https://github.com/zZHorizonZz/basepoint), naming the
fields of your entities, and Cinder maps them to their columns, runs the statement and reads the rows back.

The core knows no schema language. A model source maps types to tables. The protobuf extension maps the messages that
have the `leadseal.v1` options, which [Leadseal](https://github.com/zZHorizonZz/leadseal) makes migrations of, so one
proto is both the schema and the mapping. Cinder never runs DDL.

```java
Database database = Database.builder(pool, new PostgresDriver()).extension(new ProtobufExtension()).build();
OrdersBackend orders = new OrdersBackend(database);

Future<List<Order>> open = orders.list(orders.query()
    .where("state = \"OPEN\" AND total_cents > 10000")
    .include("items", orders.related("items").orderBy("position"))
    .orderBy("create_time", DESC));
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

To add Cinder to a project and write your first backend, see [Get started](docs/get-started.md).

## Modules

| Module                      | Package                        | Contents                                                                         |
| --------------------------- | ------------------------------ | -------------------------------------------------------------------------------- |
| `cinder-database`           | `dev.horizon.cinder`           | Models, backends, tables, extensions, and the database                           |
| `cinder-database-postgres`  | `dev.horizon.cinder.postgres`  | `PostgresDriver`, on the PostgreSQL client of Vert.x                             |
| `cinder-database-mariadb`   | `dev.horizon.cinder.mariadb`   | `MariaDbDriver`, on the MySQL client of Vert.x                                   |
| `cinder-database-sqlserver` | `dev.horizon.cinder.sqlserver` | `SqlServerDriver`, on the SQL Server client of Vert.x                            |
| `cinder-database-sqlite`    | `dev.horizon.cinder.sqlite`    | `SqliteDriver`, on the JDBC client of Vert.x                                     |
| `cinder-extension-protobuf` | `dev.horizon.cinder.protobuf`  | The models of protobuf messages, the codec of their fields, and `ProtobufMapper` |

Each driver module depends on `cinder-database`. Add only the driver that you use. On the module path, each module is
named after its package, such as `dev.horizon.cinder.postgres`.

## Documentation

The documentation is in [docs](docs/README.md), by area:

- [Models](docs/models/README.md): map types to tables, and give each type a backend.
- [Reads](docs/reads/README.md): find rows, include related rows, list pages and stream rows.
- [Writes](docs/writes/README.md): insert, upsert, update and delete rows.
- [Transactions](docs/transactions/README.md): run work in a transaction, and nest it.
- [Extensions](docs/extensions/README.md): parts, codecs, interceptors and operations of your own.
- [Databases](docs/databases/README.md): the driver of each database, and what each does differently.

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

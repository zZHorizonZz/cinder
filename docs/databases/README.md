# Databases

A driver connects Cinder to one database. It gives the Basepoint dialect that writes the SQL of the database, binds
and reads the values of its Vert.x client, and begins, ends and nests its transactions. Each driver module has one
driver:

| Database   | Minimum version | Module                      | Driver                  |
| ---------- | --------------- | --------------------------- | ----------------------- |
| PostgreSQL | 13              | `cinder-database-postgres`  | `new PostgresDriver()`  |
| MariaDB    | 11.4            | `cinder-database-mariadb`   | `new MariaDbDriver()`   |
| SQL Server | 2017            | `cinder-database-sqlserver` | `new SqlServerDriver()` |
| SQLite     | 3.44            | `cinder-database-sqlite`    | `new SqliteDriver()`    |

Add only the driver that you use, and give it to the builder of the database with a pool of its Vert.x client:

```java
Database database = Database.builder(pool, new MariaDbDriver()).extension(new ProtobufExtension()).build();
```

What each driver does with times, enums, returned rows and transactions is in
[Driver reference](driver-reference.md). The errors that differ by database are in
[Troubleshooting databases](troubleshooting-databases.md).

## Write a driver

A driver for another database implements `Driver`. It gives the Basepoint dialect of the database, and overrides the
defaults that its client or its database needs, such as `bind`, `read`, `begin` or `duplicateKey`. Run the contract
tests of the test JAR of `cinder-database` against it: subclass `ContractSuite`, and give it a `TestDatabase`, whose
schema has the columns that Leadseal makes on the database.

## Related

- [Driver reference](driver-reference.md)
- [Transactions](../transactions/README.md)
- [Basepoint dialects](https://github.com/zZHorizonZz/basepoint/blob/main/docs/dialects/README.md)

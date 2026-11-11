# Driver reference

A driver binds and reads the values of the Vert.x client of its database, and begins, ends and nests its
transactions. The Basepoint dialect of the driver writes the SQL.

| Database   | Module                      | Driver                  | Vert.x client        | Pool                   |
| ---------- | --------------------------- | ----------------------- | -------------------- | ---------------------- |
| PostgreSQL | `cinder-database-postgres`  | `new PostgresDriver()`  | `vertx-pg-client`    | `PgBuilder.pool()`     |
| MariaDB    | `cinder-database-mariadb`   | `new MariaDbDriver()`   | `vertx-mysql-client` | `MySQLBuilder.pool()`  |
| SQL Server | `cinder-database-sqlserver` | `new SqlServerDriver()` | `vertx-mssql-client` | `MSSQLBuilder.pool()`  |
| SQLite     | `cinder-database-sqlite`    | `new SqliteDriver()`    | `vertx-jdbc-client`  | `JDBCPool.pool(...)`   |

On every database, an empty binary value is stored as null: a binary column holds a value or no value, never an
empty one.

## PostgreSQL

- **Times:** `timestamptz` values in UTC.
- **Enums:** an enum type, which sorts by the order of its values.
- **JSON:** the JSON values of the client.
- **Transactions:** `START TRANSACTION` with the isolation level and the access mode, and `SAVEPOINT` for a nested
  transaction.
- **Duplicate keys:** SQL state `23505`.

## MariaDB

- **Times:** `DATETIME` values in UTC.
- **Enums:** an `ENUM` column, which sorts by the order of its values.
- **Returned rows:** MariaDB gives the rows of an insert and a delete with `RETURNING`, but not those of an update
  before version 13. On every version, an update that returns its rows locks the rows of its filter, updates them by
  their keys, and reads them again.
- **Conflicts:** MariaDB skips a row that conflicts by setting its key to itself, so an insert with
  `Conflict.ignore()` also gives back the rows that it kept, unchanged.
- **Transactions:** `SET TRANSACTION` with the isolation level and the access mode, then `START TRANSACTION`.
- **Duplicate keys:** error `1062` or `1586`.
- **Settings:** the contract tests run with `setUseAffectedRows(true)` on `MySQLConnectOptions`.

## SQL Server

- **Times:** `DATETIME2` values in UTC.
- **Text:** `NVARCHAR` columns, as Leadseal makes them, so that a column holds the text of every script.
- **Enums:** text, which sorts by the text of its values.
- **Binary nulls:** a null of a binary column is bound as a binary null, because SQL Server does not convert the
  untyped null of the client to `VARBINARY`.
- **Transactions:** `BEGIN TRANSACTION`, and `SAVE TRANSACTION` for a nested transaction. The isolation level is a
  setting of the connection, so a transaction with a level sets it before it begins, and sets `READ COMMITTED` again
  after it ends. SQL Server has no read-only transactions, so a read-only transaction can also write.
- **Duplicate keys:** error `2601` or `2627`.

## SQLite

- **Times:** text in UTC with six digits of fraction, such as `2026-10-16T10:00:00.000000Z`, which sorts as the times
  do, and compares with Basepoint's `now()` and Leadseal's default of the current time.
- **Enums:** text, which sorts by the text of its values.
- **Transactions:** SQLite has no locks of rows. A transaction that writes locks the whole database, and
  transactions run one after another. A transaction that may write begins with `BEGIN IMMEDIATE`, which takes the
  lock of the database at once, so that two of them never wait for each other. A read-only transaction begins with
  `BEGIN DEFERRED`. A transaction is always serializable, whatever its isolation level.
- **Pool:** use a file database, such as `jdbc:sqlite:app.db`, not `:memory:`, because each connection of an
  in-memory database sees its own empty database.

## Related

- [Databases](README.md)
- [Run in a transaction](../transactions/run-in-a-transaction.md)
- [Basepoint dialect reference](https://github.com/zZHorizonZz/basepoint/blob/main/docs/dialects/dialect-reference.md)

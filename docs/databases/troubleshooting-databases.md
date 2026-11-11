# Troubleshooting databases

The problems that only some databases have, the most frequent first.

## SQLite finds no tables

**Cause:** A pool of an in-memory database, such as `jdbc:sqlite::memory:`. Each connection of an in-memory database
has its own empty database, so the tables that one connection made are not in the next.

**Fix:** Use a file database, such as `jdbc:sqlite:app.db`.

**Verify:** A read on a new connection finds the tables and their rows.

## Cannot insert explicit value for identity column on SQL Server

```text
io.vertx.mssqlclient.MSSQLException: {number=544, ... message='Cannot insert explicit value for identity column in table 'notes' when IDENTITY_INSERT is set to OFF.'}
```

**Cause:** An insert, or an upsert, that sets the key of an identity column. SQL Server fills an identity column
itself, as Leadseal makes it.

**Fix:** Leave the key unset in an insert, as the protobuf extension does for an identity that the message does not
set. An upsert of such a table conflicts on another unique field, with `Conflict.merge().on("email")`.

**Verify:** The insert gives the row with the key that SQL Server gave it.

## An enum sorts in another order on SQL Server or SQLite

**Cause:** PostgreSQL and MariaDB store an enum as an enum type, which sorts by the order of its values. SQL Server and
SQLite store it as text, which sorts by the text of its values.

**Fix:** To sort the same way on every database, sort by a field that holds the order as a number, or sort the entities
in Java.

**Verify:** The query gives the rows in the same order on every database.

## SQL Server loses the characters of some scripts

**Cause:** A text column of type `VARCHAR`, which holds only the characters of its code page. Leadseal makes text
columns `NVARCHAR` on SQL Server, but a table made by hand may differ.

**Fix:** Make the column `NVARCHAR`. To compare emoji and other characters outside the Basic Multilingual Plane in
`LIKE` and `=`, give the column a collation whose name ends in `_SC`, such as `Latin1_General_100_CI_AS_SC`.

**Verify:** A row with `東京` or `🍜` reads back as it was written, and a filter of the value finds it.

## A read-only transaction writes on SQL Server

**Cause:** SQL Server has no read-only transactions.

**Fix:** See [Troubleshooting transactions](../transactions/troubleshooting-transactions.md).

**Verify:** The work of the transaction only reads.

## Related

- [Driver reference](driver-reference.md)
- [Databases](README.md)

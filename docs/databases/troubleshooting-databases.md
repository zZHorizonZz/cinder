# Troubleshooting databases

The problems that only some databases have, the most frequent first.

## SQLite finds no tables

**Cause:** A pool of an in-memory database, such as `jdbc:sqlite::memory:`. Each connection of an in-memory database
has its own empty database, so the tables that one connection made are not in the next.

**Fix:** Use a file database, such as `jdbc:sqlite:app.db`.

**Verify:** A read on a new connection finds the tables and their rows.

## An insert with Conflict.ignore() gives back a row that it did not write on MariaDB

**Cause:** MariaDB skips a row that conflicts by setting its key to itself, and gives the row back with `RETURNING`.
The other databases give back only the rows that the insert wrote.

**Fix:** Do not count the entities of the insert to learn how many rows it wrote. Use `execute(entities, conflict)` of
the table, which gives the number of rows that the database wrote.

**Verify:** `execute` gives the number of new rows on every database.

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

# Cinder

Cinder reads and writes the rows of a SQL database as entities, such as protobuf messages. You build statements with
[Basepoint](https://github.com/zZHorizonZz/basepoint) and name the fields of your entities in them. Cinder maps the
fields to their columns, sends each value in the form that its column stores, runs the statement on a Vert.x SQL
client, and reads the rows back into entities. It runs on PostgreSQL, MariaDB, SQL Server and SQLite.

To write your first backend, see [Get started](get-started.md).

## Areas

| Area                                     | What you do there                                                                          |
| ---------------------------------------- | ------------------------------------------------------------------------------------------ |
| [Models](models/README.md)               | Map types of entities to tables, as protobuf messages, and give each type a backend.       |
| [Reads](reads/README.md)                 | Find rows, include related rows, list pages for a client, stream rows and aggregate them.  |
| [Writes](writes/README.md)               | Insert, upsert, update and delete rows, and read back the rows that you wrote.             |
| [Transactions](transactions/README.md)   | Run work in a transaction, nest it, and run actions after the commit.                      |
| [Extensions](extensions/README.md)       | Change every operation of a model, the values of its fields, and the run of each statement. |
| [Databases](databases/README.md)         | Pick the driver of your database, and what each database does differently.                 |

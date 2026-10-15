# Extensions

An `Extension` adds behavior to every operation of a database. Each point of an extension defaults to nothing, so an
extension overrides only the points it uses:

| Point                 | Sees                                                                | For example                                  |
| --------------------- | ------------------------------------------------------------------- | -------------------------------------------- |
| `ModelSource`         | A type of entity                                                    | Protobuf messages, Java records              |
| `Part` of a model     | Each operation of a model: its rows, its statement and its entities | Tenancy, soft deletes, keys, times, versions |
| Basepoint `Decorator` | Every node of every statement, before its SQL                       | The syntax of a dialect                      |
| `TypeCodec`           | The value of a field, both ways                                     | Encryption, obfuscation, compression         |
| `Interceptor`         | The run of each statement                                           | Metrics, tracing, retries, caching           |

The database applies its extensions in the order it gets them:

```java
Database database = Database.builder(pool, driver)
    .extension(new ProtobufExtension())
    .extension(new ObfuscationExtension(cipher))
    .extension(new TenancyExtension(() -> currentTenant()))
    .extension(new MetricsExtension(registry))
    .build();
```

## Parts

A part takes part in one operation of a model. It has three steps, and each defaults to no change:

- `write` changes the row that a write sends, as `Assignments`: the values of each entity of an insert, or the
  changes of an update.
- `shape` changes the statement of the operation, such as to narrow it with `statement.where(filter)`.
- `complete` completes the entities that the operation gives, such as to read related rows into them.

`parts(model)` of an extension gives the parts of every operation of a model. `with(part)` of an operation adds a
part to that operation alone.

## Terminals

A terminal runs the statement of an operation for a result of its own shape: a future, a stream that it reads through
the cursor of `execution.stream(statement, fetch)`, or anything else. An operation runs a terminal with
`run(terminal)`. `Aggregate` is a terminal, and the common methods, such as `list` and `count`, are terminals too, so
an extension can do all that they do.

## Codecs

A codec turns the value of a field into the value that its column stores, and back. `applies(field)` picks the fields
of the codec, such as by an attribute of the field. A model source gives each field its attributes, and the protobuf
extension gives each custom field option as the attribute of its full name.

## Interceptors

An interceptor wraps the run of each statement, and calls the next one with `next.proceed(call)`. The call has the
kind of the operation, its model and the SQL of its statement.

## Related resources

- [Write an extension](../how-to/write-an-extension.md)
- [Add an operation](../how-to/add-an-operation.md)

# Extensions

An `Extension` adds behavior to every operation of a database. Each point of an extension defaults to nothing, so an
extension overrides only the points it uses:

| Point                 | Sees                                                                | For example                                  |
| --------------------- | ------------------------------------------------------------------- | -------------------------------------------- |
| `ModelSource`         | A type of entity                                                    | Protobuf messages, Java records              |
| `Part` of a model     | Each operation of a model: its fields, statement and entities       | Tenancy, soft deletes, keys, times, versions |
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

- `write` changes the fields that a write sends, as a map of each field to its value or to a Basepoint expression:
  the values of each entity of an insert, or the fields that an update sets.
- `shape` changes the Basepoint statement of the operation, such as to narrow it with `statement.where(filter)`. The
  statement names fields, which Cinder maps to their columns after the parts.
- `complete` completes the entities that the operation gives.

`parts(model)` of an extension gives the parts of every operation of a model. `table.with(part)` gives a table whose
operations run one more part.

## Operations

An operation needs no extension point. It is a method that builds a Basepoint statement and runs it on a table, and
an extension gives it to backends as an interface that extends `Backend`. See [Add an operation](add-an-operation.md).

## Codecs

A codec turns the value of a field into the value that its column stores, and back. `applies(field)` picks the fields
of the codec, such as by an attribute of the field. A model source gives each field its attributes, and the protobuf
extension gives each custom field option as the attribute of its full name.

## Interceptors

An interceptor wraps the run of each statement, and calls the next one with `next.proceed(invocation)`. The invocation
has the kind of the operation, its model and the SQL of its statement.

The errors of extensions are in [Troubleshooting extensions](troubleshooting-extensions.md).

## Related

- [Write an extension](write-an-extension.md)
- [Add an operation](add-an-operation.md)
- [Basepoint decorators](https://github.com/zZHorizonZz/basepoint/blob/main/docs/dialects/add-a-decorator.md)

# Protobuf types

The protobuf extension turns the value of each field of a table message into the value of its column, and back. The
column type is the one that Leadseal gives the field.

| Field                                       | Column type     | Value of the column                                     |
| ------------------------------------------- | --------------- | ------------------------------------------------------- |
| `int32`, `int64`, `bool`, `float`, `double` | Its own type    | The value as is                                         |
| `uint32`, `fixed32`                         | Integer         | A `Long`, so that values above 2³¹ − 1 fit              |
| `string`                                    | Text            | The value as is                                         |
| `string`                                    | UUID            | A `UUID`                                                |
| `string`                                    | Date            | A `LocalDate`                                           |
| `string`                                    | Time            | A `LocalTime`                                           |
| `string`                                    | Timestamp       | An `Instant`                                            |
| `string`                                    | Decimal         | A `BigDecimal`                                          |
| `string`                                    | JSON            | The JSON value of the text                              |
| `bytes`                                     | Binary          | A `byte[]`                                              |
| `bytes`                                     | UUID            | A `UUID` of the 16 bytes                                |
| An enum                                     | Text            | The name of its value                                   |
| An enum                                     | Integer         | The number of its value                                 |
| `google.protobuf.Timestamp`                 | Timestamp       | An `Instant`                                            |
| `google.type.Date`                          | Date            | A `LocalDate`                                           |
| `google.type.TimeOfDay`                     | Time            | A `LocalTime`                                           |
| `google.type.Decimal`                       | Decimal         | A `BigDecimal`                                          |
| A wrapper, such as `Int64Value`             | Its value type  | The value of the wrapper, or null for an unset wrapper  |
| A repeated field                            | JSON            | An array, as the protobuf JSON mapping writes the field |
| A map, or any other message                 | JSON            | An object, as the protobuf JSON mapping writes it       |

The JSON mapping writes each value as protobuf does, such as a 64-bit integer as a string, and reads it back the same
way.

An unset field with presence, such as an `optional` field or a message field, is a null. A message field whose type
is a table, with the `ignore` option, is a relation and has no column. For more information, refer to
[Map protobuf messages](../how-to/map-protobuf-messages.md).

## Related resources

- [Leadseal](https://github.com/zZHorizonZz/leadseal), which gives each field its column type
- [The protobuf JSON mapping](https://protobuf.dev/programming-guides/json/)

# Map protobuf messages

The protobuf extension maps each message that has the `leadseal.v1.table` option to its table, as Leadseal makes the
migrations of the table. One proto is both the schema and the mapping.

## Before you begin

- Generate the Java classes of your protos.
- Create the tables with the migrations that Leadseal makes of the same protos. Cinder never runs DDL.

## Define the tables

1. Give each table message the `leadseal.v1.table` option, and each key field the `primary_key` option:

   ```proto
   syntax = "proto3";

   package shop.v1;

   import "google/protobuf/timestamp.proto";
   import "leadseal/v1/options.proto";

   message Customer {
     option (leadseal.v1.table) = {name: "customers"};

     string id = 1 [(leadseal.v1.column).primary_key = true];
     string email = 2 [(leadseal.v1.column).unique = true];
   }

   message Order {
     option (leadseal.v1.table) = {name: "orders"};

     int64 id = 1 [(leadseal.v1.column).primary_key = true];
     string customer_id = 2 [(leadseal.v1.column) = {index: true, foreign_key: {message: "shop.v1.Customer"}}];
     string state = 3 [(leadseal.v1.column).text = {length: 20}];
     int64 total_cents = 4;
     int32 version = 5;
     google.protobuf.Timestamp create_time = 6;
     optional google.protobuf.Timestamp cancel_time = 7;

     Customer customer = 8 [(leadseal.v1.column).ignore = true];
     repeated OrderItem items = 9 [(leadseal.v1.column).ignore = true];
   }

   message OrderItem {
     option (leadseal.v1.table) = {name: "order_items"};

     int64 id = 1 [(leadseal.v1.column).primary_key = true];
     int64 order_id = 2 [(leadseal.v1.column).foreign_key = {message: "shop.v1.Order", on_delete: ACTION_CASCADE}];
     string product = 3;
     int32 quantity = 4;
     int32 position = 5;
   }
   ```

   A table without a `name` is named after its message in snake case, such as `order_item` for `OrderItem`. A table
   with a `schema` option is in that schema, such as `audit.ledger`.

2. Add relations. A field whose type is another table, and which has the `ignore` option, is a relation. The foreign
   key between the two tables joins it. In the example, `Order.customer` is a relation of one customer, and
   `Order.items` a relation of many items.

3. Add the extension to the database:

   ```java
   Database database = Database.builder(pool, new PostgresDriver())
       .extension(new ProtobufExtension())
       .build();
   ```

## Leave values to the database

An insert leaves out each field that has a Leadseal column default, such as `default_now`, or an identity, when the
message does not set it. The database fills the column, and the insert reads its value back.

## Read custom field options

The extension gives each custom field option as an attribute of the field, named by the full name of the option. An
extension reads the attribute without knowing protobuf:

```java
Attribute<Boolean> obfuscated = ProtobufField.attribute(ObfuscationProto.obfuscated);
boolean secret = field.attribute(obfuscated).orElse(false);
```

## Related resources

- [Protobuf types](../reference/protobuf-types.md)
- [Leadseal](https://github.com/zZHorizonZz/leadseal)

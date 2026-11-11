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

## Verify

Read an order with its customer and its items:

```java
Table<Order> orders = database.table(Order.getDefaultInstance());
orders.single(orders.query().where(eq("id", 42L))
        .includeOne("customer", orders.related("customer"))
        .include("items", orders.related("items").orderBy("position")))
    .onSuccess(order -> System.out.println(order.getCustomer().getEmail() + ": " + order.getItemsCount()));
```

It prints the email of the customer and the number of items. A message without a primary key, or a relation without
one foreign key, fails as [Troubleshooting models](troubleshooting-models.md) describes.

## Leave values to the database

The protobuf extension gives each model a part, `ProtobufLeadsealAdapter`, that leaves the columns that the database fills to
it, as their Leadseal options say:

| Option of the field                                                     | Left out of                                  |
| ----------------------------------------------------------------------- | -------------------------------------------- |
| `generated`, such as `generated = "upper(note)"`                         | Every insert and update                      |
| A `default_value`, `default_now`, `default_today` or `default_random`     | An insert whose message does not set it      |
| `identity` of an integer column                                         | An insert whose message does not set it      |

The write reads the values of these columns back, so the message that it gives has them. An update of a generated
field, such as `update(order, "*")`, leaves it out, as AIP-134 ignores output-only fields.

To decide by options of your own, give a part in an extension of your own, as
[Write an extension](../extensions/write-an-extension.md#fill-in-values) shows. `ProtobufField.unset(value)` tells
whether a value is that of a message that does not set the field, and `ProtobufLeadsealAdapter.hasDefault(options)` and
`ProtobufLeadsealAdapter.isGenerated(options)` read the Leadseal options of a field.

## Read custom field options

The extension gives each custom field option as an attribute of the field, named by the full name of the option. An
extension reads the attribute without knowing protobuf:

```java
Attribute<Boolean> obfuscated = ProtobufField.attribute(ObfuscationProto.obfuscated);
boolean secret = field.attribute(obfuscated).orElse(false);
```

## Convert the values of your API

A value of your API that a row holds in its own form, such as a state that is a string column, gets a
`ProtobufMapper`. It reads the value from the columns of the row and writes it back, so only the mapper knows how the
value is stored:

```java
final class StateMapper implements ProtobufMapper<OrderState, OrderOrBuilder, Order.Builder> {

  @Override
  public OrderState read(OrderOrBuilder row) {
    return switch (row.getState()) {
      case "OPEN" -> OrderState.OPEN;
      case "CANCELLED" -> OrderState.CANCELLED;
      default -> throw new IllegalStateException("The order " + row.getId() + " is " + row.getState());
    };
  }

  @Override
  public Order.Builder write(Order.Builder row, OrderState state) {
    return row.setState(state.name());
  }
}
```

`read` throws `IllegalStateException` when the columns hold a value that the mapper does not know, such as a state
that a newer version of your application wrote.

## Related

- [Protobuf types](protobuf-types.md)
- [Include related rows](../reads/include-related-rows.md)
- [Leadseal](https://github.com/zZHorizonZz/leadseal)

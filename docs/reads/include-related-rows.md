# Include related rows

An include reads the rows of a relation into its field, in the same statement. Each database aggregates them with its
own JSON functions, so a list of orders with their items is one statement, not one per order.

## Before you begin

- The model has the relation, such as `items` of an order. For protobuf messages, see
  [Map protobuf messages](../models/map-protobuf-messages.md).
- The examples use an `OrdersBackend` of `Order` messages, with the filters of `dev.horizon.basepoint.filter.Filter`
  imported statically.

## Include a relation

1. Start the query of the related rows with `related`. It is a Basepoint query of the related table, joined to each
   row of the outer query by the fields of the relation:

   ```java
   Query items = orders.related("items").orderBy("position").limit(20);
   ```

2. Add it to the query under the name of the relation, with `include` for a relation of many rows, or `includeOne`
   for a relation of one:

   ```java
   Future<List<Order>> found = orders.list(orders.query().where(eq("state", "OPEN"))
       .include("items", items)
       .includeOne("customer", orders.related("customer")));
   ```

## Verify

Each order has its items, in their order, and its customer:

```java
found.onSuccess(list -> list.forEach(order -> System.out.println(order.getCustomer().getEmail() + ": "
    + order.getItemsCount() + " items")));
```

An order without items has an empty list, and an order without a customer has no customer.

## Narrow the related rows

The query of the related rows narrows them as any query does, and names the fields of the related model:

```java
orders.list(orders.query().include("items", orders.related("items").where(eq("product", "mug"))
    .columns("id", "product")));
```

The parts of the related model, such as those of tenancy, narrow the related rows too.

## Include the relations of related rows

The query of the related rows can include their own relations. Start their query from the backend or the table of
the related type:

```java
Table<Item> lines = database.table(Item.getDefaultInstance());
orders.list(orders.query().include("items", orders.related("items")
    .includeOne("product", lines.related("product"))));
```

## Related

- [Read rows](read-rows.md)
- [Troubleshooting reads](troubleshooting-reads.md)
- [Basepoint includes](https://github.com/zZHorizonZz/basepoint/blob/main/docs/queries/include-related-rows.md)

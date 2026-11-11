# Troubleshooting reads

The errors of queries, includes, pages and streams, the most frequent first. Cinder checks a statement before it runs,
so these errors fail the future of the read and send nothing to the database. A stream throws them when it starts.

## The model of the table ... has no field ...

```text
java.lang.IllegalArgumentException: The model of the table orders has no field colour.
```

**Cause:** A filter, an order, a column, an expression or a group of the query names a field that the model does not
have, such as a typo, or a field of another table.

**Fix:** Name a field of the model, such as `create_time`, or its column, such as `created_at`. A field of a related
table goes in the query of an include, not in the outer query.

**Verify:** The read gives its rows.

## The statement is of the table ..., not of the table ... of the model

```text
java.lang.IllegalArgumentException: The statement is of the table customers, not of the table orders of the model.
```

**Cause:** A query of one table run by the backend or the table of another, such as `orders.list(customers.query())`.

**Fix:** Run the query on the backend or the table that started it.

**Verify:** The read gives the entities of its table.

## The value ... is not a value of the field ...

```text
java.lang.IllegalArgumentException: The value 10.5 is not a value of the field total_cents, whose column holds Long.
```

**Cause:** A filter that compares a field with a value of another type, such as a fraction with an integer field, or
`create_time > "yesterday"`.

**Fix:** Compare with a value of the field, such as `10`, or a time such as `"2026-01-05T10:00:00Z"`. A filter of a
client with such a value is an error of the client.

**Verify:** The read gives the rows of the filter.

## The relation ... has one row. Include it with includeOne().

```text
java.lang.IllegalArgumentException: The relation customer of the model of the table orders has one row. Include it with includeOne().
```

**Cause:** `include` of a relation of one row, or `includeOne` of a relation of many rows, which have
`include it with include()` in their message.

**Fix:** Use `includeOne` for a relation of one row, such as the customer of an order, and `include` for a relation
of many, such as its items.

**Verify:** The field of the relation holds the related entity, or the list of them.

## The model of the table ... has no relation ...

```text
java.lang.IllegalArgumentException: The model of the table orders has no relation payments, so the rows of the include have no field to go to.
```

**Cause:** `related` or an include of a name that is no relation of the model. An include goes under the name of its
relation, because Cinder reads the related rows into the field of that name. Related rows with a filter of their
own, such as the recent posts of an author, need a relation field of their own.

**Fix:** Use the name of the relation, which is the name of its field. For protobuf messages, see
[Map protobuf messages](../models/map-protobuf-messages.md).

**Verify:** The read gives the entities with their related rows.

## page_token was issued for different arguments; start the listing again

```text
dev.horizon.aquery.aip158.InvalidPageTokenException: page_token was issued for different arguments; start the listing again
```

**Cause:** A page token of another list: another table, query, filter or order. A token that the database did not
give, or that was changed, fails with `page_token is not a token this service issued`, and so does a token of a
database with another page token key.

**Fix:** List the next page with the query, the filter and the order of the page that gave the token. Give every
database that serves the same clients the same `pageTokenKey`.

**Verify:** The page after the token gives the next rows.

## A page starts after the row of its token, so its query has no offset

```text
java.lang.IllegalArgumentException: A page starts after the row of its token, so its query has no offset.
```

**Cause:** `page` of a query with `offset`. A page continues after the last row of the page before, not at a number
of rows.

**Fix:** Leave out the offset, and list the pages with their tokens.

**Verify:** The page gives the first rows of the query.

## A page orders by fields, so that its token can hold the values of the last row

```text
java.lang.IllegalArgumentException: A page orders by fields, so that its token can hold the values of the last row, not by Call[function=UPPER, arguments=[Column[name=state]]].
```

**Cause:** `page` of a query that sorts by an expression. The token holds the values of the fields of the last row,
which an expression does not have.

**Fix:** Sort a page by fields. To sort by an expression, store its value in a field, such as a generated column.

**Verify:** The pages give every row once.

## The field ... is not stored as a JSON object, so it has no key ...

```text
java.lang.IllegalArgumentException: The field state of the model of the table orders is not stored as a JSON object, so it has no key name.
```

**Cause:** A filter of a key, such as `state.name = "OPEN"` or `key("state", "name")`, of a field that is not a map or
a message.

**Fix:** Filter the field itself, such as `state = "OPEN"`. Only a field that the database stores as a JSON object has
keys.

**Verify:** The read gives the rows of the filter.

## The query of the table ... has no row

```text
java.util.NoSuchElementException: The query of the table orders has no row.
```

**Cause:** `single` of a query without rows. `single` of a query with more rows fails with an `IllegalStateException`
that says `has more than one row`.

**Fix:** Use `first` when the query can have no row, and narrow the filter when it can have more than one.

**Verify:** `single` gives the one entity of the query.

## The query selects a value under the name ..., which is no field of the model

```text
java.lang.IllegalArgumentException: The query selects a value under the name double_total, which is no field of the model of the table orders. Give the value the name of a computed field, or read it with rows().
```

**Cause:** `select(name, value)` under a name that is no computed field of the model, which the entity would lose. A
name of a stored field, such as `total_cents`, fails the same way, because the value would hide the column.

**Fix:** Give the model a computed field of the name, such as an ignored field of a protobuf message, or read the value
with `rows`.

**Verify:** The entities hold the value in the computed field.

## The field ... is computed, so a statement names it only as the name of a value that it selects

```text
java.lang.IllegalArgumentException: The field order_count of the model of the table customers is computed, so a statement names it only as the name of a value that it selects, such as select("order_count", value).
```

**Cause:** A filter, an order or a write of a computed field, which has no column.

**Fix:** Filter by the expression of the value, such as `gt(orderCount, 3)`, and sort by the name of the selected
value.

**Verify:** The read gives the rows of the filter, in the order of the value.

## No model of the database has the table ...

```text
java.lang.IllegalArgumentException: No model of the database has the table payments. Start the query of the table from the table of its model, such as database.table(prototype).query().
```

**Cause:** A statement inside a statement, such as an `exists` or a `subquery`, of a table that no table of the
database has read yet, or of a table without a model. Cinder maps its fields with the model of its table.

**Fix:** Start the statement from the table of its model, such as `database.table(Payment.getDefaultInstance())
.query()`, or from a backend of the model.

**Verify:** The read gives the rows of the filter.

## The field ... has codecs that change its text

```text
java.lang.IllegalArgumentException: The field name has codecs that change its text, so a pattern does not match what its column holds.
```

**Cause:** `startsWith`, `contains`, `endsWith` or `like` of a text field whose codecs store it as other text, such as
an encrypted field. The pattern would compare with the stored text, and find nothing.

**Fix:** Filter such a field by equality, which Cinder sends through its codecs, or keep a field without a codec to
search by.

**Verify:** The read gives the rows of the filter.

## A page continues one query of the table, not a union

```text
java.lang.IllegalArgumentException: A page continues one query of the table, not a union, an intersect or an except of queries.
```

**Cause:** `page` of a combined query. A page token holds the values of the last row of one query.

**Fix:** List a combined query with `list`, or page one query with a filter that keeps the rows of both.

**Verify:** The page gives the first rows of the query.

## Related

- [Read rows](read-rows.md)
- [List pages](list-pages.md)
- [Troubleshooting models](../models/troubleshooting-models.md)

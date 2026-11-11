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
java.lang.IllegalArgumentException: The model of the table orders has no relation payments.
```

**Cause:** `related` or an include of a name that is no relation of the model. An include goes under the name of its
relation, because Cinder reads the related rows into the field of that name.

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

## Related

- [Read rows](read-rows.md)
- [List pages](list-pages.md)
- [Troubleshooting models](../models/troubleshooting-models.md)

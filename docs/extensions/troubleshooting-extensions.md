# Troubleshooting extensions

The errors of parts, codecs and interceptors, the most frequent first.

## A part has no effect on a write

**Cause:** `write` of a part returns the map that it got after it changed a copy, or changes the map in place when the
map cannot be changed. The table writes the map that `write` returns.

**Fix:** Copy the map, change the copy, and return it:

```java
Map<String, Object> stamped = new LinkedHashMap<>(row);
stamped.put("update_time", new Now());
return stamped;
```

**Verify:** The row has the value that the part wrote.

## The model of the table ... has no field ...

```text
java.lang.IllegalArgumentException: The model of the table orders has no field tenant.
```

**Cause:** A part of every model that names a field that only some models have, such as `tenant` in `write` or
`shape`.

**Fix:** Give the part only to the models that have the field, in `parts(model)`, such as
`model.field("tenant").isPresent() ? List.of(this) : List.of()`.

**Verify:** The operations of the other models run without the part.

## A filter of a part names the column of another table

**Cause:** A filter node of your own, such as one that renders a subquery, gets columns that Cinder does not map,
because Cinder maps the fields only in the nodes of Basepoint.

**Fix:** Give the node the columns of the model, such as `model.field("parent_id").orElseThrow().column()`, rather
than the names of fields.

**Verify:** The statement runs, and the node reads the columns of its table.

## A codec gets a value that it does not expect

**Cause:** `applies` of a codec picks a field of another type than the codec handles, such as a codec of text that
applies to every field with an attribute, also to a number.

**Fix:** Check the type of the field in `applies`, such as `field.type() == String.class`, as well as its attribute.

**Verify:** The values of the other fields reach the database as they are.

## An interceptor runs a statement twice

**Cause:** An interceptor that calls `next.proceed(invocation)` more than once, such as a retry that does not wait for
the failure of the first run.

**Fix:** Call `next.proceed` again only from the failure of the run before, and only for a statement that is safe to
run twice, such as a query.

**Verify:** Each statement runs once, and once more for each retry.

## Related

- [Extensions](README.md)
- [Write an extension](write-an-extension.md)
- [Add an operation](add-an-operation.md)

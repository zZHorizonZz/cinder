# Reads

A read is a Basepoint `Query` of a table, in which you name the fields of the model. A backend or a table runs it,
and reads the rows into entities. Start a query with `query()` of a backend or a table.

## Features

**Filters, orders and masks.** Filter in Java or with AIP-160 text, sort, read only some fields, count, and lock the
rows you read. See [Read rows](read-rows.md).

**Related rows.** Read the rows of a relation into its field, in the same statement, such as the items of each
order. See [Include related rows](include-related-rows.md).

**Pages.** Serve the AIP List method of a resource, with its filter, its order, its page size and its page token.
See [List pages](list-pages.md).

**Streams.** Read many rows through a cursor, with the back pressure of a Vert.x stream. See
[Stream rows](stream-rows.md).

A query that names no fields reads every field of the model, and a query that names some reads only those. The
errors of reads are in [Troubleshooting reads](troubleshooting-reads.md).

## Related

- [Writes](../writes/README.md)
- [Basepoint queries](https://github.com/zZHorizonZz/basepoint/blob/main/docs/queries/README.md)
- [Basepoint filters](https://github.com/zZHorizonZz/basepoint/blob/main/docs/filters/README.md)

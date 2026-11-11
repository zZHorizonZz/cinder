# List pages

Serve the AIP List method of a resource: a page of rows of the filter and the order of the request, and the token of
the next page.

## Before you begin

The examples use an `OrdersBackend` of `Order` messages, and the request and the response of a List method, such as
`ListOrdersRequest` and `ListOrdersResponse`.

## List a page

1. Make a `PageRequest` of the request:

   ```java
   PageRequest page = new PageRequest(request.getFilter(), request.getOrderBy(), request.getPageSize(),
       request.getPageToken());
   ```

2. List the page, and answer with its rows and the token of the next page:

   ```java
   orders.list(page).map(found -> ListOrdersResponse.newBuilder()
       .addAllOrders(found.items())
       .setNextPageToken(found.nextPageToken())
       .build());
   ```

## Verify

The page has at most the page size of the request. The token of the next page is empty on the last page. A client
that lists with the token of each page reads every row once.

## Narrow the rows of every page

`page` takes a query, whose filter and order come before those of the request, such as to list only the orders of
the caller:

```java
orders.page(orders.query().where(eq("customer_id", caller)), page);
```

A page orders by fields, so that its token can hold the values of the last row: the order of the query, then that of
the request, then the key, which makes the order total. A query of a page has no offset.

## Size the pages

A page has at most `Database.MAX_PAGE_SIZE` rows, or the number that `Database.Builder.maxPageSize` sets, whatever the
request asks for.

## Share tokens between instances

A token works only for the list that gave it: the same table, query, filter and order. The database encrypts it with
its page token key, which is random unless you set one. Give every database that serves the same clients the same
key, so that a token of one is good at the others:

```java
Database database = Database.builder(pool, driver).pageTokenKey(key).build();
```

## Related

- [Read rows](read-rows.md)
- [Troubleshooting reads](troubleshooting-reads.md)
- [AIP-158](https://google.aip.dev/158)

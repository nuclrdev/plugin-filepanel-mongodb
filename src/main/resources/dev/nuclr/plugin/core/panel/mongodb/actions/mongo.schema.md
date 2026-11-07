# mongo.schema - Sample a collection's schema

MongoDB keeps no schema, so this one is learned from a random sample (`$sample`)
of the collection. Call it before writing a query against a collection you have
not seen: it tells you the field names, their types and how many documents have
them.

- `path` is dotted, ready for a filter: `address.city`. Fields of documents
  inside arrays are listed under the array's path (`items.sku`), which is how a
  query reaches them too; `itemTypes` says what the array holds.
- `types` counts each BSON type seen: `{"string": 97, "null": 3}` means the
  field is sometimes null. Mixed types are common in MongoDB and matter for
  queries: `{"price": 5}` does not match the string `"5"`.
- `frequency` below 1 means some documents lack the field; filter with
  `{"field": {"$exists": true}}` when that matters.
- `indexes` shows what a query can use: filter and sort on indexed fields on
  large collections.
- A larger `sampleSize` (up to 1000) finds rarer fields, at more cost.

Example: `{ "connection": "prod", "database": "shop", "collection": "orders" }`

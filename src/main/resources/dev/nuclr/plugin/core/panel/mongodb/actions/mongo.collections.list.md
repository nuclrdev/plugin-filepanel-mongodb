# mongo.collections.list - List MongoDB collections

Lists the collections of a database: plain collections, views (with the
collection they read, `viewOn`) and time series collections (with their
`timeField`).

- `docs` is the estimated count from metadata: instant, but it can be off after
  an unclean shutdown and is `null` for views. For an exact figure, or a count
  with a filter, use `mongo.count`.
- `nameLike` takes `*` wildcards and ignores case: `order*` finds `orders` and
  `orderItems`.
- At most `limit` entries come back (500 by default); `truncated` says when there
  were more.

Example: `{ "connection": "prod", "database": "shop" }`

# mongo.count - Count MongoDB documents

Counts documents exactly, all of them or those matching a filter.

- `filter` is a query filter as in `find`: `{"status": "failed"}`,
  `{"createdAt": {"$gte": {"$date": "2026-01-01T00:00:00Z"}}}`.
- Values that are not plain JSON are written in Extended JSON: `{"$oid": "..."}`
  for an ObjectId, `{"$date": "..."}` for a date, `{"$numberDecimal": "1.5"}`.
- A count runs on the server for at most 60 seconds.

Example: `{ "connection": "prod", "database": "shop", "collection": "orders", "filter": { "status": "failed" } }`

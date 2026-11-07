# mongo.find - Find MongoDB documents

Runs a `find` and returns the matching documents. It is read-only.

- Check field names and types with `mongo.schema` first.
- `filter` is a query filter: `{"status": "active", "age": {"$gte": 18}}`.
  Write ObjectIds and dates in Extended JSON: `{"_id": {"$oid": "..."}}`,
  `{"createdAt": {"$gte": {"$date": "2026-01-01T00:00:00Z"}}}`.
- `projection` limits the fields returned: `{"name": 1, "email": 1}`. Use it on
  large documents: results are capped at about a million characters, and
  `truncated: true` says documents were dropped to stay under it.
- `sort` as in the shell: `{"createdAt": -1}`.
- `limit` is 20 by default, 1000 at most; page on with `skip`, or better with a
  filter on the last sort value seen.
- Documents come back in relaxed Extended JSON: an ObjectId reads
  `{"$oid": "..."}`, a date `{"$date": "..."}`.
- A find runs on the server for at most 60 seconds.

Example: `{ "connection": "prod", "database": "shop", "collection": "orders", "filter": { "status": "failed" }, "sort": { "createdAt": -1 }, "projection": { "total": 1, "customerId": 1, "createdAt": 1 }, "limit": 50 }`

# mongo.aggregate - Run a MongoDB aggregation

Runs an aggregation pipeline and returns its results. Use it for anything a find
cannot do: grouping, counting by a field, joining with `$lookup`, reshaping.

- `pipeline` is an array of stages, as in the shell:
  `[{"$match": {...}}, {"$group": {"_id": "$status", "n": {"$sum": 1}}}, {"$sort": {"n": -1}}]`.
- Put `$match` (and `$sort` on an indexed field) first so the server can use an
  index; `mongo.schema` lists the indexes.
- `$out` and `$merge` write to a collection, so a pipeline containing either,
  anywhere, is refused. Every other stage is allowed.
- At most `limit` results come back (100 by default, 1000 at most);
  `limitReached` says there may be more. Results are also capped at about a
  million characters (`truncated`).
- Values in Extended JSON as in `mongo.find`: `{"$date": "..."}`, `{"$oid": "..."}`.
- A pipeline runs on the server for at most 60 seconds.

Example: `{ "connection": "prod", "database": "shop", "collection": "orders", "pipeline": [ { "$match": { "createdAt": { "$gte": { "$date": "2026-11-01T00:00:00Z" } } } }, { "$group": { "_id": "$paymentProvider", "failed": { "$sum": { "$cond": [ { "$eq": [ "$status", "failed" ] }, 1, 0 ] } }, "total": { "$sum": 1 } } }, { "$sort": { "failed": -1 } } ] }`

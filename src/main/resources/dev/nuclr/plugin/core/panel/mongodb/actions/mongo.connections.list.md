# mongo.connections.list - List saved MongoDB connections

Lists the MongoDB connections the user has saved in Commander's MongoDB panel.
Call it first: every other `mongo.*` action names its connection by the `id` or
`name` returned here.

- Only saved connections can be used. To work with a new deployment, ask the
  user to add it in the MongoDB panel (F7 there).
- A connection is opened on first use. If it needs a password or other secret
  that is not stored, **the user** is asked in Commander, never you. If they
  dismiss the prompt, the action fails and says so.
- Secrets are never returned.
- Every `mongo.*` action is read-only: there is no insert, update or delete, and
  a pipeline with `$out` or `$merge` is refused, whatever the user's role allows.

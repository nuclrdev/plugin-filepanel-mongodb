# mongo.databases.list - List MongoDB databases

Lists the databases of a connection the user may see, with their size on disk.

- A user with rights on only some databases sees just those. A user who may not
  list databases at all still sees the database named in the connection string.
- `admin`, `config` and `local` are MongoDB's own; the application data is
  elsewhere.

Example: `{ "connection": "prod" }`

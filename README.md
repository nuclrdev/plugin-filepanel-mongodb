# 🍃 Nuclr MongoDB Explorer

Browse MongoDB in [Nuclr Commander](https://nuclr.dev) as if it were a folder on your
disk. Databases and collections are folders. Their indexes, stats, options, a sampled
schema and the newest documents are files. The keys you already use on files, for
moving around, quick view and copy, work on your data too.

And the agents in your AI projects can use the same connections: list databases and
collections, learn a collection's shape, count, find and aggregate, without ever
seeing a password.

> 🛡️ **Read-only by design.** The plugin has no insert, update, delete or drop to
> call, and an aggregation with `$out` or `$merge`, the one way a read can write, is
> refused before it is sent, nested in `$facet` or `$lookup` included. A user with
> the `read` role is still a good second lock.

```text
MongoDB Explorer
├── 🔌 Prod (Atlas)                  a saved connection
│   ├── 🗄️ shop                       a database
│   │   ├── 📁 orders                 a collection
│   │   │   ├── 📂 docs/              the newest 500 documents, as <_id>.json
│   │   │   ├── ⚙️ _options.json
│   │   │   ├── 🔑 _indexes.json
│   │   │   ├── 📊 _stats.json
│   │   │   └── 🧬 _schema.json
│   │   ├── 👁️ paidOrders             a view
│   │   └── 📊 _stats.json
│   └── 🗄️ iot
└── 🔌 Local dev
```

## ✨ Features

- 🔌 **Every kind of deployment**: a single server, a replica set, a sharded cluster
  (through its mongos routers), Atlas and anything else behind `mongodb+srv://`, a
  load balancer. Whatever the connection string says (`replicaSet`,
  `readPreference`, `directConnection`, `compressors`...) is honoured as it is.
- 🔐 **Every auth mechanism** the MongoDB driver knows: username and password
  (SCRAM-SHA-256 or SCRAM-SHA-1), LDAP, Kerberos, X.509 client certificates, AWS IAM
  and OIDC workload identity.
- 🔒 **TLS** with a private CA, client certificates as PEM (encrypted keys included)
  or PKCS#12, and an escape hatch for throwaway local servers.
- 🚇 **SSH tunnels** to servers only a bastion can see, **replica sets included**:
  the tunnel is a SOCKS proxy, so every member is reached by the name the servers
  themselves report. Or a plain **SOCKS5 proxy**, with or without a login.
- 📄 **Read a collection as files**: options and validator, indexes, storage stats and
  a schema sampled from 100 documents, viewable with `F3`, previewable with `Ctrl+Q`,
  copyable with `F5`.
- 🕒 **See the newest documents** first, as relaxed Extended JSON: by `_id` (creation
  order for ObjectIds), by the time field of a time series collection, in reverse
  insertion order for a capped one.
- 🤖 **Share it with agents** in your AI projects through seven read-only tools.
- 🗝️ **Secrets in the OS credential store**, never in a config file. A pasted
  connection string with a password in it is split up so the password is not saved
  with it.

## 🚀 Getting started

### 1️⃣ Open the panel

Press `Alt+F1` (left pane) or `Alt+F2` (right pane) and choose
**MongoDB → MongoDB Explorer**.

> 🐧 On Linux the window manager claims `Alt+F1` and `Alt+F2` first, so
> `Ctrl+Shift+1` and `Ctrl+Shift+2` do the same.

### 2️⃣ Add a connection

Press `F7`, or right-click and choose **New Connection…**. Paste the connection string
you already have; a user and password in it move to their own fields.

**Connection**

| Field | What it is |
|---|---|
| **Name** | What the connection is called in the panel, and what agents call it |
| **Connection string** | `mongodb://host:27017`, `mongodb://h1,h2,h3/?replicaSet=rs0`, the mongos hosts of a sharded cluster, or `mongodb+srv://cluster0.abcde.mongodb.net` |
| **Authentication** | None (or whatever the string says), username and password, SCRAM-SHA-256, SCRAM-SHA-1, LDAP, Kerberos, X.509, AWS IAM, OIDC |
| **Username / Password** | For AWS: access key id and secret access key, or leave both blank to use the environment (AWS_* variables, ECS task role, EC2 instance role). For OIDC: an access token, or blank with `ENVIRONMENT:azure` / `gcp` / `k8s` in the mechanism properties |
| **Auth database** | Where the user is defined; blank means the string's database, else `admin` |
| **Mechanism properties** | `KEY:value,...`, e.g. `SERVICE_NAME:mongodb` for Kerberos, `AWS_SESSION_TOKEN:...` for temporary AWS keys |

**TLS**

| Field | What it is |
|---|---|
| **Use TLS** | Turns TLS on when the string does not (`mongodb+srv` always uses it) |
| **CA certificate** | PEM file of a private or self-signed CA |
| **Client certificate** | PEM with certificate and key, or `.p12` / `.pfx`: for X.509 auth or servers that require client certificates |
| **Certificate password** | For an encrypted key or a PKCS#12 file |
| **Allow invalid host names / certificates** | For throwaway local servers only |

**Proxy / SSH**

| Field | What it is |
|---|---|
| **Connect through** | Nothing, an SSH tunnel, or a SOCKS5 proxy |
| **Host / Port / Username** | The bastion (port 22, your local user name by default) or the proxy (port 1080) |
| **Password** | The SSH password or key passphrase, or the proxy password |
| **SSH key file** | OpenSSH, PEM or PuTTY PPK; `~` is your home folder |

SSH host keys are checked against `~/.nuclr/net/known_hosts`, shared with the Net
panel and the SQL Explorer. **Test Connection** tries it all without saving anything.
`Esc` closes the dialog.

### 3️⃣ Browse

`Enter` opens a connection, a database, a collection, then `docs/`. `F3` views a file,
`Ctrl+Q` previews it, `F5` copies it to the other pane: say `_schema.json` next to your
code, or a handful of documents into a test fixture folder.

## 📁 What's in a collection folder

| File | What it holds |
|---|---|
| 📂 `docs/` | The newest 500 documents. A last row says how many there are in all |
| ⚙️ `_options.json` | The type and options: validator, capped size, time series settings, collation, and for a view what it reads and its pipeline |
| 🔑 `_indexes.json` | The indexes, as `listIndexes` returns them |
| 📊 `_stats.json` | Storage statistics from `$collStats` |
| 🧬 `_schema.json` | Every field path in a random sample of 100 documents, with its BSON types and how often it occurs |

- 👁️ Views have no indexes or stats of their own, so they get only `_options.json`
  and `_schema.json`.
- 🗄️ A database folder has `_stats.json` too (`dbStats`).
- 🙈 MongoDB's own `system.*` collections are left out, as Compass does.
- 🏷️ A document's file name is its `_id`. An id no file name can hold (`a/b`, `CON`, a
  very long one) gets a short hash added, `a_b~1f3e9c2a.json`, and so do ids that
  differ only in case or type (`1` and `"1"`), so two documents never share a name.
- 👤 A user who may only read some databases sees just those, and a user who may not
  list databases at all still sees the one named in the connection string.

## 🤖 Using it from AI projects

Agents in your AI projects reach your databases through Commander. Commander makes
every call on their behalf, with your saved connections, so an agent never sees a
password, key or token. It only ever learns connection names.

### Set it up

1. 💾 **Save the connection** in the MongoDB panel (`F7`), with **Remember** ticked
   for its secrets. You can also manage connections from **Configs → Connections** in
   Commander, on the **MongoDB** tab, or from the **Connections…** button in a profile.
2. 🧑‍💻 **Turn on Commander's tools for the agent.** In the project's agent profile,
   open the **MCP servers** tab and set **Nuclr Commander tools** to any mode. Every
   MongoDB tool only reads, so even **Read only** runs them all, and none of them ever
   asks you for approval.
3. 💬 **Ask.**

The plugin needs no setup in ai-projects itself: Commander offers every plugin's tools
to agents on its own, and the MongoDB tab in Configs → Connections appears the same way.

### 🧰 The tools

| Tool | What the agent gets |
|---|---|
| `mongo_connections_list` | Your saved connections: id, name, hosts, auth mechanism. Never a secret |
| `mongo_databases_list` | The databases it may see, with their size |
| `mongo_collections_list` | Collections, views and time series collections, with estimated counts |
| `mongo_schema` | A collection's shape from a sample: field paths, types and frequency, plus its indexes, validator or view pipeline |
| `mongo_count` | An exact count, of everything or of a filter |
| `mongo_find` | A find: filter, projection, sort, skip and limit |
| `mongo_aggregate` | Any pipeline except `$out` and `$merge` |

Filters and pipelines take Extended JSON for what plain JSON cannot say:
`{"$oid": "..."}`, `{"$date": "2026-11-01T00:00:00Z"}`. Each tool comes with a guide
the agent reads.

### 💡 Things to ask

> "What's in the shop database, and how are orders shaped?"

> "How many orders failed this week, by payment provider?"

> "Find the last 20 sign-ups without an email address."

> "Read the schema of `events` and write the TypeScript type for one document."

### 🛟 Guard rails

- 🛡️ **Read-only.** No write exists to call; `$out` and `$merge` are refused anywhere
  in a pipeline.
- ⏱️ **Bounded on the server.** Every find, count and pipeline stops after 60 seconds.
- 📏 **Bounded answers.** A find returns 20 documents by default and 1000 at most,
  about a million characters in all. `truncated` says when documents were left out.
- 🔑 **You hold the keys.** If a connection needs a secret that isn't stored,
  Commander asks *you*, never the agent. A refused password is asked for again on the
  next call.
- 📜 **Every call is logged** in Commander's agent-actions log.
- ✋ **Cancel stops a call** straight away.

## ⌨️ Keys

| Key | Where | Does |
|---|---|---|
| `F7` | Explorer root, connections | New connection |
| `F4` | On a connection | Edit the connection |
| `F8` | On a connection | Delete the saved connection (never anything on the server) |
| `F3` / `Ctrl+Q` | On a file or document | View / quick view |
| `F5` | On a file or document | Copy to the other pane |
| `Ctrl+F3` / `Ctrl+F6` / `Ctrl+F7` | Anywhere | Sort by name / by size / back to the server's order |

Right-click a connection for **New**, **Edit**, **Delete** and **Disconnect**.

## 🔐 Credentials

Passwords, secret keys, tokens, certificate passwords and SSH or proxy passwords go to
the OS credential store, keyed by connection: Windows Credential Manager, macOS
Keychain or the Linux Secret Service. They are never written to the connection profile
and never logged. Deleting a connection forgets all of them.

## 🏗️ Building

Needs Maven 3.9+ and JDK 25.

```bash
mvn clean package    # compile, test, package
mvn clean verify     # ...and sign
```

### 🧪 Tests

The suite runs headless against an in-memory deployment, so it needs no MongoDB
server. It covers connection strings and every auth mechanism, TLS certificates in
every format, proxies, the read-only guard, the schema sampler, the panel and every
agent tool, the way Commander calls them.

## 📦 What's in the bundle

`filepanel-mongodb-<version>.zip` holds the plugin jar, `plugin.json` and `lib/` with
the MongoDB Java driver, Apache MINA SSHD for tunnels and Bouncy Castle for keys and
certificates.

## 📄 License

Apache License 2.0. See [LICENSE](LICENSE).

# engram

engram is a self-hosted memory server for Claude. It stores what you want Claude
to remember across sessions, on a box you own. Each user is a separate private
store, keyed by an SSH key. It runs as one small Docker container, and it adds few
tokens to a session.

## Why engram

- Your memories live on your own server, not a third-party service.
- Each SSH key is its own private store. One user never sees the memories of another.
- A session loads memory in two calls, so the token cost stays small.
- One container runs the server, the SSH layer, and the auth proxy. Only the SSH
  port is open.

A memory is one atomic fact tagged with `category:label` pairs. Claude reaches the
server through a skill that talks over SSH, so no secret lands in a configuration
file.

## Documentation

To deploy the server, read [the admin guide](doc/admin-guide.md). To install the
skill in Claude Code, read [the user guide](doc/user-guide.md).

## Design

- Storage is Datalevin, embedded. Schema and migrations use syncopate.
- The web layer is reitit on http-kit, with a Swagger page at `/api-docs`.
- Each user is isolated by an SSH key. Reads and writes always filter by user.
- Auth is a forced-command SSH proxy. `engram-proxy` adds the trusted
  `X-Engram-User` header and forwards to the server on localhost.
- A recall streams NDJSON, so a large response never sits whole in memory.

## Develop

Run the tests:

```bash
clojure -M:test
```

Run the server locally on port 8080:

```bash
clojure -M:run
```

Build the uberjar:

```bash
clojure -T:build ci
```

## API

All routes require the `X-Engram-User` header that `engram-proxy` injects.

| Method | Path               | Purpose                                         |
| ------ | ------------------ | ----------------------------------------------- |
| GET    | `/config`          | The array of acceptable category configurations |
| GET    | `/stats`           | Recall counts for the caller                    |
| POST   | `/memories/recall` | Recall matches plus the transitive closure      |
| POST   | `/memories`        | Create one atomic fact                          |
| PUT    | `/memories/:id`    | Correct a fact                                  |
| DELETE | `/memories/:id`    | Delete a fact                                   |
| POST   | `/memories/batch`  | Apply grouped creates, updates, and deletes     |
| GET    | `/healthz`         | Health check                                    |

The configuration structure and the deploy mounts live in [the admin
guide](doc/admin-guide.md).

## License

Copyright © 2026 Crinklywrappr

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0)

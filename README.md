# engram

engram is a self-hosted, per-user memory server. A memory is one atomic fact
tagged with `category:label` pairs. The server stores facts in Datalevin and
serves them over a small REST API. Claude reaches the API through a skill that
talks over SSH, so no secret ever lands in a configuration file.

## Design

- Storage is Datalevin, embedded. Schema and migrations use syncopate.
- The web layer is reitit on http-kit, with a Swagger page at `/api-docs`.
- Each user is isolated by their SSH key. The key comment is the user id. Reads
  and writes always filter by user, so one user never sees another's memories.
- Auth is a forced-command SSH proxy. `engram-proxy` adds the trusted
  `X-Engram-User` header and forwards to the server on localhost.
- The recall call returns matches plus the transitive related-by-`src` closure,
  streamed as NDJSON so a large response never has to be held whole in memory.

The full design record is in `docs/` and the plan file.

## Develop

Run the tests:

```bash
clojure -M:test
```

Run the server locally on port 8080 (it reads the sample configuration under `deploy/`):

```bash
clojure -M:run
```

Build the uberjar:

```bash
clojure -T:build ci
```

## Deploy

The server runs in one Docker container that also runs sshd and `engram-proxy`.
Only the SSH port is exposed. Build and start it:

```bash
docker compose up --build
```

Before starting, do two things:

1. Copy `deploy/engram-config.example.edn`, edit it, and mount it at
   `/config/engram-config.edn`. Compose already mounts the sample.
2. Create `deploy/authorized_keys` from `deploy/authorized_keys.example`. Add one
   line per trusted user. Every line must start with
   `command="engram-proxy --user <id>"`.

## Use from Claude

Add an SSH host and install the skill. See `skills/CLAUDE-nudge.md` for the
`~/.ssh/config` entry and the two lines to add to the user-level `CLAUDE.md`. The
`skills/engram-recall` skill holds the recall and write protocol. The
`skills/migrate` skill imports old markdown memories.

## API

All routes below require the `X-Engram-User` header that `engram-proxy` injects.

| Method | Path               | Purpose                                        |
| ------ | ------------------ | ---------------------------------------------- |
| GET    | `/config`          | The array of acceptable category configurations |
| GET    | `/stats`           | The caller's `category:label` counts           |
| POST   | `/memories/recall` | Recall matches plus the transitive closure     |
| POST   | `/memories`        | Create one atomic fact                         |
| PUT    | `/memories/:id`    | Correct a fact                                 |
| GET    | `/healthz`         | Health check                                   |

## Category configuration

The configuration is admin-authored and mounted. It holds the stats half-life
and an array of acceptable configurations. A configuration maps each category to
a cardinality: `1` exactly one, `?` zero or one, `*` zero or more, `+` one or
more. When a memory satisfies one configuration, it is valid. Categories are
closed to the configuration. Labels are open, but must be lowercase kebab-case.
The server always imposes `src` (exactly one) and `related` (zero or more).

## License

Copyright © 2026 Crinklywrappr

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0)

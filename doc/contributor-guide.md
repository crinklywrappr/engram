# Contribute to engram

This guide helps a contributor build engram, run the tests, and find a way around
the code.

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

## JVM flags for Datalevin

Datalevin reaches into java.nio. The run, test, and build JVMs pass two
`--add-opens` flags for it. The `:run` and `:test` aliases and `build.clj` carry
them. A bare REPL or a raw test run without an alias fails to start Datalevin.
When you start your own JVM, add these flags:

```
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
```

## Version

`build.clj` derives the version as `1.0.<commit-count>` from the git revision
count, and the uberjar name carries it. Do not hand-edit a version.

## The Docker image release

The image build and push to GHCR runs only on a manual trigger. The workflow is
`Release to GHCR`, set to `workflow_dispatch`. A push never starts it. A
maintainer starts it by hand from the Actions tab, with "Run workflow".

The run does these steps in order:

1. Run the tests as a gate.
2. Build the uberjar.
3. Derive the version from the jar name, and refuse an existing tag.
4. Build the amd64 image and smoke-test `/healthz`.
5. Build and push the multi-arch image to `ghcr.io/crinklywrappr/engram`, tagged
   with the version and `latest`.
6. Tag the release commit `v<version>` and push the tag.

The multi-arch image covers linux/amd64 and linux/arm64. The version tag never
overwrites an existing one, so each release is a new version.

## A slow first eval

A cold REPL or a first eval is slow. Loading Datalevin dominates the startup cost.
This is a known dev annoyance, not a bug. A warm REPL stays fast.

## Find your way around

Read `CONTEXT.md` first. It holds the domain glossary: memory, atomic fact, src,
related, and recall. Read `docs/adr` for the settled decisions. Match this
vocabulary in the code and in the docs.

## Schema migrations

A schema change is an EDN migration under `resources/migrations`. engram uses
syncopate for migrations, pinned at 1.0.10 or later. A full-text change needs a
connection reopen on embedded Datalevin.

## The Integrant system order

The system starts in this order:

1. Open the connection.
2. Run the migrations.
3. Reopen the connection.
4. ...

The reopen after the migrations matters. Datalevin builds its full-text engine
from the schema present at connection-open time.

## The skills and their publish

The two skills live in `plugin/skills`. A push to master that touches `plugin/**`
publishes them to the `engram-skills` repo through the publish workflow. A skill
edit ships on push. See [the user guide](user-guide.md) for how a user installs
them.

# engram admin guide

This document helps a competent admin deploy engram. engram runs as one Docker
container. The container runs sshd, the `engram-proxy` forced command, and the
memory server on localhost. Only the SSH port is open.

## Port and filesystem mappings

The container listens for SSH on port 22. The host maps a port to it. The example
below maps host port 2222, because the host sshd owns port 22.

Three mounts feed the container:

- The data directory mounts at `/data`. The server writes the LMDB database here.
- The configuration file mounts at `/config/engram-config.edn`.
- The `authorized_keys` file mounts at `/home/engram/.ssh/authorized_keys`.

A directory mount and a file mount differ. The data mount is a directory. The
configuration mount and the `authorized_keys` mount are file-to-file. A file
mount needs the host file to exist first. If the host file is absent, Docker
creates a directory at that path, and the server or sshd then fails.

The entrypoint chowns `/data` to the engram user at start. The host directory
owner does not matter.

A sample `docker run`:

```bash
docker run --rm --name engram \
  -p 2222:22 \
  -v /srv/engram/data:/data \
  -v /srv/engram/config.edn:/config/engram-config.edn:ro \
  -v /srv/engram/authorized_keys:/home/engram/.ssh/authorized_keys:ro \
  --security-opt seccomp=unconfined \
  ghcr.io/crinklywrappr/engram:latest
```

The `--security-opt seccomp=unconfined` flag is required under an old Docker
seccomp profile. The modern JVM uses the `clone3` syscall. An old profile rejects
it, and the JVM fails to start a thread. The clean alternative is Docker 20.10.10
or later, whose default profile allows `clone3`.

## The running version

The server reports its version two ways. The first runs over the SSH proxy from
a client whose key the admin added. The GET carries no body, so close stdin:

```bash
ssh engram GET /healthz </dev/null
```

The response body carries a `version` field, such as `1.0.42`. The second reads
the image label on the host:

```bash
docker inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' ghcr.io/crinklywrappr/engram:latest
```

## The configuration file

The admin writes the configuration file and mounts it. The repo ships a sample at
`deploy/engram-config.example.edn`. Copy it, edit it, and mount your copy. The
`ENGRAM_CONFIG` environment variable names the container path, and the image sets
it to `/config/engram-config.edn`.

The file is an EDN map with these keys:

- `:half-life-days` sets the exponential decay for the recent recall count.
- `:configurations` is a vector of acceptable configurations.
- `:categories` is optional. It describes each category for the client.

A configuration maps each category to a cardinality. The cardinality shorthand is
`1` for exactly one, `?` for zero or one, `*` for zero or more, and `+` for one or
more. When its `category:label` pairs satisfy any one configuration, a memory is
valid.

Categories are closed to what appears in the configurations. Labels are open
vocabulary. A label must be lowercase kebab-case. The server always imposes `src`
with exactly one, and `related` with zero or more. Do not list `src` or `related`
in the file.

The `:categories` map describes each category. Each entry gives a `:description`
and a vector of `:examples`. A description is at most 256 characters. Each example
is a lowercase kebab-case token. A described category must appear in a
configuration. The server returns this map on `GET /config`, so the client picks
fitting labels.

Example:

```clojure
{:half-life-days 14
 :categories
 {"domain" {:description "the broad subject area"
            :examples ["clojure" "databases"]}}
 :configurations
 [{"domain" "+" "project" "*" "tech" "*"}]}
```

## Manage users

Each trusted user gets one line in `authorized_keys`. The admin edits this file by
hand and mounts it. Every line starts with a forced command:

```
command="engram-proxy --user alice",no-pty,no-port-forwarding,no-X11-forwarding,no-agent-forwarding ssh-ed25519 AAAA...key... alice@example
```

The `--user` value is the memory identity. The server stores the memories for
that user under it. The trailing comment is the human-readable owner.

To add a user, append one line with the public key and a `--user` identity. To
remove a user, delete the matching line.

The login user is always `engram`. engram is the one unix account in the
container. A client never logs in as the memory identity. The memory identity
comes from the forced command, not from the SSH login name.

The forced command confines each key. Without it, the key gets an interactive
shell. The restrictions after the command harden the key further.

## authorized_keys permissions

sshd reads `authorized_keys` as the engram user, not as root. The file owner and
mode must let engram read it, and strict mode must accept the owner.

Set the owner to `root:root` with mode 644. Strict mode accepts a root-owned file.
The other-read bit lets engram read it. A `root:root` file with mode 600 fails,
because engram cannot read it. The alternative is to set the owner to uid 1001
with mode 600.

## Backups

engram does not manage backups. An admin who wants backups runs a host cron job.

Back up the data directory behind the `/data` mount. The LMDB database lives at
`/data` inside the container. On the host, back up the directory you
mounted at `/data`. The configuration file and the `authorized_keys` file are
plain text the admin already keeps. A backup of the data directory is enough for
the memories.

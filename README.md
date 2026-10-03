# engram

engram is a self-hosted memory server for Claude. It stores what you want Claude
to remember across sessions, on a box you own. Each user is a separate private
store, keyed by an SSH key. It runs as one small Docker container, and minimizes
token use.

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

- To deploy the server, read [the admin guide](doc/admin-guide.md).
- To install the skill in Claude Code, read [the user guide](doc/user-guide.md).
- To work on the code, read [the contributor guide](doc/contributor-guide.md).

## License

Copyright © 2026 Crinklywrappr

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0)

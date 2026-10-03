# 01: Admin deployment doc

**What to build:** A document under `doc/` that lets a competent admin deploy engram without a step-by-step walkthrough. The admin reads it one time. It covers the configuration file structure, user management, the `authorized_keys` permissions, the port and filesystem mappings, and backups.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] The doc explains the configuration file structure. It covers `:half-life-days`, the `:configurations` array, and the cardinality shorthand (`1`, `?`, `*`, `+`). It covers the optional `:categories` map, its 256-character description limit, and its example vector.
- [ ] The doc explains that categories are closed to the configurations. Labels are open lowercase kebab-case. The server imposes `src` and `related`, and they never appear in the file.
- [ ] The doc explains user management: add a user by appending one `command="engram-proxy --user <id>"` line to `authorized_keys`, and remove a user by deleting that line. The `--user` value is the memory identity. The login user is always `engram`.
- [ ] The doc states the `authorized_keys` permission rule: owner `root:root` mode 644, because sshd reads the file as the engram user. It names the mode 600 alternative under uid 1001.
- [ ] The doc lists the required mappings: host port 2222 to container port 22, the `/data` directory bind mount, the configuration file-to-file mount onto `/config/engram-config.edn`, and the `authorized_keys` file-to-file mount. It notes that a file mount needs the host file to exist first.
- [ ] The doc names the seccomp requirement: pass `--security-opt seccomp=unconfined`, or run Docker 20.10.10 or later.
- [ ] The doc states which files to back up for an admin who wants backups: the LMDB data directory under the `/data` mount. engram does not manage backups.
- [ ] The doc respects the simple-english lint hook.

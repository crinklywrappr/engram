# 03: Adopt Telemere and log the app's activity

**What to build:** engram logs its activity through Telemere. The direct trove
dependency is gone. Startup, migrations, each request, validation rejections, and
errors each emit a structured log line. This merges the logging-library choice
and the missing-logging work into one change.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit 93776bf)

- [ ] `com.taoensso/trove` is removed from the direct dependencies in `deps.edn`.
      It can stay as syncopate's transitive dependency.
- [ ] `com.taoensso/telemere` is a direct dependency with a minimal setup.
- [ ] Startup logs one line that names the running components and the port.
- [ ] Each request logs one line with the user, the method, the path, the status,
      and the duration in milliseconds.
- [ ] A 409 validation rejection logs a line, and a handler error logs a line.
- [ ] Log output does not include a memory's content or any private user data
      beyond the user id.

# 02: Runtime-only Dockerfile with a prebuilt jar and arch-selected babashka

**What to build:** the Dockerfile drops its build stage. It copies a prebuilt uberjar into the runtime image. It picks the babashka archive from the target architecture.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] The Dockerfile has no build stage and copies a prebuilt uberjar from the build context.
- [ ] The babashka download picks its archive from the target architecture. Both `amd64` and `arm64` resolve to the correct binary.
- [ ] The container boots from the built image and answers `200` on `/healthz` from inside.
- [ ] The hardcoded `linux-amd64` babashka reference is gone.

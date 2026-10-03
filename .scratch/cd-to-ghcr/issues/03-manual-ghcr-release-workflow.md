# 03: Manual GHCR release workflow

**What to build:** a manually triggered GitHub Actions workflow publishes a multi-arch image to GHCR. It runs the tests, builds the jar, smoke-tests the `amd64` image, pushes both architectures, and tags the commit.

**Blocked by:** 01, 02.

**Status:** ready-for-agent

- [ ] The workflow triggers by hand through `workflow_dispatch`.
- [ ] The checkout uses full depth for a correct commit count.
- [ ] The test suite runs once on the `amd64` runner and gates the rest.
- [ ] The workflow builds the uberjar once on the runner.
- [ ] The `amd64` image starts and answers `200` on `/healthz` before any push.
- [ ] `buildx` pushes one manifest for `amd64` and `arm64` to `ghcr.io/crinklywrappr/engram`.
- [ ] The image carries the tags `1.0.<count>` and `latest`.
- [ ] The image carries the `org.opencontainers.image.source` label.
- [ ] Login uses the built-in `GITHUB_TOKEN` with `packages: write`.
- [ ] On success the workflow pushes the annotated git tag `v1.0.<count>`.
- [ ] If the git tag already exists, the workflow fails and does not overwrite it.

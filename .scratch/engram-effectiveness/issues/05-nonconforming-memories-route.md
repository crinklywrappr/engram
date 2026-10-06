# 05: Return the memories that do not conform to the configuration

**What to build:** A route returns the caller's stored memories that satisfy no current configuration. The response is an NDJSON stream. Each line carries the same memory shape as the recall stream, including content and tags. The route reuses the existing tag check against each memory's tags. The check reads the configuration the server currently holds. The result tracks any configuration change without a code change. A conforming memory never appears. The route returns only the nonconforming memories. It adds no annotation about which category is missing or extra.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] The route streams the caller's memories that satisfy no current configuration.
- [ ] A memory that satisfies a configuration never appears.
- [ ] Each line carries content and tags, in the recall memory shape.
- [ ] The route reuses the existing configuration tag check.
- [ ] The result reflects the server's current configuration with no code change.
- [ ] Another user's memories never appear in the response.

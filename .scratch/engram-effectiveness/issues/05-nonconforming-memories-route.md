# 05: Return the memories that do not conform to the configuration

**What to build:** A `GET /memories/nonconforming` route returns the caller's stored memories that satisfy no current configuration. The route takes no request body. The response is an NDJSON stream. Each line carries the same memory shape as the recall stream, including content and tags. The route reuses the existing tag check against each memory's tags. The check reads the configuration the server currently holds. A new `engram.memory/nonconforming` function filters the all-memories stream by an injected reject-predicate. The handler passes `config/tag-error` with the live tag-schema, so the memory namespace holds no configuration dependency. The result tracks any configuration change without a code change. A conforming memory never appears. The route returns only the nonconforming memories. It adds no annotation about which category is missing or extra.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] The route streams the caller's memories that satisfy no current configuration.
- [x] A memory that satisfies a configuration never appears.
- [x] Each line carries content and tags, in the recall memory shape.
- [x] The route reuses the existing configuration tag check.
- [x] The result reflects the server's current configuration with no code change.
- [x] Another user's memories never appear in the response.
- [x] The route is `GET /memories/nonconforming` and takes no request body.
- [x] A new `engram.memory/nonconforming` filters the all-memories stream by an injected conformance predicate.
- [x] The handler passes `config/tag-error` with the live tag-schema, so the memory namespace holds no configuration dependency.

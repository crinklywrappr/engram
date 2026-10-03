# 02: Admins can describe categories

**What to build:** An admin can describe each category in the configuration file. A client then sees those descriptions through `GET /config`. Descriptions live in a new optional top-level `:categories` map, keyed by category name. Each entry carries an optional `:description` and an optional `:examples` vector. The description is a short string, capped so it fits in a tweet. Each example is a lowercase kebab-case token, the same rule labels obey. The loader validates this map. The `/config` response carries it. The recall skill teaches the client to read it.

A prototype settled the shape:

```
{:half-life-days 14
 :categories
 {"domain" {:description "broad subject area"
            :examples ["clojure" "databases"]}}
 :configurations
 [{"scope" "?" "domain" "+" "project" "*"}]}
```

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] The loader accepts a configuration that carries an optional `:categories` map.
- [ ] The loader accepts a configuration that has no `:categories` map, because the map is optional.
- [ ] When a description is longer than 256 characters, the loader rejects the configuration.
- [ ] When an example is not a lowercase kebab-case token, the loader rejects the configuration.
- [ ] When `:categories` names a category absent from `:configurations`, the loader rejects the configuration.
- [ ] When the configuration carries a `:categories` map, `GET /config` returns it.
- [ ] The response schema names the `:categories` key, because response coercion strips keys it does not name.
- [ ] The example configuration file shows the `:categories` map with a comment.
- [ ] The `engram-recall` skill tells the client to read the category descriptions and examples.
- [ ] Tests cover a valid map, an over-length description, a non-token example, an unknown category, and the `/config` output.
- [ ] The full suite passes.

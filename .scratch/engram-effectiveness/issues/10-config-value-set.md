# 10: Let a configuration constrain a category's acceptable values

**What to build:** An admin can replace a category's cardinality shorthand with a map. The map carries a required `:cardinality` key. The cardinality value is one of the four shorthands. The map carries an optional `:one-of` key. The `:one-of` value is an EDN vector of acceptable labels for that category. The vector holds one or more lowercase kebab-case tokens. The value set applies only inside the configuration that declares it. A memory that matches that configuration must draw the category's label from the set. A label outside the set fails validation. The failure returns the current configurations like any other configuration mismatch. The server enforces lowercase kebab-case on each `:one-of` token at load time. The server rejects a map that omits `:cardinality` at load time. `GET /config` carries the map form. The `engram-recall` and `migrate` skills document the map form beside the shorthand. The `CONTEXT.md` glossary and the sample configuration in `deploy/engram-config.example.edn` show the map form.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] A configuration category accepts either a cardinality shorthand or a map.
- [ ] The map requires `:cardinality` and accepts an optional `:one-of` vector.
- [ ] The `:one-of` vector lists the acceptable labels for that category.
- [ ] A memory matching the configuration must use a label from the `:one-of` set for that category.
- [ ] A label outside the set fails validation and returns the current configurations.
- [ ] The server rejects a `:one-of` token that is not lowercase kebab-case, at load time.
- [ ] The server rejects a map that omits `:cardinality`, at load time.
- [ ] `GET /config` carries the map form.
- [ ] The `engram-recall` and `migrate` skills document the map form.
- [ ] `CONTEXT.md` and `deploy/engram-config.example.edn` show the map form.

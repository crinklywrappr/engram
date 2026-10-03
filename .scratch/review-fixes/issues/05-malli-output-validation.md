# 05: Validate route output with malli

**What to build:** Every route validates its response with malli through reitit
response coercion. Each endpoint declares a response body schema, and the swagger
page shows the response schemas. A response that does not match its schema is a
server-side error surfaced during development, not shipped silently.

**Blocked by:** 04 (the malli coercion setup and the per-route schemas land
there).

**Status:** resolved (commit f9fc2a1)

Design notes:

- reitit response coercion covers a data response. The fetch route's NDJSON path
  returns a stream, not data, so response coercion does not apply to it. Coerce
  the JSON fallback shape, and document that the NDJSON stream is uncoerced.

- [ ] reitit response coercion is enabled.
- [ ] Response schemas exist for the configurations, the stat rows, the new id on
      create, the id on update, the deleted id on delete, and the error bodies.
- [ ] The fetch route's JSON fallback has a response schema. The NDJSON stream is
      documented as uncoerced.
- [ ] The swagger page shows the response schemas.
- [ ] Tests cover the response schema for a representative endpoint.

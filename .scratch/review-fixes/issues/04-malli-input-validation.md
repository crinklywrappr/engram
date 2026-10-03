# 04: Validate route input with malli

**What to build:** Every route validates its request with malli through reitit
coercion. A structurally malformed request returns 400, and the swagger page
shows the request schemas. Separately, a malli schema compiled at load time from
the admin configurations validates a memory's tags, `src`, and `related`. A
mismatch returns 409 with the configurations, so the client refreshes its cached
configuration. This malli schema replaces the hand-rolled `config/validate` and
`config/token-error`.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit 3163887)

Design notes (from an empirical prototype, before this ticket):

- malli is already on the classpath through reitit, so there is no new
  dependency.
- The token schema must anchor the regex, because malli `:re` uses `re-find`,
  not `re-matches`. Without anchors, `Clojure_X` passes on the `lojure`
  substring. Use `[:re #"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$"]`.
- The configuration match stays a 409, not a 400. reitit coercion failures are
  400, but a configuration mismatch must stay 409 so the client refreshes. So run
  the schema derived from the configurations as a handler-level validation, not as
  reitit request coercion. Reitit coercion covers only the static request shape.
- Build the schema once at load time from the configurations. Normalize a
  memory's flat `[category label]` pairs into a `category -> [labels]` map, then
  validate. This shape came from the prototype:

```clojure
;; one closed map per configuration; a memory is valid when it satisfies any one
[:or
 [:map {:closed true}
  ["domain" {}               [:vector {:min 1} Label]]  ; cardinality "+"
  ["scope"  {:optional true} [:vector {:max 1} Label]]  ; cardinality "?"
  ["tech"   {:optional true} [:vector Label]]]          ; cardinality "*"
 ...]
;; cardinality -> vector bounds: "1" min 1 max 1, "?" max 1, "*" none, "+" min 1
;; required key for "1" and "+", optional key for "?" and "*"
;; Label = [:re #"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$"]
```

- [ ] reitit uses malli coercion. Request bodies have malli schemas. content is a
      non-empty string. `src`, each label, and each `related` value are anchored
      kebab tokens. `tags` and `pairs` are vectors of token pairs.
- [ ] A structurally malformed request returns 400.
- [ ] A well-formed request whose tags match no configuration returns 409 with
      the configurations in the body.
- [ ] The malli schema derived from the configurations is compiled once at load,
      not rebuilt on each request.
- [ ] The malli schema replaces `config/validate` and `config/token-error`, and
      those hand-rolled predicates are removed.
- [ ] `related` is validated for token shape only. It does not require the
      referenced `src` to exist.
- [ ] The swagger page shows the request schemas.
- [ ] Tests cover a 400 on a malformed request, a 409 on a configuration
      mismatch, a valid create, and the token accept and reject cases. They
      replace the current hand-rolled validation tests where needed.

# 22: Recall and search by a nested match expression

**What to build:** A match expression is a nested boolean rule that selects memories. It combines leaves with `and`, `or`, and `not`, to any depth. Three routes take one. The leaf type differs by route. One map-tagged encoding is shared across all three.

## The shared encoding

An operator is a single-key object: `{"and": [ ... ]}`, `{"or": [ ... ]}`, or `{"not": <expression>}`. The `and` and `or` operators take one or more child expressions. The `not` operator takes exactly one child. A zero-argument `and` or `or`, and a `not` with no child or more than one child, return 400. A bare leaf is a whole expression. The grill settled this shape. See `CONTEXT.md` for the "Match expression" glossary term.

## `/memories/recall/by-tags`

The body carries the expression under a `:match` field. The field replaces the old flat `:tags` body. A leaf is a tag pair, for example `["domain","clojure"]`. This route runs no full-text search, so it has no phrase leaf. The server evaluates the expression against the tag store: `and` is set intersection, `or` is set union, and `not` is set difference within the caller's own memories. A body with no `:match` returns 400. The response stays the NDJSON stream and still follows the related closure.

```json
{"match": ["domain","clojure"]}
{"match": {"or": [["tech","datalevin"], ["tech","reitit"]]}}
{"match": {"and": [["domain","clojure"], {"not": ["tech","sqlite"]}]}}
```

## `/memories/search` and `/memories/short-term/search`

The `:search` field accepts either a string or a match expression. A string is the simple path, which Datalevin parses on its own. A leaf is a term string or a `{"phrase": "..."}` object. Datalevin's search takes the `and`/`or`/`not` grammar natively, so the search half maps straight onto it. A present-but-empty string returns 400. `/memories/search` keeps its `:limit` and `:categories` fields. `/memories/short-term/search` keeps its `thought_process` query parameter and its empty-body-lists-buckets behavior.

```json
{"search": "deploy"}
{"search": {"and": ["deploy", {"or": ["datalevin","lmdb"]}, {"not": "sqlite"}, {"phrase": "to the pi"}]}}
```

## Scope

This ticket breaks the old `:tags` body and the string-only `:search` body. There is no compatibility shim. engram is single-user and self-hosted, so no external client needs a deprecation window. Three things move to the new shape in this ticket. The `engram-recall` skill moves. Every skill that posts a `by-tags` or a search body moves. The tests move.

## The recall-by-tags evaluation

The server compiles the `:match` expression into one datalog query over the `:memory/tag` pairs. A research pass settled this. See `research/22-by-tags-datalog.md`. A leaf emits three datoms through a fresh tag var. An `:or` becomes an `or-join [?e]` over each child as an `and` branch. A `:not` becomes a `not-join [?e]` over its child. An `:and` concatenates its children's clauses.

Two rules carry the whole correctness burden. The query leads with `[?e :memory/user ?u]`, so `?e` is bound before any `not-join`. Every `or-join` and `not-join` lists only `[?e]`, so each leaf's tag var stays branch-local. The compiled query emits the same `?e (distinct ?r)` rows the current recall produces, so the related closure walk is reused unchanged. A probe verified a three-operator nested expression against a real store.

The Clojure fold over per-leaf entity-id sets is the fallback. engram picks it only once the compiler's binding rules prove hard to maintain.

**Blocked by:** 11 (search scope) and 11a (the `/memories/recall/by-tags` rename) are done. The grill and the recall-by-tags research are both resolved. Nothing gates implementation.

**Status:** done

- [x] A grilling resolved the recall request shape, the degenerate cases, and the operator arity, before any code.
- [x] A research pass settled the server evaluation: one compiled datalog query over the tag pairs, feeding the related closure unchanged.
- [x] `/memories/recall/by-tags` takes the expression under `:match`, replacing the flat `:tags` body.
- [x] The server compiles the `:match` expression into one datalog query: a fresh tag var per leaf, `or-join [?e]`, `not-join [?e]`, and a lead `[?e :memory/user ?u]` clause.
- [x] `/memories/recall/by-tags` matches memories against a nested `and`/`or`/`not` expression over tag pairs.
- [x] `and` is intersection, `or` is union, and `not` is set difference within the caller's own memories.
- [x] The recall response stays the NDJSON stream and follows the related closure.
- [x] `/memories/search` and `/memories/short-term/search` take a string or a match expression under `:search`.
- [x] The search leaf is a term string or a `{"phrase": "..."}` object, over Datalevin's native grammar.
- [x] A zero-argument `and` or `or`, a malformed `not`, a missing `:match`, and an empty `:search` string all return 400.
- [x] The `engram-recall` skill, the skills that post these bodies, and the tests all move to the new shape.

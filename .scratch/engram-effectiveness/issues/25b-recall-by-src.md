# 25b: Recall by src, and the index pins sources by it

**What to build:** A caller recalls memories by their `src`. `POST /memories/recall/by-src` takes a list of srcs. It returns the caller's memories under those srcs, plus the transitive related closure. The response is the same NDJSON stream, with the JSON fallback, that `by-ids` returns. It works like `by-ids`, but it selects on `src` rather than id. A src selects a whole source, every fact migrate or a batch minted under that source name. An empty list selects nothing. A src the caller does not own contributes nothing, because the match joins on the caller's user.

The memory index then pins a source by name. The index's third request kind changes from `by-ids` to `by-src`. An index entry carries a `by-src` request that holds one or more srcs. The lapse probe asks whether the caller still owns a memory under those srcs. The `by-ids` recall route stays, because it is the post-search recovery path, but it is no longer an index request kind. The index's three request kinds become `by-tags`, `by-src`, and `thought-process`.

This is a prefactor for 25a. A src is known before any fact is minted. So a migrated entry pins its sources directly, with no id to resolve after the write.

## Why src, not id, for the index

A `by-ids` request is useful only after a search hands back ids. The index never searches. It pins known sources, so it wants the source name, not the ids a write happens to mint. A `by-src` request is stable across a re-import. It also picks up a fact added to the source later, which a frozen id list cannot.

## The probe reuses the recall path

The `by-src` lapse probe is an existence check that reuses the recall-side src lookup. The `by-tags` probe reuses the match compiler the same way. The probe and the full `recall-by-src` share one lookup, so they can never disagree about what a src yields. The probe binds a single result and drops the related closure, so it stays cheap.

**Blocked by:** 25 (the memory index schema and routes). Done.

**Status:** done

- [x] `POST /memories/recall/by-src` takes `{"srcs": [...]}` and returns the caller's memories under those srcs plus the related closure.
- [x] The response is the same NDJSON stream, with the JSON fallback, that `by-ids` returns, and it records the recall the same way.
- [x] An empty `srcs` list returns a header and no memories, and a src the caller does not own contributes nothing.
- [x] Each src is a kebab-case token, so a malformed src is a 400 at coercion.
- [x] The memory index request kind `by-ids` is replaced by `by-src`, so the three kinds are `by-tags`, `by-src`, and `thought-process`.
- [x] A `by-src` index request holds one or more srcs, and its lapse probe is an existence check beside `recall-by-src`.
- [x] The `by-ids` recall route stays, and `by-ids` is no longer an index request kind.
- [x] The `engram-recall` skill documents the `by-src` recall route and the `by-src` index request kind, and the tests move.

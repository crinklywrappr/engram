# 24: Decouple the recall-count model from the stats namespace

**What to build:** The recall-count model gets its own name, apart from the `/stats` aggregate reporting. engram serves recall counts at `/recalls`. It reports link density, the conforming fraction, and the freshness aggregates at `/stats`. The code and the data model still name the recall counts "stats". This ticket renames them for the recall count. It is a mechanical rename and split, with no behavior change.

## The code split

A new `engram.recall-count` namespace holds the recall-count model: the pair count and the per-memory count. It takes the catalog read, the pair-row read, the write planners, the `by-user` regroup, the `RecallCountWritable` protocol, and the two strategy records. The test-only reader of every stored row moves too, renamed `all-recall-rows`.

`engram.stats` keeps the `/stats` aggregates and the link density, and keeps its name. Those are a different concern from the recall count.

The write consumer moves to `engram.recall-count.writer`. Its Integrant keys become `:engram.recall-count/tag-writer` and `:engram.recall-count/mem-writer`, and `resources/system.edn` follows.

## The data migration

One forward syncopate migration renames the pair recall-count attributes. It is irreversible. The cron backup of the data directory is the rollback path.

| old | new |
|---|---|
| `:stat/user` | `:recall/user` |
| `:stat/category` | `:recall/category` |
| `:stat/label` | `:recall/label` |
| `:stat/lifetime` | `:recall/lifetime` |
| `:stat/decayed` | `:recall/decayed` |
| `:stat/last-request` | `:recall/last-recalled` |
| `:stat/user+category+label` | `:recall/user+category+label` |

The migration runs three steps. It creates the `:recall/*` attributes and the identity tuple. A function step copies each stored row's values onto the same entity's `:recall/*` attributes, so the tuple rebuilds in place. It then removes the old `:stat/*` attributes and the old tuple. No attribute is full-text, so the migration triggers no re-index.

The per-memory recall attributes stay as they are. `:memory/recall-lifetime`, `:memory/recall-decayed`, and `:memory/last-recalled` already name the recall count and live on the memory entity.

Out of scope: an orphaned `:recall/*` row for a pair no live memory carries stays in the store and stays ignored, as today. The sweep is left for later.

**Blocked by:** None. Ticket 13 (the per-memory recall count) is done, so the split covers both the pair count and the memory count.

**Status:** done

- [x] The recall-count model lives in `engram.recall-count`, apart from `engram.stats`.
- [x] `engram.stats` keeps only the `/stats` aggregates and the link density.
- [x] The write consumer lives in `engram.recall-count.writer`, with matching Integrant keys.
- [x] A migration renames the `:stat/*` attributes to `:recall/*`, including the identity tuple.
- [x] The migration copies every stored row to the new attributes, then removes the old ones.
- [x] `:stat/last-request` becomes `:recall/last-recalled`, at parity with `:memory/last-recalled`.
- [x] The `/recalls` route and the `/stats` route return the same shapes as before.
- [x] The full test suite passes, with the tests moved to the new names.

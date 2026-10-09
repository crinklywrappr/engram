# 14: Add the per-memory freshness band and the confirm route

**What to build:** Each memory gains a `last-confirmed` timestamp. The memory wire carries a `freshness` band derived from it. A new `POST /memories/confirm` route restamps a batch of the caller's memories. The admin configuration gains a second half-life, and the existing half-life is renamed so each one names the measure it shapes.

One attribute holds the stamp on the memory entity. It is `:memory/last-confirmed` (instant). Migration 004 adds it. The migration is additive. It adds no full-text attribute, so it triggers no re-index.

The two single build paths carry the stamp. A create sets `last-confirmed` to the create instant. An update sets `last-confirmed` to the update instant. A batch create and a batch update inherit the stamp, because they share those build paths. An edit reconfirms the fact. The invariant `created-at <= updated-at <= last-confirmed` holds for every new memory.

There is no data migration and no backfill. An existing memory that predates the field has no stored `last-confirmed`. The freshness read falls back to `updated-at`, then to `created-at`, for such a memory. A write or a confirm gives the memory a real `last-confirmed` and ends the fallback.

A new `engram.freshness` namespace holds the freshness kernel. The freshness value is `0.5 ^ (age / freshness-half-life)`. The `age` is the elapsed time since the last confirmation, which is `now` minus the resolved `last-confirmed`. The namespace maps the value to a band. The band is `fresh` for an age under one half-life. The band is `aging` for an age from one up to two half-lives. The band is `stale` for an age at two half-lives or beyond. Ticket 15 and ticket 16 reuse this namespace.

The band lands on the single recall wire shape that `recall-by-tags`, `recall-by-ids`, `all-memories`, `nonconforming`, and `fetch` share. A search row is left unchanged, because search ranks by relevance, not by age. The pull pattern gains `:memory/last-confirmed` so the wire builder can compute the band. The wire builder emits only `freshness`. It never emits `last-confirmed`, `created-at`, `updated-at`, or `last-recalled`. The memory read functions and their handlers take the freshness half-life. They capture one clock for each request, so a stream bands every line against the same instant. `MemoryOut` gains a required `freshness` enum of `fresh`, `aging`, or `stale`.

A new `POST /memories/confirm` route restamps a batch. The body is `{:ids [idstr ...]}`, each id the uuid wire form, so a malformed id is a 400 at coercion. The route resolves each id against the caller's own memories. It drops an id the caller does not own and an id that does not exist. It collapses a repeated id to one stamp. It sets `last-confirmed` to now for the owned memories in one transaction. It never touches `updated-at`. The response is `{:confirmed n}` at 200, where `n` is the number of memories stamped. The route is a static path, so it resolves ahead of `/memories/:id`.

The admin configuration gains a second half-life and renames the first. The existing `:half-life-days` becomes `:recall-half-life-days`, because it shapes the recent recall count for tags and memories. The new `:freshness-half-life-days` shapes the freshness band. `load-config` merges the defaults, `14` for the recall half-life and `30` for the freshness half-life, so every reader sees a complete configuration. When the file names a half-life, `validate-config` requires it to be a positive number. An absent half-life is valid and takes the default. Both keys stay bare and attach to no entity. The sample configuration shows both. The read sites in `engram.system` and `engram.handler` move to the new names.

The glossary gains the terms this ticket earns. `CONTEXT.md` defines Freshness, the fresh-aging-stale band, and Confirm. Each is a domain term with no implementation detail.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] Migration 004 adds `:memory/last-confirmed` as an additive instant attribute.
- [ ] A create sets `last-confirmed` to the create instant, and an update sets it to the update instant.
- [ ] A batch create and a batch update stamp `last-confirmed` through the shared build paths.
- [ ] There is no data migration, and the freshness read falls back to `updated-at`, then `created-at`.
- [ ] The `engram.freshness` namespace computes the value `0.5 ^ (age / freshness-half-life)` and the band.
- [ ] The band follows the half-life split: fresh under one, aging one up to two, stale at two or beyond.
- [ ] The `freshness` band lands on the recall wire for by-tags, by-ids, all-memories, nonconforming, and fetch.
- [ ] A search row carries no `freshness` band.
- [ ] The wire omits `last-confirmed`, `created-at`, `updated-at`, and `last-recalled`.
- [ ] `MemoryOut` names a required `freshness` enum of `fresh`, `aging`, or `stale`.
- [ ] `POST /memories/confirm` stamps `last-confirmed` to now for a batch of the caller's memory ids in one transaction.
- [ ] The confirm route drops an unowned or absent id, collapses a repeat, and never touches `updated-at`.
- [ ] The confirm route returns `{:confirmed n}` with the number of memories stamped.
- [ ] `:half-life-days` is renamed `:recall-half-life-days`, and `:freshness-half-life-days` is added.
- [ ] `load-config` defaults the recall half-life to 14 and the freshness half-life to 30.
- [ ] When the file names a half-life, `validate-config` requires it to be a positive number.
- [ ] The sample configuration shows both half-lives, and the read sites move to the new names.
- [ ] The glossary defines Freshness, the fresh-aging-stale band, and Confirm.
- [ ] The tests query the database directly to read the stored stamp and the confirm count.

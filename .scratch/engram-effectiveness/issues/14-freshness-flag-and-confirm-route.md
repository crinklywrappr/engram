# 14: Add the per-memory freshness flag and the confirm route

**What to build:** Each memory carries a `last-confirmed` timestamp. A create sets `last-confirmed` to the created time. An update sets `last-confirmed` to now. An edit reconfirms the fact. An existing memory that predates the field takes its `updated-at` as the backfill. A memory with no `updated-at` takes its `created-at` instead. A new `POST /memories/confirm` route sets `last-confirmed` to now for a batch of memory IDs. The body is a map of IDs. The route stamps only the IDs the caller owns. The route drops an ID the caller does not own. The route returns the count it stamped. The configuration carries a `:staleness-half-life-days` key. The server computes a freshness value from the age since `last-confirmed`. The value is `0.5 ^ (age / staleness-half-life)`. The server maps the value to a band. The band is `fresh` under one half-life. The band is `aging` between one and two half-lives. The band is `stale` beyond two half-lives. The JSON memory carries `"freshness"` with one of those three values. The JSON memory never carries `created-at`, `updated-at`, `last-recalled`, or `last-confirmed`.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] The schema adds a `last-confirmed` timestamp.
- [ ] A create sets `last-confirmed` to the created time, and an update sets it to now.
- [ ] An existing memory backfills `last-confirmed` to its `updated-at`, or its `created-at` without one.
- [ ] `POST /memories/confirm` sets `last-confirmed` to now for a batch of the caller's memory IDs.
- [ ] The confirm route drops an ID the caller does not own and returns the count it stamped.
- [ ] The configuration carries a `:staleness-half-life-days` key.
- [ ] The JSON memory carries a `freshness` band of `fresh`, `aging`, or `stale`.
- [ ] The band follows the half-life split: fresh under one, aging one to two, stale beyond two.
- [ ] The JSON memory omits `created-at`, `updated-at`, `last-recalled`, and `last-confirmed`.

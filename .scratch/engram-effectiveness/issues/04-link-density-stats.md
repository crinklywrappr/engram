# 04: Add link-density metrics to `/stats`

**What to build:** `GET /stats` reports how densely the memories link to each other. The metrics compute over `src` nodes. The links are the `related` edges. The metrics treat the edges as directed. The body reports the average out-degree. The average out-degree is the typical fan-out. The body reports the size of the largest weakly-connected component as a fraction of the nodes. A weakly-connected component treats the links as undirected and groups the sources that reach each other. The component fraction is the worst-case reach of one recall. The new metrics stand as their own part of the `/stats` body. After ticket 08 removes the recall rows, `/stats` carries no recall counts.

**Blocked by:** 08 (Move the recall counts off `/stats`).

**Status:** ready-for-agent

- [ ] `GET /stats` reports the average out-degree over `src` nodes.
- [ ] `GET /stats` reports the largest weakly-connected component as a fraction of the nodes.
- [ ] The metrics compute over the caller's own memories only.
- [ ] The link-density metrics stand as their own part of the `/stats` body.
- [ ] The response schema names the new metrics.

# Recall recovery path: search, then recall by ids

When a recall by pairs does not surface a fact, read this reference. Search finds
the candidates, then a recall by ids loads them in full. This is a two-step path.

1. `POST /memories/search` runs one ranked full-text query over content and src.
   The body is `{"search": "...", "limit": 20, "categories": ["domain"]}`. Only
   `search` is required. The `search` value is a string or a boolean expression.
   A string is the simple path. An expression nests `{"and": [ ... ]}`,
   `{"or": [ ... ]}`, `{"not": <expression>}`, and a `{"phrase": "exact words"}`
   leaf, for example `{"search": {"and": ["deploy", {"not": "staging"}]}}`.
   `limit` defaults to 20 and caps at 100. A larger `limit`
   clamps to 100. The response is `{"results": [ ... ]}`, ranked by relevance.
   Each row carries `id`, `src`, `content`, and a `score`. A row carries `tags`
   only for the categories you name. A body that names no categories returns no
   `tags`. Search follows no related links. It is the discovery step: read the
   candidates and choose the ids that matter.
2. `POST /memories/recall/by-ids` with `{"ids": ["<uuid>", ...]}` loads those
   memories in full plus their related closure, as the same NDJSON stream. A
   row carries no score. An id that is not yours, or does not exist, is skipped.
   An empty `ids` list returns a header line and no memories.

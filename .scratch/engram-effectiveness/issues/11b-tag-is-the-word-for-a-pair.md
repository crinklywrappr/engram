# 11b: "tag" becomes the word for a category:label pair

**What to build:** Lift the glossary ban on the word "tag". Make "tag" the everyday word for a category:label pair. Then rename the one remaining pocket of "pair" naming on the recall selector to match.

The glossary change is small. The category:label pair entry in `CONTEXT.md` drops its `_Avoid_: tag (when it means the pair)` line. The glossary names "tag" as the ordinary word for the pair. It keeps "category:label pair" as the structural spelling. That spelling stresses the two parts. No new cross-referenced term is needed. The engram-recall skill already relates the two. It says "a memory is one atomic fact tagged with `category:label` pairs". A reader ties the root word to the definition from that line. Every norm attached to `tags` carries over for free.

The code change finishes the rename the ban held back. Today `/memories/recall/by-tags` carries a `{"pairs": ...}` body. Once "tag" is the word, that body reads as a contradiction. After this ticket the route accepts `{"tags": [["category","label"], ...]}`. The selector key moves to `tags` on the request, the handler binding, the NDJSON header, and the JSON fallback. The recall function renames from `recall-by-pairs` to `recall-by-tags`. Its lookup renames from `eids-by-pair` to `eids-by-tag`. The tuple type stays named `Pair`. That name describes the two-part shape, not the domain role. The engram-recall skill example uses the `tags` body key.

No external consumer reads this route. The wire change needs no compatibility shim. The in-repo callers and the tests move with it.

**Out of scope:** The stats internal identifiers that still say "pair" stay as they are. These are the tag-row reader `pair-rows`, the `plan-recalls` `pair-counts` argument, and the `stats.writer` recall parameters. They are invisible to clients and carry no contradiction. A rename here is not worth the churn.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `CONTEXT.md` drops the `_Avoid_: tag (when it means the pair)` line. It names "tag" as the word for a category:label pair. It keeps "category:label pair" as the structural spelling.
- [ ] `/memories/recall/by-tags` accepts `{"tags": [["category","label"], ...]}`. A `{"pairs": ...}` body no longer matches.
- [ ] The request schema, the handler binding, the NDJSON header, and the JSON fallback all carry the selector under `tags`.
- [ ] The recall function and its lookup are renamed to `recall-by-tags` and `eids-by-tag`. Every in-repo caller moves with them.
- [ ] The tuple type stays named `Pair`.
- [ ] The engram-recall skill example uses the `tags` body key.
- [ ] The stats internal "pair" identifiers stay unchanged.
- [ ] The tests move to the `tags` body key and the renamed functions. The full suite passes.

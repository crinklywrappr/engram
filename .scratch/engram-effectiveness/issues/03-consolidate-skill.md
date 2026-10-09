# 03: Add the `/consolidate` skill

**What to build:** The `/consolidate` skill finds duplicate memories and merges them. The skill reads every memory through `GET /memories`. The skill forms every unordered pair of memories. The skill splits the pairs into fixed-size chunks. The skill spawns a Haiku subagent for each chunk, with a model override. The skill runs about ten subagents at a time. Each subagent judges its pairs for sameness. Each subagent writes its findings to a shared directory. The master agent reads the findings after the subagents finish. The master agent groups the duplicates into clusters by transitive closure. The master agent then sweeps the clusters once more for a further merge.

Each cluster resolves one of three ways. The master keeps one survivor and drops the rest. The master replaces the cluster with one new memory for a new framing. The master replaces the cluster with several new memories under one `src`. The split applies to disjoint facts. One memory cannot state those facts atomically. The master names the `src` for each cluster by its own judgment. The master repoints any `related` edge that named a dropped `src` onto the chosen `src`. The master reconciles the tags by judgment. The master unions the labels for a fact that spans two projects. The master lets a broader label supersede a narrower one, such as `scope:global` over `scope:project`. No configuration governs the merge.

The skill prepares a batch body in the create, update, and delete shape. The skill writes the batch to a review file and sends nothing to the server. The skill waits for user approval. On approval the same file is the batch body. The expensive model only launches the skill, runs the master sweep, and confirms the batch.

**Blocked by:** 02 (Stream every memory over NDJSON at `GET /memories`).

**Status:** done

- [x] The skill reads every memory through `GET /memories`.
- [x] The skill forms every unordered memory pair and splits them into fixed-size chunks.
- [x] The skill spawns a Haiku subagent per chunk and runs about ten at a time.
- [x] Each subagent judges its pairs and writes findings to a shared directory.
- [x] The master clusters duplicates by transitive closure and sweeps once more for a further merge.
- [x] A cluster resolves as one survivor, one new memory, or several new memories under one `src`.
- [x] The master names the `src` per cluster and repoints `related` edges off any dropped `src`.
- [x] The master reconciles tags by judgment, union or supersede, with no configuration key.
- [x] The skill writes the batch to a review file and sends nothing before user approval.

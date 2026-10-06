---
name: consolidate
description: >-
  Find duplicate memories in engram and merge them. Use on demand to clean up a
  memory store full of near-duplicates. Fans the pairwise comparison out to cheap
  subagents, then stages one batch for your review before any write.
---

# consolidate

Find the duplicate memories in engram and merge them into one clean set. A user
runs this skill on demand. You read the memories, send the pairwise comparison out
to cheap subagents, and cluster the results. You make the merge decisions and
stage one batch. The user approves the batch before any write.

## Transport

Every call is `ssh engram <METHOD> <PATH>`, with any request body on stdin. A GET
carries no body, so close stdin with `< /dev/null`.

```bash
ssh engram GET /memories < /dev/null
ssh engram GET /config   < /dev/null
cat batch.json | ssh engram POST /memories/batch
```

## Step 1: read every memory

Run `ssh engram GET /memories < /dev/null`. The response is an NDJSON stream. The
first line is a header object. Every line after it is one memory. Each memory
carries `id`, `content`, and `src`. Non-empty `tags` and `related` appear too.
Collect all of them.

## Step 2: form the pairs and chunk them

Form every unordered pair of two memories. For `n` memories that is `n*(n-1)/2`
pairs. Split the pairs into fixed-size chunks. A chunk of about 150 pairs works
well.

Create a working directory for the run at
`~/.claude/projects/<project-slug>/consolidate/`. The project slug is the one
Claude Code already uses for the project. Put a `chunks` directory and a
`findings` directory inside it. A chunk file lives under `chunks`, named
`chunk-<n>.json`. Each chunk file is self-contained. It lists only its own pairs,
and each pair carries both memories in full:

```json
[{"a": {"id":"...","content":"...","src":"a","tags":[["domain","clojure"]]},
  "b": {"id":"...","content":"...","src":"b","tags":[["tech","datalevin"]]}}]
```

Each chunk holds only its own pairs, so a subagent's work stays small. Do not
write every chunk file up front. You write and delete the chunk files in waves in
Step 3.

## Step 3: judge each chunk with a subagent

Spawn one subagent per chunk, with the model set to haiku. Run about ten at a
time. Start the next ten after a batch finishes. Give each subagent its chunk file
path and its findings file path.

The subagent reads its chunk. The subagent decides, for each pair, whether the two
memories state the same fact. The subagent writes the same-pairs to its findings
file under `findings`, named `chunk-<n>.json`:

```json
[{"a":"<id>","b":"<id>","reason":"both state the equality-operator preference"}]
```

The subagent writes nothing else. The subagent calls no server route. The
subagent only compares and records.

Handle the chunks in waves. For each wave, write its chunk files, spawn the
subagents, and let them finish. Fold the wave's findings into an edge set in
memory. Then delete that wave's chunk files and findings files. At most one wave
of files sits on disk at a time.

## Step 4: cluster the duplicates

Use the edge set you collected in Step 3. Each same-pair is an edge between two
memory ids. Group the ids into clusters by transitive closure. Suppose A and B are the same.
Suppose B and C are the same. Then A, B, and C form one cluster. Sweep the
clusters once more yourself. Look for a merge the pairwise pass missed.

## Step 5: resolve each cluster

Resolve each cluster one of three ways:

- Keep one survivor. Drop the rest.
- Replace the cluster with one new memory. Use this for a new framing that states
  the shared fact better.
- Replace the cluster with several new memories under one src. Use this for facts
  that are disjoint at the edges. One memory cannot state them atomically.

For each cluster, name one src. Pick an existing cluster src or a new one, by your
own judgment. Repoint the related edges. Any memory whose `related` names a dropped
src must point at the chosen src instead. A repoint swaps one src for another in
that list, so the list stays the same length.

Reconcile the tags by judgment. Union the labels for a fact that spans two
projects. Let a broader label supersede a narrower one, for example `scope:global`
over `scope:project`. No configuration rule governs this. You decide each case.

Run `ssh engram GET /config < /dev/null` first to recall the rules. Make sure each
surviving or new memory satisfies one configuration.

## Step 6: stage the batch for review

Write the whole plan to a review file at
`~/.claude/projects/<project-slug>/consolidate/batch.json`. Use the batch body
shape:

```json
{"create": [ ... ], "update": [ ... ], "delete": [ "<id>", ... ]}
```

- `create` holds each new memory, in the single-write shape with `content`, `src`,
  `tags`, and any `related`.
- `update` holds each kept memory whose tags or related changed, and each outside
  memory whose related you repointed. An update carries the `id` and only the
  fields to change. An update that sends only `related` leaves the tags and the
  content untouched.
- `delete` holds the id of every dropped memory.

Show the file. Send nothing to the server yet. Wait for the user to approve.

## Step 7: apply after approval

After the user approves, send the review file as the batch body. `POST
/memories/batch` with the file on stdin. The file is already in the batch shape,
so send it with no reformatting.

Read the result. On `HTTP 200` the body is `{"ids":[...],"applied":n}`, and the
merge is done. If the batch fails, the body is `{"errors":[...]}` with `HTTP 422`.
Nothing was written, because the batch is atomic. A tag error carries
`configurations`. Replace your cached configuration, fix the named entries, and
resend the whole batch.

After the server applies the batch, remove the working directory and the review
file.

## Notes

- You run the comparison on subagents to save the expensive model's budget. You
  only launch the run, cluster the findings, and confirm the batch.
- Prefer fewer, cleaner memories over many near-duplicates.

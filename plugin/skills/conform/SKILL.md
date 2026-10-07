---
name: conform
description: >-
  Bring nonconforming memories back into line with the engram configuration. Use
  on demand after a configuration change leaves memories that satisfy no
  configuration. Fans the fixes out to cheap subagents, then stages one batch for
  your review before any write.
---

# conform

Bring the nonconforming memories back into line with the configuration. A user
runs this skill on demand. You fan the per-memory fixes out to cheap subagents,
take a second pass over the ones they decline, and stage one batch. The user
approves the batch before any write.

## Transport

Every call is `ssh engram <METHOD> <PATH>`, with any request body on stdin. A GET
carries no body, so close stdin with `< /dev/null`.

```bash
ssh engram GET /recalls < /dev/null
ssh engram GET /config < /dev/null
ssh engram GET /memories/nonconforming < /dev/null
cat batch.json | ssh engram POST /memories/batch
```

## Step 1: read the configuration, the vocabulary, and the failing memories

Run `ssh engram GET /config < /dev/null`. The body carries `configurations`, the
acceptable category-to-cardinality maps, and `categories`, a description and
examples per category. A memory is valid once its tags satisfy one configuration.

Run `ssh engram GET /recalls < /dev/null` for the label vocabulary already in use.
The body is `{"recalls": [ ... ]}`, and each row is `[category, label,
count, lifetime, recent]`. Read the category and label of every row. Reuse an
existing label over a near-duplicate.

Run `ssh engram GET /memories/nonconforming < /dev/null`. The response is an
NDJSON stream. The first line is a header object. Every line after it is one
memory that satisfies no configuration. Each memory carries `id`, `content`, and
`src`. Non-empty `tags` appear too. Collect all of them.

## Step 2: chunk the failing memories

Split the nonconforming memories into fixed-size chunks. A chunk of about 30
memories works well. Create a working directory for the run at
`~/.claude/projects/<project-slug>/conform/`. The project slug is the one Claude
Code already uses for the project. Put a `chunks` directory and a `findings`
directory inside it. A chunk file lives under `chunks`, named `chunk-<n>.json`.
Each chunk file is self-contained. It carries its memories, the configuration,
and the vocabulary the subagent needs:

```json
{"configurations": [...], "categories": {...}, "vocabulary": [["domain","clojure"]],
 "memories": [{"id":"...","content":"...","tags":[["tech","datalevin"]]}]}
```

The `vocabulary` is the category:label pairs already in use. The subagent reuses
one of these over a near-duplicate.

Do not write every chunk file up front. You write and delete the chunk files in
waves in Step 3.

## Step 3: propose fixes with subagents

Spawn one subagent per chunk, with the model set to haiku. Run about ten at a
time. Give each subagent its chunk file path and its findings file path.

For each memory, the subagent proposes the smallest tag change that makes the
memory satisfy one configuration. The subagent judges the change from the
memory's content and its existing tags. The subagent reuses a label from the
vocabulary over a near-duplicate. The subagent keeps only the fixes it is
confident about.
The subagent declines the rest. The subagent writes its findings file under
`findings`, named `chunk-<n>.json`:

```json
{"fixes": [{"id":"<uuid>","tags":[["domain","databases"],["tech","datalevin"]]}],
 "declined": ["<uuid>"]}
```

Each fix lists the memory's full corrected tag set, because a batch update
replaces the whole set. The subagent calls no server route. The subagent only
proposes and records.

Handle the chunks in waves. For each wave, write its chunk files, spawn the
subagents, and let them finish. Fold the fixes and the declined ids into memory.
Then delete that wave's chunk files and findings files. At most one wave of files
sits on disk at a time.

## Step 4: take a second pass over the declined memories

Collect the declined ids from every wave. For each declined memory, attempt the
smallest conforming tag change yourself, from its content and tags. Reuse a label
from the vocabulary here too. Where you stay unsure, ask the user. Group the uncertain memories into one question set
rather than one question per memory. Add each resolved memory to the batch. Leave
a memory out only after the user agrees to skip it.

## Step 5: stage the batch for review

Write the whole plan to a review file at
`~/.claude/projects/<project-slug>/conform/batch.json`. Use the batch body shape
with one `update` group:

```json
{"update": [ {"id":"<uuid>","tags":[["domain","databases"],["tech","datalevin"]]} ]}
```

Each entry carries the `id` and the full corrected tag set, and nothing else. The
content and the related links stay untouched. Show the file. Send nothing to the
server yet. Wait for the user to approve.

## Step 6: apply after approval

After the user approves, send the review file as the batch body. `POST
/memories/batch` with the file on stdin. The file is already in the batch shape,
so send it with no reformatting.

Read the result. On `HTTP 200` the body is `{"ids":[...],"applied":n}`, and the
run is done. If the batch fails, the body is `{"errors":[...]}` with `HTTP 422`.
Nothing was written, because the batch is atomic. A tag error carries
`configurations`. Replace your cached configuration, fix the named entries, and
resend the whole batch. After the server applies the batch, remove the working
directory and the review file.

## Notes

- You run the per-memory judgment on subagents to save the expensive model's
  budget. You take the second pass and confirm the batch.
- The skill names no fixed category. It reads the live configuration each run, so
  it survives a configuration change.

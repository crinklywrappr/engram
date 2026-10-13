---
name: decompress
description: >-
  Close out a thought-process at the end of a task. Promote its worthwhile
  short-term notes to long-term facts, archive the whole bucket to disk, then
  wipe it. Use to clear a task's short-term buffer without losing anything.
---

# decompress

Close out one thought-process at the end of a task. Promote its worthwhile notes
to long-term facts, archive the whole bucket, then wipe it. The run has no
approval gate. The archive loses nothing.

## Transport

Every call is `ssh engram <METHOD> <PATH>`, with any request body on stdin. A GET
carries no body, so close stdin with `< /dev/null`.

```bash
ssh engram GET /recalls < /dev/null
ssh engram GET /config  < /dev/null
echo '{}' | ssh engram POST '/memories/short-term/search?thought_process=<name>'
cat batch.json | ssh engram POST /memories/batch
ssh engram DELETE '/memories/short-term?thought_process=<name>' < /dev/null
```

## Step 1: find the thought-process

Find the target thought-process from the conversation. It is the bucket you
staged notes in during this task. When the conversation does not make the
target clear, ask the user to name it. When you need the names, run `ssh engram
POST /memories/short-term/search < /dev/null` to list the buckets.

## Step 2: read the notes

Read the bucket's notes. Call `POST /memories/short-term/search?thought_process=<name>`
with an empty body. The response is an NDJSON stream. The first line is a header
object. Every line after it is one note with `id`, `thought-process`, `text`,
`created-at`, and `updated-at`. Read the whole stream one time.

## Step 3: distill the worthwhile notes

Judge each note. Distill the worthwhile ones into standalone atomic facts. Follow
the `migrate` discipline:

- One fact states one thing. Split a list into one fact per item.
- Assign `category:label` pairs that satisfy one configuration from `GET /config`.
- Reuse a label from `GET /recalls` over a near-duplicate.
- Set a `src` by topic.
- When the notes form one source, use the thought-process name as the `src`.
- Link a fact to another by its `src` through `related`.
- Prefer fewer, cleaner facts over many noisy ones.

Drop a note that states nothing worth keeping.

## Step 4: archive the whole bucket

Archive every note before the wipe, promoted or not. Pick the archive root. For a
project that has a `.claude` directory, the root is the project-local
`.claude/memory-archive`. The root is `~/.claude/memory-archive` otherwise. Write
the bucket under `<root>/short-term/<thought-process>/`.

Write one markdown file per note, named by a kebab slug of the note. Mirror the
`migrate` source format. Each file carries frontmatter and the note text as the
body:

```markdown
---
name: <note-slug>
description: <one-line summary>
metadata:
  type: short-term
  created-at: <created-at>
  updated-at: <updated-at>
  promoted: true
  promoted-src: <src>
---

<the note text>
```

A promoted note carries `promoted: true` and the `promoted-src` it became. A
dropped note carries `promoted: false` and no `promoted-src`. Write a `MEMORY.md`
index that lists every file, one line each. Make sure that the whole directory is
on disk before the next step.

## Step 5: promote the facts

Send the promoted facts as one batch. Write them to a file in the batch shape,
`{"create": [ fact, ... ]}`, then `POST /memories/batch` with the file on stdin.
On `HTTP 200` the body is `{"ids":[...],"applied":n}`, and the promotion is done.
On `HTTP 422` the body is `{"errors":[...]}`, and nothing was written, because the
batch is atomic. A tag error carries `configurations`. Replace your cached
configuration, fix the named facts, and resend the whole batch.

## Step 6: wipe the thought-process

After the batch returns `HTTP 200`, wipe the bucket. Call `DELETE
/memories/short-term?thought_process=<name>`. The body is `{"deleted": n}`. A
failed promotion stops the run before this step, so no note is lost.

## Notes

- The archive is the plain-text backup. It holds the whole bucket, so the wipe
  loses nothing.
- This skill runs at the end of a task.
- A short-term note never joins a recall, so a promoted fact is the only trace
  that reaches a later session through engram.

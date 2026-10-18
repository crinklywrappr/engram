---
name: migrate
description: >-
  Migrate a project's file-based memory into engram. Use when moving from the old
  files (a MEMORY.md index, per-fact markdown, and workstream notes) to the engram
  server. It previews long-term facts, short-term notes, and index entries, then
  applies them after you approve.
---

# migrate

Turn a project's file-based memory into engram. Work in two phases. First build a
preview, then apply it after the user approves. The import carries three things:
the long-term facts, the short-term notes, and the index entries that tie them
together.

## Inputs

- The project's memory files: a `MEMORY.md` index and per-fact markdown files.
  Each file has frontmatter (`name`, `description`, `metadata.type`). These hold
  the durable, long-term material.
- The project's workstream artifacts: a plan file, or a `.scratch/<feature-slug>/`
  grouping of tickets. These hold the ephemeral, short-term material.
- The engram configuration: run `ssh engram GET /config < /dev/null` first to learn
  the closed set of categories and their cardinalities.
- The labels already in use: run `ssh engram GET /recalls < /dev/null` next. The body
  is `{"recalls": [ ... ]}`, and each entry is a positional row
  `[category, label, count, lifetime, recent]`. Read the category and the label of
  every row to learn the vocabulary your memories already carry. Reuse these
  labels, so you do not add a near-duplicate of a label that already exists.

The cardinality says how many labels of a category a memory carries:

- `1` exactly one
- `?` zero or one
- `*` zero or more
- `+` one or more

A category can instead be a map, `{"cardinality": "?", "one-of": ["global",
"project"]}`. The `one-of` list closes that category's labels to its tokens. Pick
a label from the list for that category.

## What becomes a fact, and what becomes a note

Sort each source into a long-term fact or a short-term note. These are
rules-of-thumb. You resolve them at the preview, so the user corrects any call:

- A short-term note belongs to a thought-process. A thought-process is a bounded
  workstream with a beginning and an end. It maps to a plan, a ticket, a
  milestone, or a feature.
- Knowledge that survives into a project's maintenance stage is not ephemeral, so
  it is a long-term fact.
- Feedback about the user is a long-term fact.

## Phase 1: build the preview (dry run)

Phase 1 builds three preview files and writes nothing to the server. Put them
under `~/.claude/projects/<project-slug>/`. The project slug is the one Claude
Code already uses. Each file carries only a `create` group, which is a valid batch
body.

### The memories file

Distill each memory file into standalone atomic facts:

- One fact states one thing. Split any list into one fact per item.
- Fold the file's preamble and any Why into each fact, so it stands alone.
- Set `src` to the source file name, kebab-case, without the extension. Facts from
  one file share that src.
- Turn each `[[link]]` into a `related` entry naming the target file's src.
- Assign `category:label` pairs from the closed category set. Labels are lowercase
  kebab-case. Reuse labels. Do not invent near-duplicates.
- Make sure that each fact's categories satisfy one configuration from `GET /config`.
- When the configuration has a category that names a fact's project, tag a
  project-specific fact with the project slug.

Write the facts to `<project>-memories.json` in the `POST /memories/batch` shape:

```json
{"create": [ {"content":"one fact stated plainly",
              "src":"source-file-name",
              "tags":[["domain","clojure"]],
              "related":["another-src"]} ]}
```

`content` is the one fact. `src` is the source file name. `tags` is a list of
`[category, label]` pairs. `related` is a list of other `src` values, and it is
optional.

### The short-term file

Form a short-term note from each source you classified as short-term:

- The bucket is the thought-process. Name it in kebab-case from the workstream
  slug, for example the feature directory name.
- The note text is a coherent chunk of the source. One source yields one or more
  notes.
- A note carries no tag, no src, and no related.

Write the notes to `<project>-short-term.json` in the
`POST /memories/short-term/batch` shape:

```json
{"create": [ {"thought-process":"engram-effectiveness",
              "text":"a staged note in free text"} ]}
```

### The index file

The index is structured by project and by thought-process. The `MEMORY.md` titles
and the hooks are signal for the titles and the hooks you write. An entry carries a
kebab-case title and a free-text hook. Two kinds of entry come out:

- One entry per project. Its backbone recalls the project's own facts. The
  configuration can carry a category that names a fact's project. When it
  exists, the backbone is a required `by-tags` over the single pair
  `[<project-category>,"<slug>"]`. Without it, the backbone is a required `by-src`
  over the project's own source names, not the cross-project ones. The title and
  the hook name the project.
- One entry per thought-process in the project. Create one for each bucket in the
  short-term file. It carries a required `thought-process` request, so a later
  decompress of the bucket reaps the entry. It also carries the project backbone,
  not required. The backbone repeats across these entries, which is fine.

When the configuration carries the idea of cross-project reach, a project entry
also references the user's cross-project memories. The reach category is the one
whose values split a cross-project fact from a project-specific one. When it exists,
widen the project backbone to an `or` of the project pair and the cross-project pair.
When the project backbone is a `by-src` instead, add a second, non-required `by-tags`
over the cross-project pair. Either way, a session in this project also sees the
cross-project facts. There is no separate cross-project entry. The admin names
these categories, so do not assume the names `project`, `scope`, or `global`. Read
the category descriptions in the configuration.

Write the entries to `<project>-index.json` in the `POST /index/batch` shape. This
example uses the sample configuration's category names. Read the configuration for the admin's
actual names:

```json
{"create": [ {"title":"engram",
              "hook":"the engram project, plus your cross-project habits",
              "requests":[{"kind":"by-tags",
                           "payload":{"or":[["project","engram"],["scope","global"]]},
                           "required":true}]} ]}
```

A request names its kind. A `by-tags` request carries a match expression. A
`by-src` request carries a list of source names. A `thought-process` request
carries a bucket name. Each request carries a `required` flag, default false. A
`thought-process` request names a bucket by the same name the short-term file
uses.

### Show the preview

Show all three files. Do not POST anything yet. The three files cross-reference by
`src` and by thought-process name, not by any id.

## Phase 2: apply (after approval)

Once the user approves, post the three batches in order. The facts and the buckets
must exist before the index. Then a required request finds its memory the moment its entry is created.

1. Send the memories file. `POST /memories/batch` with the file on stdin.
2. Send the short-term file. `POST /memories/short-term/batch` with the file on stdin.
3. Send the index file. `POST /index/batch` with the file on stdin.

Read each result. On `HTTP 200` the body is `{"ids":[...],"applied":n}`, and that
batch is done. On `HTTP 422` the body is `{"errors":[...]}`, one entry per bad
operation, each with its group `op` and its index `i`. Nothing in that batch was
written, because the batch is atomic. Only a memories-batch tag error carries `configurations`, which you use to refresh
the cached configuration. When a batch returns 422, surface the error and stop. Do not post the next batch. Fix the
named operations and resend.

After all three batches apply, move the source files to
`~/.claude/memory-archive/<project>/`. Do not delete them. The archive is the
plain-text backup.

## Notes

- Migrate only in a session that knows the project, so the facts are accurate.
- Prefer fewer, cleaner facts over many noisy ones.
- Prefer fewer, richer index entries over one entry per `MEMORY.md` line.

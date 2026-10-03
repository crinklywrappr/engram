---
name: migrate
description: >-
  Migrate a project's markdown memory files into engram as atomic facts. Use
  when moving from the old file-based memory (a MEMORY.md index plus per-fact
  markdown files) to the engram server. Runs as a dry-run preview, then a bulk
  apply after you approve.
---

# migrate

Turn a project's markdown memories into engram atomic facts. Work in two phases:
build a preview, then apply it after the user approves.

## Inputs

- A project memory directory with a `MEMORY.md` index and per-fact markdown
  files. Each file has frontmatter (`name`, `description`, `metadata.type`).
- The engram configuration: run `ssh engram GET /config < /dev/null` first to learn
  the closed set of categories and their cardinalities.
- The labels already in use: run `ssh engram GET /stats < /dev/null` next. The body
  is `{"stats": {"recalls": [ ... ]}}`, and each entry is a positional row
  `[category, label, count, lifetime, recent]`. Read the category and label of
  every row to learn the vocabulary your memories already carry. Reuse these
  labels during the migration, so you do not add a near-duplicate of a label that
  already exists.

The cardinality says how many labels of a category a memory carries:

- `1` exactly one
- `?` zero or one
- `*` zero or more
- `+` one or more

## Phase 1: distill and preview (dry run)

For each markdown file, distill its content into standalone atomic facts:

- One fact states one thing. Split any list into one fact per item.
- Fold the file's preamble and any "Why" into each fact so it stands alone.
- Set `src` to the source file name (kebab-case, without the extension). Facts
  from one file share that src.
- Turn each `[[link]]` into a `related` entry naming the target file's src.
- Assign `category:label` pairs from the closed category set. Labels are
  lowercase kebab-case. Reuse labels across facts. Do not invent near-duplicates.
- Make sure each fact's categories satisfy one configuration from `GET /config`.

Write the full proposed fact set to a preview file in JSON. Use the batch body
shape: `{"create": [ fact, fact, ... ]}`, one entry per fact. Put the file at
`~/.claude/projects/<project-slug>/<project>-preview.json`. The project slug is
the one Claude Code already uses for the project. Each `fact` has the same shape
as a single `POST /memories` body:

```json
{"content": "one fact stated plainly",
 "src": "source-file-name",
 "tags": [["domain","clojure"],["tech","datalevin"]],
 "related": ["another-src"]}
```

`content` is the one fact. `src` is the source file name in kebab-case without
the extension. `tags` is a list of `[category, label]` pairs. `related` is a list
of other `src` values this fact links to, and it is optional. Show the file. Do
not POST anything yet.

## Phase 2: apply (after approval)

Once the user approves the preview:

1. Send the preview file as the batch body. `POST /memories/batch` with the
   preview file on stdin. The preview is already in the batch shape, so send it
   with no reformatting. Do not send a call per fact.
2. Read the result. On `HTTP 200` the body is `{"ids":[...],"applied":n}`, and the
   migration is done. On `HTTP 422` the body is `{"errors":[...]}`, one entry per
   bad fact, each with its index `i` in the create list. Nothing was written,
   because the batch is atomic. A tag error carries `configurations`. Replace your
   cached configuration, fix the named facts, and resend the whole batch.
3. Move the source markdown files to `~/.claude/memory-archive/<project>/`. Do
   not delete them. The archive is the plain-text backup.

## Notes

- Migrate only in a session that knows the project, so the facts are accurate.
- Prefer fewer, cleaner facts over many noisy ones.

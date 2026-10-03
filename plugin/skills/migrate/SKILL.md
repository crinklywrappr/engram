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
- The engram configuration: run `ssh engram GET /config` first to learn the closed set
  of categories and their cardinalities.

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

Write the full proposed fact set to a preview file (for example
`.scratch/migrate/<project>-preview.edn`), one fact per entry, and show it. Do
not POST anything yet.

## Phase 2: apply (after approval)

Once the user approves the preview:

1. Send every fact in one batch. `POST /memories/batch` with a body
   `{"create": [ fact, fact, ... ]}`, one entry per distilled fact. Do not send a
   call per fact.
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

# 25a: `migrate` populates the memory index

**What to build:** The `migrate` skill writes memory index entries as it imports a project. An entry is a project entry, a thought-process entry, or a cross-project theme, not a copy of one `MEMORY.md` line. The import produces three things: long-term facts, short-term notes, and the index entries that tie them together. The index orients a later recall of the imported knowledge. See `CONTEXT.md` for the "Memory index", "Index entry", and "Lapse" terms.

## What becomes long-term, and what becomes short-term

migrate reads two kinds of source. One is the project's memory files, the `MEMORY.md` and its per-fact files. The other is the project's workstream artifacts, a plan or a `.scratch/<feature-slug>/`. It sorts each into a long-term fact or a short-term note. These are rules-of-thumb, resolved at the preview, not hard-coded logic:

- A short-term note belongs to a thought-process. A thought-process is a bounded workstream with a beginning and an end. It maps to a plan, a ticket, a milestone, or a feature, a `.scratch/<feature-slug>/` grouping like `engram-effectiveness`.
- Knowledge that survives into a project's maintenance stage is not ephemeral, so it is a long-term fact.
- Feedback about the user is a long-term fact.

migrate judges tags and reach the way the current skill already does. When the config has a `project` category, a project-specific fact carries `["project", <project-slug>]`.

## Forming a short-term note

migrate forms a short-term note from the content it classified as short-term. The bucket is the thought-process, named in kebab-case from the workstream slug, for example the feature directory name. The note text is a coherent chunk of the source, so one source yields one or more notes. A note carries no tag, no src, and no related, because a short-term note holds none. Reading the workstream artifacts grows migrate's input, so the `migrate` skill's Inputs section grows to name them.

## The entry kinds

The index is structured by project and by thought-process. It is not a free-form cluster, and there is no separate cross-project entry. The admin names the categories. So migrate reads them from the config by sense, not by the fixed names `project`, `scope`, or `global`. The `MEMORY.md` titles and hooks are signal for the titles and hooks migrate writes. An entry carries a kebab-case title and a free-text hook.

Two kinds of entry come out:

- One project entry per project. Its backbone recalls the project's own facts. When the config carries a category that names a fact's project, the backbone is a required `by-tags` over the single pair `[<project-category>,"<slug>"]`. Without one, the backbone is a required `by-src` over the project's own source names, not the cross-project ones. The title and the hook name the project.
- One entry per thought-process in the project. migrate creates one for each bucket it staged in the short-term batch. It carries a required `thought-process` request, so a later decompress of that bucket reaps the entry. It also carries the project backbone, not required. The backbone repeats across these entries, which is fine.

When the config carries that idea, a project entry also references the user's cross-project memories. The reach category is the one whose values split a cross-project fact from a project-specific one. When it exists, migrate widens the project backbone to an `or` of the project pair and the cross-project pair. When the backbone is a `by-src` instead, migrate adds a second, non-required `by-tags` over the cross-project pair. So the project entry surfaces the user's global memories from inside the project, and no standalone cross-project entry is needed.

The index never uses a `by-ids` request. A migrated source is named by `src`, so `by-src` pins it (ticket 25b).

## The preview: three files

migrate writes three preview files, so the reviewer knows which set each file holds:

- the memories file, in the `POST /memories/batch` shape.
- the short-term file, in the `POST /memories/short-term/batch` shape (25c).
- the index file, in the `POST /index/batch` shape (25d).

Each file carries only a `create` group. The update and delete groups are optional, so a create-only body is valid.

Each index entry's `thought-process` request names a bucket by the same name the short-term file uses. So the three files cross-reference by `src` and by thought-process name, not by any id. migrate shows all three. It writes nothing yet.

## The apply: three batches in order

After the user approves, migrate posts three batches in dependency order:

1. the memories batch.
2. the short-term batch (25c).
3. the index batch (25d).

The facts and the buckets exist before the index, so every required probe resolves at the moment its entry is created. Any of the three batches can reject with 422. When a batch rejects, migrate surfaces the error and stops before the next batch, the way the current skill surfaces the memory batch rejection. After all three batches apply, migrate archives the source files, the way the current skill does.

**Blocked by:** 25, 25b, 25c, and 25d. 25 gives the index schema and routes. 25b adds the `by-src` recall route and the index request kind. 25c batch-creates short-term notes. 25d batch-creates index entries. 25b makes a migrated entry final at dry-run, because a `by-src` request names sources known before any write. The apply is three batches in order: memories, then short-term notes (25c), then index entries (25d).

**Status:** done

- [x] migrate sorts each source into a long-term fact or a short-term note, reviewable at the preview.
- [x] migrate structures the index by project and by thought-process, not one entry per `MEMORY.md` line.
- [x] One project entry per project, and one entry per thought-process per project, each carrying the project backbone.
- [x] The project `by-src` fallback covers the project's own source names, not the cross-project ones.
- [x] migrate reads the category names from the config by sense, not the fixed names `project`, `scope`, or `global`.
- [x] When the config carries a cross-project reach, a project entry references the cross-project facts, and there is no separate cross-project entry.
- [x] A project entry scopes the project's facts by a required `by-tags` project backbone. When the config has no project category, the backbone is a required `by-src` over the sources.
- [x] A thought-process entry, one per staged bucket, carries a required `thought-process` request and the non-required project backbone.
- [x] The index uses no `by-ids` request.
- [x] migrate writes three preview files (memories, short-term, index), cross-referenced by `src` and thought-process name, and writes nothing during the dry run.
- [x] After approval, migrate posts the memories batch, then the short-term batch, then the index batch, then archives the sources.
- [x] migrate reads the project's workstream artifacts, beyond the memory files, as the short-term source.
- [x] migrate forms each short-term note from a workstream artifact, the text a coherent chunk.
- [x] A note's bucket is named in kebab-case from the workstream slug, and one artifact yields one or more notes.
- [x] Each preview file carries only a create group.
- [x] On apply, a 422 from any batch surfaces and stops migrate before the next batch.

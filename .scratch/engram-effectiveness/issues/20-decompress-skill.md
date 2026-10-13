# 20: The short-term lifecycle (session-start listing and `/decompress`)

**What to build:** The short-term lifecycle wraps the start-to-stop ceremony around short-term memories. It lands as two skill changes. The `engram-recall` skill gains a short-term section for the session start and the staging work. A new `/decompress` skill closes out a thought-process at the end of a task. Ticket 19 already serves the routes these skills call.

## `engram-recall`: session start and staging

The `engram-recall` skill runs at session start. It gains a short-term section that covers three steps.

Start: the skill lists the buckets. It calls `POST /memories/short-term/search` with an empty body and no `thought_process`. The call returns `{"thought-processes": {name: count}}`. The skill shows the buckets. The agent resumes the current task's bucket. The agent adopts another session's bucket by its name.

Work: the skill states the staging rules. One short-term memory holds one loose thought. The agent names a thought-process in lowercase kebab-case. A good name describes the project and the task. The agent stages notes through the create, update, delete, and search routes from ticket 19.

Stop: the skill names `/decompress` as the close-out step.

## `/decompress`: close out a thought-process

The `/decompress` skill closes out one thought-process. It runs with no approval gate. The archive loses nothing.

1. The skill finds the target thought-process from the conversation context. When the context is not enough, the skill asks the user to name it.
2. The skill reads the bucket's notes. It calls `POST /memories/short-term/search?thought_process=xxx` with an empty body. The response is an NDJSON stream of the notes.
3. The skill judges each note. It distills the worthwhile notes into atomic long-term facts. Each fact carries conforming tags, a `src`, and any `related` links. The skill assigns a `src` by topic. When the notes form one source, it uses the thought-process name as the `src`. It reuses the labels from `GET /recalls`. It follows the `migrate` distill discipline, so it prefers fewer, cleaner facts.
4. The skill archives the whole bucket before the wipe. When the project has a `.claude/` directory, the skill writes to `.claude/memory-archive/short-term/<thought-process>/`. The skill writes under `~/.claude/memory-archive/short-term/<thought-process>/` otherwise. It writes one `<note-slug>.md` file per note. Each file carries `name`, `description`, and `metadata.type` frontmatter, the created and updated times, and the note text as the body. A promoted note carries a `promoted: true` field and a `promoted-src` field. A `MEMORY.md` index lists the notes. This mirrors the `migrate` source format.
5. The skill promotes the facts through `POST /memories/batch`. On an `HTTP 200` the promotion is done. On an `HTTP 422` the skill fixes the named facts and resends the whole batch.
6. After the promotion returns `HTTP 200`, the skill wipes the thought-process. It calls `DELETE /memories/short-term?thought_process=xxx`. A failed promotion aborts the run before the wipe, so no note is lost.

**Blocked by:** 12 (Document the session-start recall backbone) and 19 (Store and serve short-term memories under a thought-process).

**Status:** ready-for-agent

- [ ] `engram-recall` lists the short-term buckets at session start.
- [ ] The listing shows each thought-process with its note count.
- [ ] `engram-recall` states the staging rules for a short-term note during work.
- [ ] `engram-recall` names `/decompress` as the end-of-task step.
- [ ] The agent resumes the current task's bucket, or adopts another session's bucket by its name.
- [ ] `/decompress` finds the target thought-process from context. When the context is not enough, it asks the user.
- [ ] `/decompress` reads the bucket's notes through the short-term search route.
- [ ] `/decompress` distills the worthwhile notes into atomic long-term facts with conforming tags, a `src`, and any `related` links.
- [ ] `/decompress` archives the whole bucket as one frontmatter file per note plus a `MEMORY.md` index, before the wipe.
- [ ] A promoted note's archive file records that it was promoted and names its `src`.
- [ ] `/decompress` promotes the facts through `POST /memories/batch`.
- [ ] `/decompress` wipes the thought-process only after the promotion returns `HTTP 200`.
- [ ] `/decompress` runs with no approval gate.

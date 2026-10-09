# 17: Add the `/review` skill

**What to build:** The `/review` skill revalidates the stale memories the caller leans on. A stale memory is one overdue for reconfirmation, measured from its last-confirmed stamp. The skill walks these memories in confirm-priority order, highest first, and presents each for a decision. The ticket also adds one server behavior the skill needs.

The server change comes first. An update that carries only an `id` confirms that memory. It stamps last-confirmed to now and leaves updated-at unchanged. A `PUT /memories/:id` with an empty map body confirms the same way. An update that carries content, tags, or related stays a correction and stamps updated-at and last-confirmed together. The `/memories/confirm` route stays as the semantic way to confirm a memory. The empty update and the empty `PUT` are incidental conveniences the skill leans on, so the whole page lands in one `/batch` call.

The skill reads `GET /memories/stale` one time and walks that single snapshot in confirm-priority order. It never re-queries between pages, because a confirmed or corrected memory will not resurface on the next run. The skill works in pages of four memories. For each page it issues one structured question prompt with one option menu per memory. The arrow keys move between the menus and one Submit ends the page.

Each memory shows its content and its freshness. When the session gives real evidence the fact drifted, the memory also shows a single proposed change. The explicit options are keep, delete, and skip. When a proposed change exists, it joins them as a fourth option. The prompt adds an "Other" choice on its own, which carries the free-text path. A skip, or a dismissed prompt, leaves that memory stale.

Claude grounds a proposed change only in what it can observe this session: the conversation, the project and its code, and the memories already recalled. When Claude finds no evidence the fact drifted, it offers no change and the menu degrades to keep, delete, skip, and free-text. The proposed change is always a single in-place correction to the one memory, never a restructure.

Keep maps to a bare confirm. Delete removes an obsolete fact. A correction replaces content by default. It keeps the current tags and related unchanged. When the user names tags or related, the correction changes those too. Free-text is the open path. Through it the user can drive a split, a delete plus new creates under one src, or a consolidation, deletes plus a create. The only rules are the `/batch` schema and the glossary rule that every result is one atomic fact. Claude defends that ground and otherwise accommodates. Restructuring defaults to the shown memory. When free-text names a memory outside the snapshot, Claude reads its current state with `GET /memories/<id>` to build a correct replacement.

On a delete Claude does not repoint a dangling related edge, because a missing src is harmless on recall. On a consolidation Claude repoints only the related of the memories it edits in that same batch.

After the fourth memory of a page is answered or skipped, Claude assembles the whole page into one `POST /memories/batch` call. Keeps go in as bare `id` updates, corrections as field-bearing updates, and deletes in the delete group. A split or a consolidation contributes its creates and deletes. Then the skill advances to the next four. When the user stops or the stale list is exhausted, the run ends. The skill sets no priority floor and asks for no separate blanket approval. Each menu choice is itself the decision.

**Blocked by:** 16 (Serve stale memories ordered by confirm-priority at `/memories/stale`) and 14 (Add the per-memory freshness flag and the confirm route). Both are done.

**Status:** done

- [x] A `/batch` update that carries only an `id` confirms the memory, stamping last-confirmed and leaving updated-at unchanged.
- [x] A `PUT /memories/:id` with an empty map body confirms the same way.
- [x] A `/batch` update that carries content, tags, or related stamps updated-at and last-confirmed together.
- [x] Tests cover the bare confirm path and the field correction path for both `/batch` and `PUT`.
- [x] The skill reads `GET /memories/stale` once and walks that snapshot in confirm-priority order without re-querying between pages.
- [x] The skill presents the memories in pages of four, one structured prompt per page, one menu per memory, ending in one Submit.
- [x] When session evidence shows the fact drifted, the memory shows a single proposed change beside its content and freshness.
- [x] The explicit menu options are the proposed change (when one exists), keep, delete, and skip, with free-text on the automatic "Other" choice.
- [x] A skip or a dismissed prompt leaves the memory stale.
- [x] Keep sends a bare confirm, a correction replaces content by default and carries tags and related unchanged, and delete removes the memory.
- [x] Free-text can drive a split or a consolidation. The `/batch` schema and the atomic-fact rule are the only limits. Claude reads any outside memory with `GET /memories/<id>`.
- [x] A consolidation repoints only the related of the memories edited in the same batch, and a delete repoints nothing.
- [x] Each page of four flushes as one `POST /memories/batch` call before the skill advances.
- [x] When the user stops or the stale list is exhausted, the run ends. The skill sets no priority floor and asks for no separate blanket approval.

# 01: Fix the clarity bugs in engram-recall

**What to build:** engram-recall tells a session how to select memories for a recall and how to call each route. Two statements in it no longer match the real routes and the skill's own convention. First, the recall section says a recall selects memories "two ways". There are three paths: by category:label pairs, by ids from a search, and by source names. Second, the `GET /recalls` example in the recall-by-pairs step omits the `< /dev/null` that every other GET example carries. Fix both, so the text matches the routes and the transport convention.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] The recall overview names all three selection paths: by category:label pairs, by ids from a search, and by source names.
- [x] Every GET example in engram-recall closes stdin with `< /dev/null`.
- [x] Every edited sentence passes the blocking lint hook.

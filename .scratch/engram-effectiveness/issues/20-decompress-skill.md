# 20: Add the `/decompress` skill

**What to build:** The `/decompress` skill closes out a thought-process. The skill reads the thought-process's notes through `GET /memories/short-term`. The skill judges each note. The skill distills the worthwhile notes into atomic long-term facts. Each fact carries tags that satisfy a configuration, a src, and any related links. The skill creates the facts through the batch create path.

The skill archives the discarded notes before the wipe. The archive is the project-local `.claude/memory-archive` for a project that has one. The archive is `~/.claude/memory-archive` otherwise. The skill then wipes the thought-process through the bucket delete. There is no approval gate. The archive loses nothing.

**Blocked by:** 19 (Store and serve short-term memories under a thought-process).

**Status:** ready-for-agent

- [ ] The skill reads the thought-process's notes through `GET /memories/short-term`.
- [ ] The skill distills the worthwhile notes into atomic long-term facts.
- [ ] Each promoted fact carries conforming tags, a src, and any related links.
- [ ] The skill creates the facts through the batch create path.
- [ ] The skill archives the discarded notes to project-local `.claude/memory-archive`, or `~/.claude/memory-archive` without one.
- [ ] The skill wipes the thought-process through the bucket delete.
- [ ] The skill runs with no approval gate.

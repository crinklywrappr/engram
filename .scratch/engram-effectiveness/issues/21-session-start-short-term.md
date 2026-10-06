# 21: Surface short-term buckets at session start

**What to build:** The `engram-recall` skill lists the short-term thought-processes at session start. The skill calls `GET /memories/short-term` with no thought-process. The call returns the caller's thought-processes, each with a note count. The skill shows the thought-processes to the agent. The agent resumes the current task's buffer from the list. The agent adopts another session's buffer by its name. The skill names `/decompress` as the end-of-task step. The listing adds one small call to session start.

**Blocked by:** 12 (Document the session-start recall backbone) and 19 (Store and serve short-term memories under a thought-process).

**Status:** ready-for-agent

- [ ] The skill lists the short-term thought-processes at session start through `GET /memories/short-term`.
- [ ] The listing shows each thought-process with its note count.
- [ ] The agent resumes the current task's buffer or adopts another session's by name.
- [ ] The skill names `/decompress` as the end-of-task step.

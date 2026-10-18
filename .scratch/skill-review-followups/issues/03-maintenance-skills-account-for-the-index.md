# 03: Maintenance skills account for the memory index

**What to build:** The memory index pins facts by source name through a `by-src` request. When consolidate merges duplicates, it changes and drops source names, so a surviving fact can lose the index entry that pins it. consolidate must read the index. When it drops or renames a source named by an index `by-src` request, it repoints or flags that request before the write. review deletes memories too. A deleted required-probe target reaps its index entry by design, so review gains a short note that records this behavior. After this ticket, a merge no longer orphans an index pointer by accident.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] consolidate reads `GET /index` as part of its run.
- [x] When consolidate drops or renames a source named by an index `by-src` request, it repoints or flags that request before the write.
- [x] review carries a short note that deleting a required-probe target reaps its index entry by design.
- [x] Every edited sentence passes the blocking lint hook.

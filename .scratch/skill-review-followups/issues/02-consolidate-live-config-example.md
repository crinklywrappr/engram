# 02: consolidate reads the live config for its tag-reconcile example

**What to build:** consolidate merges duplicate memories and reconciles their tags. Its tag-reconcile guidance names `scope:global` and `scope:project` as an example of a broader label superseding a narrower one. An admin configuration can omit the `scope` category entirely, so that example misleads a reader whose store uses other category names. Add the note that `conform` already carries: the skill names no fixed category and reads the live configuration each run. Keep any category name in the guidance as an illustration drawn from the sample configuration, not an assumption that the category exists.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] The consolidate tag-reconcile guidance states that the skill reads the live configuration and names no fixed category.
- [x] Any category name in consolidate reads as an illustration from the sample configuration, not an assumption that the category exists.
- [x] Every edited sentence passes the blocking lint hook.

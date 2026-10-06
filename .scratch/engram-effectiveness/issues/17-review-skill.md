# 17: Add the `/review` skill

**What to build:** The `/review` skill revalidates the stale memories the caller leans on. The skill reads the `/stale` route for the stale memories in confirm-priority order. The skill works from the top of the order down, where the hot-and-overdue memories sit. The skill presents each memory for a keep-or-correct decision. The skill confirms the kept memories in one batch through the confirm route from ticket 14. The skill corrects the rest with an update. The skill runs from the top down until the user stops it. The skill sets no priority floor. A confirmed memory moves its freshness back toward fresh. The next review passes over it.

**Blocked by:** 16 (Serve stale memories ordered by confirm-priority at `/stale`) and 14 (Add the per-memory freshness flag and the confirm route).

**Status:** ready-for-agent

- [ ] The skill reads the `/stale` route for stale memories in confirm-priority order.
- [ ] The skill works from the top of the order down.
- [ ] The skill presents each memory for a keep-or-correct decision.
- [ ] The skill confirms the kept memories in one batch through the confirm route.
- [ ] The skill corrects the rest with an update.
- [ ] The skill runs top-down until the user stops it, with no priority floor.

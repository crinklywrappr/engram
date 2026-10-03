# 05: Consolidate the tag rules and the 409 rejection

**What to build:** The tag rule lives in one place and the 409 rejection is built by one responder. Today the create route matches even an empty tag set against the configurations. If the memory has tags, the update route matches them against the configurations. The batch path repeats both rules a third time. That create-versus-update asymmetry is a convention spread across three sites that must move together. This gives one predicate for the create rule and one for the update rule, and routes create, update, and the batch planner through them. It also gives one responder that builds the 409 body with the current configurations attached, so the two single-write routes stop repeating that block.

**Blocked by:** 04 (the batch tag rule moves into the planner, so consolidate after the planner exists).

**Status:** resolved (commit 0673cd6)

- [x] One predicate expresses the create tag rule, and one expresses the update tag rule, each named for which it is.
- [x] The create route, the update route, and the batch planner all route through those two predicates.
- [x] The empty-tags-matched-on-create asymmetry is stated in one place, not restated at each site.
- [x] One responder builds the 409 body with the configurations attached, and both single-write routes use it.
- [x] The API behaviour is unchanged. A create with tags that match no configuration still returns 409 with the configurations, and an update with no tags still skips the tag rule.
- [x] The existing create, update, and batch tests pass without changes to their assertions.

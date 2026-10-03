# 07: Collapse /stats rows to positional tuples

**What to build:** The `/stats` route returns each recall-count row as a positional tuple. A row becomes `[category label lifetime recent]`, in that fixed order. This matches the positional `[category label]` pair the recall body already takes, and it drops four key strings from every row. Update the `/stats` response schema to a vector of tuples under the envelope. Record the column order in the glossary and the recall skill, so the client reads each position correctly.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] The `/stats` envelope holds a vector of `[category label lifetime recent]` tuples.
- [ ] The response schema names the tuple shape, not four separate fields.
- [ ] The glossary and the recall skill state the fixed column order.
- [ ] The full test suite passes.

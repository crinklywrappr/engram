# 02: Comment the :none sentinel in the recall walker

**What to build:** The recall walker carries a code comment for its `:none` sentinel. The pair lookup grounds `:none` for a memory that has no related links. That keyword flows through the walker. The walker strips it before the src lookup. A reader must see why. Add one short comment at the pair lookup, so the later strip reads as deliberate.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] The pair lookup carries a comment naming `:none` as the no-related sentinel.
- [ ] No code behavior changes.

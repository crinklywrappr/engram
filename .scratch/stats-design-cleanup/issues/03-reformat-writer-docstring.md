# 03: Reformat the writer docstring

**What to build:** The `writer` docstring reads as a short header sentence and a bulleted list. The list names the tuning keys and the guarantees the writer holds. The prose follows the simple-english document rules.

**Blocked by:** 02 (the docstring names the accumulator term that 02 settles).

**Status:** ready-for-agent

- [ ] The docstring opens with one header sentence that states what `writer` creates.
- [ ] A bulleted list names the `debounce-ms` and `max-wait-ms` tuning keys and their defaults.
- [ ] A bulleted list names the guarantees: one serial consumer, a debounced flush, daemon threads, and the `:continue` error mode.
- [ ] The prose carries no semicolon, no em-dash, and no sentence over the simple-english word limit.
- [ ] The docstring uses the domain term settled in 02, not `buffer`.

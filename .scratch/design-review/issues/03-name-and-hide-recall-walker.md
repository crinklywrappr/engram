# 03: Name and hide the related-closure walker

**What to build:** The recall engine reads as what it is. The function that walks the transitive related-by-src closure is the real recall algorithm. Today it is public. It is named as a prime variant of the public query function. Its parameters are single letters. A reader has to run the loop by hand to trust it. This makes the walker a private helper of query and names its work-list parameters for what they hold. It also records that the walker returns a lazy sequence whose values are valid only over the open connection's immutable database snapshot.

**Settled parameter names (from a grilling session):** Rename the walker's parameters to reveal their sense. `seen` stays. `unseen` becomes `pending`, and each row destructures as `[eid srcs]`. `related` becomes `frontier`. `xs` becomes `lookup+args`, and each item destructures as `[lookup arg]`. Each tail binding is `more`. The two sets union as `(clojure.set/union frontier srcs)`. The docstring must state that `lookup+args` holds a sequence of `[lookup arg]` pairs. The compound name reads as either a tuple or a sequence. The function keeps its `query'` name and becomes private, so the parameter names carry the clarity. A rename lost to a domain clash. `related` names the memory field. The walker returns the pair matches and the related closure, so a `related`-based name favors one and excludes the other.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit 4daf040)

- [x] The walker is private to the memory namespace.
- [x] The walker keeps its `query'` name, and the settled parameter names carry the clarity.
- [x] The walker's parameters use the settled names: `pending`, `frontier`, and `lookup+args`.
- [x] A note near the public query function records why the lazy result is valid only over the open snapshot.
- [x] The public query behaviour is unchanged, and the existing query and closure tests pass without edits to their assertions.

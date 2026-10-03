# 06: Extract the route table out of `app`

**What to build:** The route table lives in its own `def` or `fn`. `app` wires
only the coercion, the middleware, and the `wrap-*` chain around it. No endpoint
behavior changes. The routes become easier to read and to extend, which helps the
output-validation work in ticket 05.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit a02d618)

- [ ] The route vector is a top-level `def` or `fn`, separate from `app`.
- [ ] `app` composes the router, the swagger-ui handler, the default handler, and
      the `wrap-*` chain over that routes value.
- [ ] `conn` and the config still reach the handlers.
- [ ] No endpoint behavior changes. The full test suite still passes.

# 01: Split the handler namespace into schema and middleware

**What to build:** `engram.handler` becomes three namespaces. The wire schemas move to a new `engram.schema`. The HTTP middleware moves to a new `engram.middleware`. The handlers, the route table, and the `app` entry point stay in `engram.handler`, which requires the two new namespaces. The `app` interface does not change, so `engram.system` and the tests stay untouched and the suite stays green.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `engram.schema` holds every request and response schema, and each schema is public.
- [ ] `engram.middleware` holds `wrap-user`, `wrap-log`, `wrap-request-id`, the exception handlers, and `exception-mw`, each public.
- [ ] The ndjson response helpers stay in `engram.handler` beside the recall handler.
- [ ] `engram.handler` keeps the handlers, the route table, and `app`, and requires `engram.schema` and `engram.middleware`.
- [ ] The `app` signature does not change.
- [ ] The full test suite passes.

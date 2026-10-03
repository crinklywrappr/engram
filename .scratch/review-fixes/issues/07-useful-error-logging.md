# 07: Make the error log useful and log it in the right place

**What to build:** An unhandled error produces one structured log line with
enough detail to diagnose it, emitted at reitit's exception middleware. Every
request carries a correlation id that ties a user's report to the log line.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit a0ec165)

Design (settled by grilling):

- Error logging lives in reitit's exception middleware, not the outer
  `wrap-error`. Build the middleware from `exception/default-handlers` plus an
  `::exception/wrap` wrapper that logs and then delegates to the default handler.
  Coercion errors still return 400. Every other error still returns 500.
- The wrapper emits one Telemere `:error` signal. It carries the throwable (class,
  message, stack), the method, the path, the user, the response status, the
  correlation id, and the request body. Privacy is not a concern right now, so
  the body is logged.
- A per-request correlation id is a short random hex token. An outer middleware
  generates it, attaches it to the request, and sets it as a response header on
  every response. It appears in the per-request log line, in the error line, and
  in the 500 body, so a user can quote it. A header name such as
  `X-Engram-Request-Id` is fine.
- Remove the outer `wrap-error`. reitit's exception middleware is the single error
  seam.

- [ ] Error logging happens in reitit's exception middleware, through an
      `::exception/wrap` wrapper over `default-handlers`. Coercion errors still
      return 400. Other errors still return 500.
- [ ] An unhandled error logs one Telemere `:error` line with the throwable, the
      method, the path, the user, the status, the correlation id, and the request
      body.
- [ ] Every request gets a short-hex correlation id: attached to the request, set
      as a response header on every response, and present in the per-request log
      line.
- [ ] The 500 body includes the correlation id.
- [ ] The outer `wrap-error` is removed, and no error is logged twice.
- [ ] A test triggers a handler error and asserts a 500 that carries the
      correlation id in the body and the header.

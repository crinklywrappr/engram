# 01: Rewrite the test suite by the testing conventions

**What to build:** The engram test suite follows `docs/agents/testing.md`. Today `test/engram/core_test.clj` is one 2707-line namespace. Each test opens a fresh migrated connection and closes it, so CI pays a migration and a full-text re-index per test. The undrained recall-count writers also race that close, which fills the log with stat-flush errors. Decompose it by fixture need, not by concern. The shared-store namespace gets one `:once` fixture that drains the writers before it closes the connection. Lead with integration tests through `app`, and keep per-user isolation so a shared store stays safe.

## What changes

- Split `core_test` by fixture need: a pure namespace with no store, one shared-store namespace, and a fresh-store namespace. Group concerns by section comment inside a namespace, not by a new namespace.
- The shared-store namespace uses one `:once` fixture. It opens the migrated connection and builds the app with its writers. On teardown it drains the writers before it closes the connection.
- Each test picks its own user id, or its own `src` names, so tests share the `:once` store without collision.
- A test that needs a pristine database gets a fresh store, in its own namespace or a scoped fixture. A migration test and a re-index test are the cases.
- Wrap a test that is expected to emit a system error in `taoensso.telemere/with-min-level`, above the error level. The deliberate 500 test is the example. Inject no-op writers into a test that does not assert stats.
- Assert an async recall count with a short `debounce-ms` writer and a watch on its `:last-flush` atom, not with `drain!`. Drain is for a shutdown test only.
- The generative spec stays, and the release still runs it through `clojure -T:build test`.

## Outcome

core_test split into three namespaces by fixture need. `pure-test` holds 15 tests
with no fixture. `store-test` holds 105 tests on one `:once` store, each test under
its own user, and groups concerns by section comment. `fresh-store-test` holds 4
tests, each on a fresh store. A shared `test-support` namespace holds the fixture
and the helpers. The full suite runs 125 tests and 689 assertions, generative
included, with no failures. The log carries one flush-failed line, from the test
that drives that failure on purpose.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] core_test is split by fixture need into a pure, a shared-store, and a fresh-store namespace.
- [x] No assertion from core_test is dropped. The new namespaces together cover what the old one did.
- [x] The shared-store namespace uses one `:once` fixture that drains the writers before it closes the connection.
- [x] No stat-flush error appears in the test log.
- [x] Tests share the `:once` store safely through per-user or per-src isolation.
- [x] A test expected to emit a system error binds the telemere level up, so the log stays clean.
- [x] The full suite passes, generative included, through `clojure -T:build test`.
- [x] Every sentence passes the blocking lint hook.

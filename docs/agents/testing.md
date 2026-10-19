# Testing conventions

How to write and organize the engram test suite. These rules keep the suite fast
to run and the CI output clean.

## Priorities: richness, then runtime, then grouping

Three values guide the suite, in order. Richness comes first. A rich test asserts
many things, both the direct result and its converse. For example, a test asserts
that all foo are bar, and also that no foo is baz. Never drop an assertion to fit
a fixture or a grouping. Runtime comes second. A shared fixture makes the suite
fast, but a shared fixture never justifies a weaker test. Grouping comes last. The
namespace layout serves the first two. Never split or merge namespaces in a way
that costs richness or runtime.

## Lead with integration tests

Drive the full `app` handler over request maps against a real Datalevin store.
One test then exercises coercion, the route, the datalog, and the response
together. Write a unit test only for logic that earns it. Examples are the token
regex, the match compiler, and the decay math. A test through the handler asserts
behavior at the lasting interface, so an internal refactor does not break it.

## Let the fixture need set the namespace boundary

The fixture need sets the namespace, not the concern. Three fixture needs exist.
A pure test needs no store. A store-driven test needs one shared store. A test
that needs a pristine or a destroyed store needs its own fresh store. Put the pure
tests in one namespace with no fixture. Put the store-driven tests in one
namespace with one `:once` store, and keep each test to its own user. Put the
fresh-store tests in one namespace that opens a fresh store per test. Group by
concern inside a namespace with section comments. Do not add another namespace for
a concern. Another namespace is another migration and another re-index.

## One `:once` fixture per namespace

Use `clojure.test/use-fixtures` with `:once`, not `:each`. A `:once` fixture opens
the migrated connection and builds the app with its recall-count writers. It runs
the tests. On teardown it drains the writers before it closes the connection.

`:once` matters for CI time. `fresh-conn` opens a connection, runs every
migration, and rebuilds the full-text index on the fresh directory. That cost is
large. `:each` pays it once per test. `:once` pays it once per namespace.

The drain-before-close order also removes the stat-flush errors. A writer flushes
on a background thread, so a flush that fires after the connection closes throws.
The drain flushes while the store is still open. That is the same order the
Integrant system uses in production.

## Keep per-user isolation so a shared store stays safe

A `:once` store is shared by every test in the namespace. engram isolates every
memory by user, and there is no cross-user space. So give each test its own user
id or its own `src` names. The tests then do not collide inside the shared store.
Reserve a fresh store for a test that needs a pristine database. A migration test
or a re-index test is the case.

## Bind away expected system errors in unit tests

A test that is expected to emit a system error must not pollute the log. Wrap that
test in `taoensso.telemere/with-min-level` at a level above the error, so the
expected line stays out of the output. The deliberate 500 test is the clear case,
and it logs on the test thread, so the scoped level covers it. `with-min-level` is
thread-scoped. A background-thread error does not reach it. The stat-flush race is
the example, and the draining `:once` fixture removes it at the source instead.
When a test does not assert stats at all, inject no-op writers, so it never starts
the async machinery.

## Assert an async count with a flush watcher

The recall-count writer flushes on a debounce, off the request thread, so a count
is not in the store the moment a recall returns. Do not drain to force it. Drain
is for shutdown. Instead build the writer with a short `debounce-ms`. Add a watch
on its `:last-flush` atom that delivers a promise. Record the recall, then block
on the promise. When the real flush commits, the watcher fires. Then read the
count and assert. This exercises the true debounce and flush path.

# record-fetch! decay atomicity research

> Home of this note: the repo keeps design-review tickets under
> `.scratch/design-review/issues/`. Ticket 10 set the precedent that findings
> live in a sibling `research/` folder, and ticket 02 followed it. This note
> follows the same precedent and lives at
> `.scratch/design-review/research/record-fetch-decay-atomicity.md`.

All claims below trace to a primary source. The sources are four. The first is
the Datalevin source tree at the exact tag this repo uses. The second is the
DataScript docs it derives from. The third is the Datomic schema and
transaction docs as pattern origin. The fourth is the LMDB docs. Each Datalevin
claim cites a file path plus a line and an identifier. The source is a clone at
tag `1.0.1` (commit `3a442ad`, "Version 1.0.1"). `deps.edn` pins
`datalevin/datalevin {:mvn/version "1.0.1"}`, so the tag matches the live
code.

## Problem statement

`record-fetch!` in `src/engram/stats.clj` is a read-modify-write in two steps.
It calls `(d/db conn)` to take a snapshot. It reads the current row with
`existing`. It computes the new `lifetime` (an increment) and the new `decayed`
(decay the old weight to now, then add one) in Clojure, outside any
transaction. It then transacts absolute values for `:stat/lifetime` and
`:stat/decayed`. Two concerns follow.

First, lost updates under concurrency. Two fetches of the same (user, category,
label) that both read before either writes each compute from the same old
value. Each asserts an absolute value. The second transaction overwrites the
first, and one increment is lost. http-kit serves requests on a thread pool
(`src/engram/system.clj`, `:engram.web/server`, `hk/run-server`), so concurrent
fetches of the same pair are possible. Ticket 07 moves this write off the
request thread, which changes the concurrency picture. This note addresses that
interaction.

Second, the decay is computed against a snapshot taken before the transaction
commits, not against the state at commit time. Even single-threaded, the
projected decay uses the read-time clock and the read-time prior weight.

## Question 1: does Datalevin serialize writes through a single writer?

Yes. Datalevin runs on LMDB, and LMDB permits one write transaction at a time.
The LMDB docs state that write transactions are fully serialized. Source: LMDB
documentation, http://www.lmdb.tech/doc/, and the LMDB overview at
https://en.wikipedia.org/wiki/Lightning_Memory-Mapped_Database. The Datalevin
README repeats this. It states "Write transactions prevent other write
transactions, since writes are serialized." Source: Datalevin README,
https://github.com/datalevin/datalevin/blob/master/README.md.

The serialization is a JVM monitor lock in the Datalevin source. The
`with-transaction-kv` macro wraps its body in `(locking (write-txn orig-db#)
...)`. Source: `src/datalevin/lmdb.clj:584`, macro `with-transaction-kv`. The
Datalog-level `with-transaction` macro does the same. It wraps the body in
`(locking orig-conn#)` and then `(locking (l/write-txn s#))`. Source:
`src/datalevin/conn.clj:284` and `:291`, macro `with-transaction`.

What this guarantees. One `d/transact!` call is atomic. A single transaction
runs to completion before any other write transaction starts. Two writers can
never interleave inside one transaction.

What this does NOT guarantee. Two separate steps in application code are not one
transaction. A read with `(d/db conn)` followed later by a `d/transact!` is two
calls. The single-writer lock covers each call, not the gap between them. The
read snapshot can be stale by the time the transaction commits. Another writer
can commit in that gap. The LMDB serialization does not close a race that spans
two separate calls. It only serializes each call. This is the exact shape of the
`record-fetch!` defect.

## Question 2: does Datalevin support compare-and-swap in a transaction?

Yes. Datalevin supports both `:db.fn/cas` and `:db/cas` as transaction
operations. The transaction loop dispatches on them. Source:
`src/datalevin/db/tx/execute.clj:742`, `(or (identical? op :db.fn/cas)
(identical? op :db/cas))`, which calls `handle-cas`.

Syntax. The operation is a vector `[:db.fn/cas e a old-value new-value]`. The
`handle-cas` function destructures it as `[_ e a ov nv]`. Source:
`src/datalevin/db/tx/execute.clj:425`, function `handle-cas`, line `:427`.

Semantics. `handle-cas` reads the current datoms for `e` and `a` from the
in-transaction db. It reads from `(:eavt db)` and from `(:store db)`. Source:
`src/datalevin/db/tx/execute.clj:438` to `:444`. It then calls
`validate-cas-value`. Source: `src/datalevin/db/tx/execute.clj:445`. That
function behaves as follows. When the stored value does not match the expected old value,
it raises `:db.fn/cas failed`. Source: `src/datalevin/validate.clj:1476` to
`:1487`. The old value must still match at execution time. A match asserts the
new value. A mismatch aborts the transaction with an exception.

The Datomic `:db/cas` is the pattern origin and states the same rule. The
four-argument form is entity, attribute, expected old value, new value. When the
entity has the expected value in db-before, the CAS asserts the new value.
Otherwise the transaction aborts and throws. When no value exists, a nil old
value asserts the new value. Source: Datomic transaction docs,
https://docs.datomic.com/transactions/transaction-functions.html.

How a CAS makes the update safe or forces a retry. A CAS on `:stat/lifetime`
turns the blind absolute write into a conditional one. The transaction data
carries the value read at snapshot time as the expected old value.

```clojure
[:db.fn/cas stat-eid :stat/lifetime old-life new-life]
```

If another fetch committed a new lifetime in the gap, the stored value no longer
matches `old-life`. The transaction throws `:db.fn/cas failed`. The caller
catches the failure, re-reads, recomputes, and retries. No increment is lost,
because a losing writer never commits its stale value.

Two limits apply to CAS for this use.

First, CAS needs the entity id. `handle-cas` calls `entid-strict` on `e`.
Source: `src/datalevin/db/tx/execute.clj:428`. So the caller resolves the row
first, for example by the composite tuple lookup ref from ticket 02. A first
fetch of a new pair has no row yet, so that path is a plain upsert, not a CAS.

Second, CAS guards one attribute value. `:stat/decayed` is a double computed
from the prior weight and the elapsed time. A CAS on `:stat/decayed` matches the
exact prior double. That guards the decayed field against a concurrent write.
The decay value itself is still computed in application code before the
transaction (see Question 5).

## Question 3: does Datalevin support transaction or database functions?

Yes. Datalevin supports `:db.fn/call`, installed `:db/fn` attributes, and
installed `:db/udf` descriptors. The transaction loop dispatches on
`:db.fn/call`. Source: `src/datalevin/db/tx/execute.clj:724`, `(identical? op
:db.fn/call)`, which calls `handle-fn-call`. The loop also dispatches on a
keyword op that names an installed function. Source:
`src/datalevin/db/tx/execute.clj:733`, `(and (keyword? op) (not (builtin-fn?
op)))`, which calls `handle-custom-tx-fn`. The error message lists the accepted
forms. It names an ident that maps to an installed transaction function, in the
shape `{:db/ident <keyword> :db/fn <Ifn>}` or `{:db/ident <keyword> :db/udf
<descriptor>}`. Source: `src/datalevin/validate.clj:1572`.

Shape. A call is a vector `[:db.fn/call f arg1 arg2 ...]`. `handle-fn-call`
destructures it as `[_ target & args]`, resolves the function, then runs `(apply
f db args)`. Source: `src/datalevin/db/tx/prepare.clj:280` to `:284`, function
`handle-fn-call`. An installed function runs `(apply fun db args)`. Source:
`src/datalevin/db/tx/prepare.clj:299`, function `handle-custom-tx-fn`. The
function receives the db as its first argument. It returns tx-data, which the
loop expands inline through `prepare-tx-fn-result`. Source:
`src/datalevin/db/tx/execute.clj:726` and `:735`.

Where the db comes from. The tx loop rebinds `db` to `(:db-after report)` on
each step. Source: `src/datalevin/db/tx/execute.clj:841`. So a function later in
the same transaction sees the datoms added by earlier steps in that
transaction. The function receives the in-transaction db value, not a stale
outside snapshot.

Does it run at a serialized commit point? The transaction runs inside
`local-transact-tx-data`, which runs inside the single-writer lock described in
Question 1. The whole transaction, function included, runs while that write lock
is held. So the function reads and writes at a point where no other writer can
interleave. This closes the race for the row the function touches.

DataScript is where `:db.fn/call` originates. It documents the same call form
and the db-first argument. Source: DataScript transaction docs,
https://github.com/tonsky/datascript/blob/master/docs/transactions.md.

A transaction function computes both the increment and the decay atomically. The
function reads the current row from the passed db, computes the new lifetime and
the new decayed weight, and returns the asserting datoms. All of it runs under
the write lock, so no concurrent fetch reads the same old value.

```clojure
;; conceptual shape; runs at commit under the write lock
(defn bump-stat [db user c l half-life-days now-ms]
  (let [[eid life dec last] (read-row db user c l)
        decayed (if last
                  (+ 1.0 (* dec (decay-factor half-life-days (- now-ms last))))
                  1.0)]
    [{:stat/user user :stat/category c :stat/label l
      :stat/lifetime (inc (long (or life 0)))
      :stat/decayed decayed
      :stat/last-request (java.util.Date. now-ms)}]))
```

One caveat on the clock. The tx data still carries `now-ms` computed by the
caller before the transaction. The function reads the prior weight at commit,
which fixes the prior-weight staleness. The clock caveat stays (see Question 5).

## Candidate solution A: serialized single-writer for stat updates

The idea. Funnel every stat write through one Clojure agent or a
single-consumer queue. The read-modify-write is then never concurrent, because
one consumer runs them one at a time.

Source basis. This is a standard Clojure concurrency idiom, not a Datalevin
feature. The primary support is that LMDB and Datalevin already serialize the
write itself (Question 1). A single consumer serializes the read plus the write
as one unit in application code.

Tradeoffs. A single consumer removes the race by construction. It also matches
ticket 07 well. Ticket 07 asks that the fetch not wait on the stats write, and
that a stats write failure never fails the fetch (`.scratch/design-review/issues/07-non-blocking-fetch-stats-write.md`).
An agent send from the request thread returns at once, and the agent runs the
read-modify-write off-thread. So one mechanism satisfies both 07's off-thread
goal and this note's serialization goal. The cost is one more moving part. If
the consumer stalls, the queue grows. This solution does not fix the clock
concern by itself, because the consumer still computes decay in application code
(see Question 5).

Interaction with 07. This solution is the strongest fit for 07. A per-connection
single consumer is off-thread, non-blocking to the fetch, and serialized. It
satisfies 07's intent directly, and it removes the lost-update race as a side
effect.

## Candidate solution B: compare-and-swap with retry

The idea. Read the row and compute the new values. Then transact a `:db.fn/cas`
on `:stat/lifetime` that carries the read value as the expected old value. The
same can guard `:stat/decayed`. On a `:db.fn/cas failed` exception, re-read and
retry.

Source basis. Datalevin supports `:db.fn/cas` and `:db/cas` at
`src/datalevin/db/tx/execute.clj:742`. The fail semantics live at
`src/datalevin/validate.clj:1476`. Datomic is the pattern origin at
https://docs.datomic.com/transactions/transaction-functions.html.

Tradeoffs. CAS keeps the write blind-free without a global lock. It needs a
retry loop and a resolved entity id, so a first-time pair falls back to a plain
upsert. Under the stated load of 1 to 10 users with sparse calls, retries are
rare. CAS on `:stat/lifetime` alone guards the count. A separate CAS on
`:stat/decayed` guards the double against a concurrent write. CAS does not fix
the clock concern (see Question 5).

## Candidate solution C: transaction or database function at commit

The idea. Install a `:db/fn` or pass a `:db.fn/call`. The function reads the
current row from the in-transaction db. It computes the increment and the decay.
It returns the asserting datoms. All of it runs at commit under the write lock.

Source basis. Datalevin supports `:db.fn/call` and installed functions at
`src/datalevin/db/tx/execute.clj:724` and `:733`. The db-first argument lives at
`src/datalevin/db/tx/prepare.clj:284` and `:299`. The in-transaction db is at
`src/datalevin/db/tx/execute.clj:841`. DataScript is the origin at
https://github.com/tonsky/datascript/blob/master/docs/transactions.md.

Tradeoffs. This is the tightest fix for the lost update, because the read and
the write happen at one serialized commit point with no application-side gap. It
partly fixes the clock concern, because the prior weight is read at commit, not
at an earlier snapshot. The elapsed-time clock is still a caveat (see Question
5). The cost is that the decay math moves into a function that runs inside the
transactor path. That function must stay pure and fast, since it holds the write
lock while it runs. This solution conflicts with ticket 06's plan to keep the
planner a plain pure function that returns tx-data, because the compute moves
inside the transaction. Ticket 06 can still hold the planner pure and have the
planner emit the `:db.fn/call` form as data.

## Candidate solution D: store raw events, compute on read

The idea. Make the write a pure append. Each fetch adds one event datom with a
timestamp. The lifetime is the event count. The decayed weight is a fold over
the event timestamps at read time.

Source basis. This needs only `:db/add` of new entities, which is a plain
transaction. No read-modify-write, so no lost update by construction. This is a
schema and design choice, not a Datalevin feature to cite.

Tradeoffs. The write is safe with no lock, no CAS, and no function. The cost
moves to the read. The recall path is meant to be cheap. The docstring in
`resources/migrations/001-schema.edn` notes the recall path stays cheap, and the
`stats` read in `src/engram/stats.clj` already projects decay on read. Reading
now folds over every event for a pair, which grows without bound as fetches
accumulate. A periodic compaction into a rolled-up row can bound the read cost,
but that compaction is itself a read-modify-write and reintroduces the race it
removed. This solution trades a write race for a read cost.

## Candidate solution E: accept last-write-wins as documented

The idea. Keep the current code and document last-write-wins as a known
assumption.

Source basis. The soundness condition is a probability argument over the stated
load. The project describes 1 to 10 users with sparse calls. For soundness, the
read-to-commit gap must almost never overlap for the same pair.

Exact conditions under which this is sound. All of the following must hold. One,
the load stays at the stated 1 to 10 users. Two, calls stay sparse, so two
fetches of the same (user, category, label) within one read-to-commit window are
rare. Three, an occasional lost increment is acceptable, since the counts are
approximate popularity signals, not exact ledgers. When ticket 07 moves the
write off-thread, the read-to-commit gap can grow, because the write is queued
behind other work. A larger gap raises the collision odds, so 07 makes this
assumption weaker, not stronger.

## Question 5: does any option fix the "decayed computed before commit"?

The clock concern has two parts. One is the prior weight used in the decay. The
other is the elapsed-time clock, `now-ms`, used in `decay-factor`.

Prior weight. Solution C fixes this part. A transaction function reads the prior
`:stat/decayed` at commit from the in-transaction db, so it uses the weight that
is current at commit. Solution B with a CAS on `:stat/decayed` does not fix it.
CAS aborts on a stale prior weight, then the retry re-reads and recomputes, so
the committed value is fresh, but only after a retry. Solution A fixes it in
effect. The single consumer reads and writes with no other writer in between. So
its snapshot equals the commit state for that row.

Elapsed-time clock. Consider the case where the decay is a projection computed
in application code with a `now-ms` captured before the transaction. No option
fixes this fully. The captured `now-ms` can drift from the true commit instant.
The drift is the queue delay plus the transaction time. Under the stated sparse
load this drift is small relative to a half-life measured in days. So the decay
error is negligible. A transaction function can read a clock inside itself to
shrink the drift. That step reintroduces impurity into the function and is
unverified as a Datalevin-safe practice. The clean statement is this. A decay
projected in application code carries an inherent clock caveat. The caveat is
bounded and small under the stated load.

## Recommendation

Solution A, a serialized single-consumer for stat writes, is the best-supported
option for engram. The reasons follow.

It removes the lost-update race by construction, and its only primary-source
dependency is the serialization that Datalevin already provides (Question 1). It
is the tightest fit for ticket 07. A per-connection agent or single-consumer
queue is off the request thread. It does not delay the fetch. It isolates a
stats write failure from the fetch response. That is exactly 07's three
requirements. The same mechanism that satisfies 07 also serializes the
read-modify-write. So one change closes both.

Solution C, a transaction function at commit, is the strongest correctness
story on its own. It reads and writes at one serialized point. It fixes the
prior-weight staleness. It conflicts with ticket 06's pure-planner plan, because
the compute moves inside the transaction. Ticket 06 can adapt by having the pure
planner emit the call form as data.

Solution B, CAS with retry, is a sound middle path. It fits a design that avoids
a single consumer. It needs a resolved entity id and a retry loop.

This is best handled as a new ticket, not folded into 06 or 07. Ticket 06 is a
pure refactor and must stay narrow. Ticket 07 is the off-thread move. The
atomicity fix rides naturally on 07's mechanism choice. So the new ticket must
depend on 07. It names the single-consumer approach as the default, with CAS as
the fallback. The new ticket must state the clock caveat from Question 5 as a
documented, bounded limitation.

Marked unverified. The exact `:db.fn/call` and installed `:db/fn` ergonomics in
Datalevin 1.0.1 are read from the source. No end-to-end engram test proves
the decay function runs correctly under `with-transaction`. The claim that a
single-consumer queue satisfies 07 in practice is a design judgment, not a
sourced fact. The claim that reading a clock inside a transaction function is
safe in Datalevin is unverified.

## Sources

- LMDB documentation, write transactions serialized: http://www.lmdb.tech/doc/
- LMDB overview, single-writer serialization:
  https://en.wikipedia.org/wiki/Lightning_Memory-Mapped_Database
- Datalevin README, "Write transactions prevent other write transactions, since
  writes are serialized":
  https://github.com/datalevin/datalevin/blob/master/README.md
- Datalevin 1.0.1 source, tag `1.0.1`, commit `3a442ad`. Files and identifiers:
  - `src/datalevin/lmdb.clj:584`, `with-transaction-kv`, `(locking (write-txn
    orig-db#))`, with a counter read-modify-write example in the docstring.
  - `src/datalevin/conn.clj:253`, `with-transaction`, `(locking orig-conn#)` and
    `(locking (l/write-txn s#))`, with a counter increment example.
  - `src/datalevin/db/tx/execute.clj:742`, CAS dispatch on `:db.fn/cas` and
    `:db/cas`, calling `handle-cas` at `:425`, reading current datoms at `:438`.
  - `src/datalevin/validate.clj:1476`, `validate-cas-value`, `:db.fn/cas failed`
    on mismatch.
  - `src/datalevin/db/tx/execute.clj:724` and `:733`, dispatch on `:db.fn/call`
    and on installed function idents.
  - `src/datalevin/db/tx/prepare.clj:280`, `handle-fn-call`, `(apply f db
    args)`, and `:286`, `handle-custom-tx-fn`, `(apply fun db args)`.
  - `src/datalevin/db/tx/execute.clj:841`, the loop rebinds `db` to `(:db-after
    report)`, so a function sees in-transaction datoms.
  - `src/datalevin/validate.clj:1572`, accepted ops including installed `:db/fn`
    and `:db/udf` idents.
- Datomic transaction functions, `:db/cas` semantics and origin:
  https://docs.datomic.com/transactions/transaction-functions.html
- DataScript transaction docs, `:db.fn/call` origin and db-first argument:
  https://github.com/tonsky/datascript/blob/master/docs/transactions.md
- Repo files cited:
  - `src/engram/stats.clj` (`record-fetch!`, `existing`, `stats`, `decay-factor`).
  - `resources/migrations/001-schema.edn` (`:stat/*` attributes and the composite tuple).
  - `src/engram/system.clj` (`:engram.web/server`, `hk/run-server`).
  - `deps.edn` (`datalevin` version).
  - `.scratch/design-review/issues/06-pure-fetch-stats-planner.md`.
  - `.scratch/design-review/issues/07-non-blocking-fetch-stats-write.md`.
</content>
</invoke>

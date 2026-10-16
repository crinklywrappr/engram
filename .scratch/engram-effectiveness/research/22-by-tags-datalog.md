# A nested match expression for recall by tags

## The question

Ticket 22 adds a nested boolean match expression. It lands on the
`by-tags` recall route. The body becomes `{:match <expr>}`. An expr is one
of four forms. A leaf is a tag pair. The three operators are `:and`, `:or`,
and `:not`. Semantics scope to the caller's own memories. `and` is set
intersection. `or` is set union. `not` is set difference inside the caller's
memories. Nesting goes to any depth. This note records how to build the
datalog. It compares the two strategies the ticket names.

## Recommendation up top

Build the one-query compiler (strategy 1). A probe compiled a three-operator
nested expression to one datalog query and ran it against a real store. The query
returned the correct memories. The compiler is about twelve lines. It emits the
recall rows directly, so the match path feeds the existing related-closure walk
with no extra lookup. The Clojure fold (strategy 2) is also correct. It needs an
`eids-by-eids` lookup the repo lacks. It runs N leaf queries where the compiler
runs one. The fold is the fallback. Once the compiler's binding rules prove hard
to maintain, switch to it.

The decisive point is integration. The current `recall-by-tags` hands `query` a
seq of `[eid (distinct ?r)]` rows (`memory.clj:505-515`). The one query emits that
exact shape itself (T5 below), so it drops into `query` unchanged. The fold yields
a bare eid set, so it needs a new related lookup over those eids before the walk.

## The verified compiler

A probe built this compiler and ran it against a fresh store through a `-Sdeps`
alias with no main-opts. The store held alice's five memories and bob's one.

```clojure
(defn- leaf-clauses [e [category label]]
  (let [t (gensym "?t")]
    [[e :memory/tag t] [t :tag/category category] [t :tag/label label]]))

(defn- match->clauses
  "Compile a match expression into :where clauses binding ?e. A leaf emits three
  datoms through a fresh tag var. :and concatenates. :or becomes an or-join over
  ?e, each child an (and ...) branch. :not becomes a not-join over ?e."
  [e expr]
  (if (map? expr)
    (let [[op arg] (first expr)]
      (case op
        :and (vec (mapcat #(match->clauses e %) arg))
        :or  [(list* 'or-join [e] (map #(cons 'and (match->clauses e %)) arg))]
        :not [(list* 'not-join [e] (match->clauses e arg))]))
    (leaf-clauses e expr)))

(defn match-query [expr]
  (vec (concat '[:find [?e ...] :in $ ?u :where [?e :memory/user ?u]]
               (match->clauses '?e expr))))
```

The probe input nested all three operators: `domain:clojure` and either
`tech:datalevin` or `tech:reitit` and not `tech:sqlite`. It compiled to a lead
`[?e :memory/user ?u]` clause, three domain datoms, an `or-join [?e]` over two
`and` branches, and a `not-join [?e]`. It returned `("m1" "m2")`. A top-level
`{:not ["tech" "sqlite"]}` returned `("m1" "m2" "m5")`. Bob's matching memory
never appeared.

Three rules make the compiler correct. The query leads with `[?e :memory/user ?u]`,
so `?e` is bound before any `not-join` (see T4). Every `or-join` and `not-join`
lists only `[?e]`, so each leaf tag var stays branch-local, and a fresh `gensym`
keeps the vars apart. Conjunction in a `:where` is juxtaposition, so `:and` is a
plain `mapcat`.

## The data model, grounded

A memory entity carries `:memory/user`, a string
(`resources/migrations/001-schema.edn:16`). `:memory/tag` is a cardinality-many
component ref to tag entities (`001-schema.edn:19`). Each tag entity carries
`:tag/category` and `:tag/label`, both strings (`001-schema.edn:23-24`). So a leaf
pair matches a memory through one path. The path is `[?e :memory/tag ?t]`, then
`[?t :tag/category ?c]`, then `[?t :tag/label ?l]`. This is the path `eids-by-tag`
already uses (`src/engram/memory.clj:454-462`). `recall_count.clj` queries the
same path at `src/engram/recall_count.clj:57-60`.

The existing recall feeds matched rows into a related-closure walk. `recall-by-tags`
runs `eids-by-tag` per pair and hands `query` a seq of deferred lookups
(`memory.clj:505-515`). Each lookup yields `[eid (distinct ?r)]` rows, where `?r`
is each related src or the `:none` sentinel (`memory.clj:454-462`, the
`related-rules` at `memory.clj:447-452`). `query` then walks the related-by-src
closure from those rows (`memory.clj:474-503`). Any new match path must end by
producing the same `[eid srcs]` rows so `query` is reused unchanged.

## Datalevin datalog can express the full grammar (strategy 1)

Datalevin datalog is a fork of DataScript. The parser and the query resolver both
carry Nikita Prokopov's copyright, the DataScript author (`datalevin/parser.clj:2`,
`datalevin/query/resolve.clj:2` in the 1.0.1 jar). So the `or`, `or-join`, `not`,
and `not-join` semantics are DataScript's, with Datalevin's own planner underneath.

### Nesting is recursive in the grammar

The clause grammar lists four nested clause kinds. They are the not clause, the
not-join clause, the or clause, and the or-join clause. Each one contains more
clauses (`datalevin/parser.clj:511-522`). `parse-clause` dispatches to the four
parse functions. Each parse function calls `parse-clauses` on its body. That
recurses back through `parse-clause` (`parser.clj:622-699`). So the grammar nests
to any depth by construction. An `and` branch sits inside `or` and `or-join`.
`parse-and` handles it (`parser.clj:652-659`). Both `parse-or` and `parse-or-join`
reach it through `(some-fn parse-and parse-clause)` (`parser.clj:665`,
`parser.clj:677`).

### Variable-binding rules at resolve time

The resolver enforces three binding rules that the compiler must honor.

- `not-join [vars] clauses`: every var in `vars` must already be bound in the outer
  context. `check-bound` raises "Insufficient bindings" otherwise
  (`query/resolve.clj:959-963`, the raise at `resolve.clj:822-827`). The resolver
  then computes `subtract-rel` of the outer relation minus the negation relation
  (`resolve.clj:970-973`). So `not-join` is a true set difference against whatever
  the outer `:where` already bound.
- `or-join [vars] branches`: the free vars must appear in every branch.
  `check-free-subset` raises otherwise (`resolve.clj:922-926`, the raise at
  `resolve.clj:837-845`). Each branch resolves independently, is limited to the
  join vars, and the branch relations are deduped-unioned
  (`resolve.clj:927-937`).
- Bare `not` and bare `or` collect their own free vars. `not` requires at least one
  of its vars to be bound in the outer context (`resolve.clj:943-950`). `or`
  requires all branches to use the same free-var set (`resolve.clj:904-911`, the
  raise at `resolve.clj:829-835`). The explicit-var `or-join` and `not-join` forms
  are safer for a compiler. The var list is fixed to `[?e]`. The compiler does not
  depend on each branch's incidental vars.

### How `not` scopes the user filter

`not-join [?e] ...` subtracts from the relation already bound for `?e`
(`resolve.clj:970-973`). So if `[?e :memory/user ?u]` runs first in the outer
`:where`, the subtraction is taken inside the caller's memories only. A probe
confirmed this. `NOT domain/clojure` for alice returned her non-matching memories.
It never returned bob's matching memory. See T3 below.

### Empirical verification of nesting and scoping

I built a fresh Datalevin store with the real schema shape. I ran probes through a
`-Sdeps` alias. The alias has no main-opts, so the test-runner did not intercept.
I defined a `leaf` rule `[(leaf ?e ?c ?l) [?e :memory/tag ?t] [?t :tag/category ?c]
[?t :tag/label ?l]]`. Each probe used a user-scoped `:where`. It starts with `[?e
:memory/user ?u]`.

Seed: alice has m1 with domain/clojure and scope/project, m2 with domain/clojure,
m3 with scope/project, m4 with tech/datalevin, m5 with no tags. bob has zA with
domain/clojure.

- T1, `(A AND B) OR (NOT C)` as `(or-join [?e] (and (leaf ?e "domain" "clojure")
  (leaf ?e "scope" "project")) (not-join [?e] (leaf ?e "tech" "datalevin")))`,
  returned m1 m2 m3 m5. Correct, and bob's zA never appeared.
- T2, `A AND (B OR (NOT C))`, returned m1 m2. Correct nesting of `or-join` with a
  `not-join` branch.
- T3, bare `NOT A` with the user pattern first, returned m3 m4 m5. Difference within
  the user, bob excluded.
- T4, a top-level `not-join [?e]` with no preceding `[?e :memory/user ?u]`, raised
  "Insufficient bindings: #{?e} not bound". This confirms the compiler must emit the
  user pattern before the match expression.
- T5 used the full recall shape. The find was `?e (distinct ?r)`. The match was `A
  OR (NOT B)`, with `related-or-none` appended. It returned rows that are srcs m1 m2
  m4 m5, each paired with its related srcs or `:none`. So the one-query output feeds
  the existing `query` walk unchanged.
- T6, a match of a pair no memory carries, returned `()`. The empty result is
  clean.
- T7 and T9, `NOT (NOT A)`, returned m1 m2, which equals A. The inner `not-join
  [?e]` reads `?e` from the outer not-join scope, so the inner negation needs no
  re-stated user pattern (T9).
- T10, `NOT (A OR B)`, returned m3. De Morgan composes.
- T8, an `or-join [?e]` branch that bound an extra local `?x` did not error. The
  `or-join` var list fixes the shared var to `?e`, and extra vars inside a branch
  stay local. So the compiler lists only `[?e]` on every `or-join` and `not-join`.

### Divergence from Datomic to note

DataScript and Datalevin require the explicit-var forms far more often than Datomic.
Datomic can infer many `or` and `not` bindings. Here the resolver has hard
`check-bound` and `check-free-subset` raises (`resolve.clj:822-845`). So the
compiler must be deliberate. Start every match with `[?e :memory/user ?u]`. Use
`or-join [?e]` and `not-join [?e]`, not the bare `or` and `not`. Then the shared
var is always `?e`, never an accident of a branch's internal vars.

## The fold over per-leaf eid sets (strategy 2)

Run one small query per distinct leaf pair to get its eid set, scoped to the user
with `[?e :memory/user ?u]` and the leaf path. Get the caller's full eid set once
for the `not` universe. Walk the expression tree and combine with
`clojure.set/intersection`, `union`, and `difference`.

A probe ran the identical expressions through a fold evaluator. The universe was
alice's five memories. `(A AND B) OR (NOT C)` returned m1 m2 m3 m5. `A AND (B OR NOT
C)` returned m1 m2. `NOT A` returned m3 m4 m5. Every answer matched the one-query
answers exactly.

- Expresses the full grammar: yes. Leaf is a query, the three operators are the
  three `clojure.set` functions, recursion walks the tree.
- Correctness traps: `not` is `(difference universe child)`. The universe is the
  caller's own eids from `[?e :memory/user ?u]`. Compute the universe once. An empty
  child subtracts nothing, so it gives back the whole universe. That is correct for
  `NOT (match nothing)`. An empty `:and` or `:or` child list cannot occur. The
  grammar requires one or more children.
- Performance shape: one query per distinct leaf pair, plus one universe query when
  any `not` appears. Collapse duplicate leaves to one query by the pair key. Every
  leaf query uses the `:memory/user` and the tag indexes, the same access the
  current `eids-by-tag` uses.
- Code complexity: low. A `postwalk`-style recursion over the expression map, three
  set calls, and one leaf query. No query compilation, no var-binding rules to get
  right.
- Feeds the existing walk cleanly: the fold yields a set of eids. Turn that set
  into the `[eid srcs]` rows that `query` expects. Run the existing related lookup
  once across the eids. Use an `eids-by-ids`-shaped query, or a small
  `eids-by-eids`. Then call `query` as today. The related closure stays untouched.

## Head to head

- Expressiveness: both cover the full grammar. A probe confirmed both.
- Correctness: a probe confirmed both equal on three nested expressions, on the empty match,
  and on double negation. The one query has a sharp edge: forget the leading user
  pattern and `not` either errors or, worse in a future refactor, leaks. The fold's
  `not` is an explicit `difference` against an explicit user universe, so the
  scoping is visible in the Clojure, not implicit in clause order.
- Performance: the one query is a single datalog call. The fold is N leaf queries
  plus one universe query. At engram's scale, a per-user store of atomic facts, N
  is tiny and each query hits an index. The difference is not user-visible. If a
  pathological expression ever names hundreds of distinct pairs, revisit.
- Complexity: the fold is far less code. The one query needs a compiler that emits
  `or-join`/`not-join` with the right var lists, injects the leaf rule, always leads
  with the user pattern, and keeps a single `?e`. That compiler is testable but is a
  real surface to maintain.
- Integration: both end at the existing `query` walk. The fold reaches it through
  one extra related lookup over the matched eids. The one query reaches it directly,
  because it can emit `?e (distinct ?r)` itself (T5).

## Residual unknowns for the implementer

- The compiler emits `?e (distinct ?r)` with the `related-or-none` rule appended,
  so it feeds `query` directly (T5). The compiled `:find` and the rule injection
  are the one piece the recall path does not have today.
- Does the match test leaf pairs against the tag schema? The other option is a
  free match that returns nothing for an unknown pair. The current recall by tags
  does not test pairs on read (`memory.clj:505-515`). So a free match keeps that
  behavior. Confirm the ticket wants the same.
- A fresh tag var per leaf is required, because `:memory/tag` is cardinality-many.
  A reused var forces one tag entity to carry two pairs, which no entity does. The
  compiler uses `gensym` per leaf.
- The compiler always leads with `[?e :memory/user ?u]` and lists only `[?e]` on
  every `or-join` and `not-join`. The probes show those two rules are the whole
  correctness burden (T4, T8).

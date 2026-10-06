# engram

engram is a per-user memory server. It holds standalone atomic facts tagged with
category and label pairs, and returns them together with the facts they link to.

## Language

**Memory**:
One standalone atomic fact owned by a user. It states one thing. It is the
long-term store, in contrast to a short-term memory.
_Avoid_: note, record, entry

**Atomic fact**:
A single claim that stands on its own. A fact that needs a list is split into
several facts, one per item.
_Avoid_: observation, item

**Short-term memory**:
A loose task note a user stages during a task. It carries an id and free text in
any form. It holds no tags and makes no atomic claim. It lives apart from a
Memory. It never appears in a recall. A `/decompress` run promotes the worthwhile
notes to Memories. The run clears the rest.
_Avoid_: scratchpad, draft

**Thought-process**:
The bucket that holds a user's short-term memories for one task. The client names
it in lowercase kebab-case. A good name describes the project and the task. It
rarely collides. Another session can find it by name. Every short-term memory
operation names one.
_Avoid_: session, namespace, task-id

**Category**:
The namespace of a tag, for example `domain`. Categories are a closed set fixed
by the configuration.
_Avoid_: namespace, key, field

**Label**:
The value of a tag, for example `clojure`. Labels are open vocabulary and
lowercase kebab-case.
_Avoid_: value, tag-value

**category:label pair**:
One tag on a memory: a category paired with a label, for example `domain:clojure`.
_Avoid_: tag (when it means the pair)

**src**:
The identity of the source a fact came from. Exactly one per memory. Facts split
from one source share a src, and relations point at it.
_Avoid_: source-id, origin

**related**:
The set of other memories a fact links to, named by their src. A recall follows
these links across hops.
_Avoid_: links, references

**Configuration**:
One acceptable set of category-to-cardinality rules. A memory that satisfies any
one configuration is valid.
_Avoid_: schema, ruleset

**Cardinality**:
How many labels of a category a memory can carry: `1`, `?`, `*`, or `+`.
_Avoid_: multiplicity, arity

**User**:
The owner of a set of memories, identified by an SSH key. The key comment is the
user id. Memories are private to their user.
_Avoid_: account, tenant, client

**Recall**:
The act of asking for memories by their category:label pairs. A recall returns
the matching memories and the memories they link to across hops.
_Avoid_: query, request, fetch

**Recall count**:
How often a category:label pair was recalled. engram keeps it as a lifetime
total and a recent decay measure. On the wire a stats row is a positional row of
category, label, count, lifetime, and recent. The count is how many of the
caller's memories carry the pair. A pair that no recall touched still appears,
with a lifetime of 0 and a recent of 0.0.
_Avoid_: hits, fetch count

**Lifetime count**:
The running total of how often a category:label pair was recalled.
_Avoid_: total, hits

**Recent count**:
The exponential-decay measure of how often a category:label pair was recalled
lately, kept beside the lifetime count.
_Avoid_: score, frequency, weight

**Batch**:
A grouped map of operations a client applies in one atomic call. It has a create,
an update, and a delete group. All of the operations apply, or none do.
_Avoid_: bulk, transaction

**Operation**:
One create, update, or delete inside a batch. Its verb names which of the three
it is.
_Avoid_: op, action, command

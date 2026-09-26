# engram

engram is a per-user memory server. It holds standalone atomic facts tagged with
category and label pairs, and returns them together with the facts they link to.

## Language

**Memory**:
One standalone atomic fact owned by a user. It states one thing.
_Avoid_: note, record, entry

**Atomic fact**:
A single claim that stands on its own. A fact that needs a list is split into
several facts, one per item.
_Avoid_: observation, item

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
The set of other memories a fact links to, named by their src. A fetch follows
these links across hops.
_Avoid_: links, references

**Configuration**:
One acceptable set of category-to-cardinality rules. A memory that satisfies any
one configuration is valid.
_Avoid_: schema, config, ruleset

**Cardinality**:
How many labels of a category a memory can carry: `1`, `?`, `*`, or `+`.
_Avoid_: multiplicity, arity

**User**:
The owner of a set of memories, identified by an SSH key. The key comment is the
user id. Memories are private to their user.
_Avoid_: account, tenant, client

**Lifetime count**:
The running total of how often a category:label pair was fetched.
_Avoid_: total, hits

**Recent count**:
The exponential-decay measure of how often a category:label pair was fetched
lately, kept beside the lifetime count.
_Avoid_: score, frequency, weight

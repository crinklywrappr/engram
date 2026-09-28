# 2. Batch request is an order-independent grouped map

Status: Accepted

## Context

engram applies many memory changes in one call through `POST /memories/batch`.
The first design took an ordered list of grouped `[verb, payloads]` runs. Order
was significant. A later operation overrode or deleted the result of an earlier
one in the same batch.

That ordering added rules the client does not need. A client cannot see the id of
a memory it creates in the same batch. So a create never feeds an update or a
delete in the same call. The only id two operations can share is an id used by
both an update and a delete. Ordering the whole batch to resolve that one case is
more machinery than the case is worth.

## Decision

The batch request is a map with three optional keys: `create`, `update`, and
`delete`. Each key holds a list of payloads. The batch has no global order.

Within one group, list order still resolves same-target work. Several updates to
one id fold together, and a later entry wins a same-field tie. A repeated id in
the delete group is deduplicated, and the first entry wins, so an accidental
double delete is not an error.

Across the update and delete groups, an id must not appear in both. When it does,
the server rejects the whole batch. The rest of the rules from ADR 0001 hold. The
batch is atomic, so the server validates everything first and writes nothing on
any failure.

## Consequences

The request is simpler to build and to read. The client groups its work by verb
and does not reason about a global order.

One rule replaces the ordering machinery. An id in both the update and the delete
group is a client mistake, so the server names both operations and rejects the
batch.

The cost is that a client cannot express "update this memory, then delete it" in
one batch. That sequence was already pointless. The delete removes the update
result anyway. A client that wants both sends the delete alone.

# 1. Atomic batch writes

Status: Accepted

## Context

engram lets a client apply many memory changes in one call, through
`POST /memories/batch`. A batch carries an ordered list of create, update, and
delete operations. The migrate skill is the first heavy user. It distills a
project's markdown files into many facts and sends them together.

One operation can fail. A create or update can carry tags that match no
configuration. An update or delete can name an id that does not exist for the
caller. When one operation fails, the batch needs one rule for the other
operations.

Two models were possible. An atomic batch validates every operation first. If all
pass, it applies them in one transaction. If any fails, it writes nothing. A
partial batch applies each operation on its own, keeps the ones that pass, and
reports the ones that fail.

## Decision

The batch is atomic and all-or-nothing. The server validates and resolves every
operation against the batch's starting state before it writes. If every operation
passes, the server commits them in one transaction and returns 200. If any
operation fails, the server writes nothing and returns 422 with the failing
operations.

## Consequences

The client always knows the store's state after a batch. A 200 means every change
landed. A 422 means nothing changed. The client can fix the named operations and
resend the whole batch without fear of duplicates.

The migrate skill gains a clean retry. It runs a dry-run preview, so by apply time
the facts must be correct. An atomic batch matches that flow. A stray bad tag
rejects the batch and names the bad fact, and the resend is safe.

The cost is that one bad operation blocks good ones. A client that wants to keep
the good writes must remove the bad operation and resend. For engram's small,
trusted user base and the migrate use case, that trade is worth the simpler
guarantee.

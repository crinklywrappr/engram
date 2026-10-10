# 18a: Allow a `src` correction

**What to build:** Claude can change a memory's `src` with an update, the same way it changes content, tags, and related. A `PUT /memories/:id`, or a batch update, that carries a new `src` replaces the src in place and reconfirms the fact. An update that omits `src` leaves the current src unchanged. The client doc stops saying a correction cannot carry `src`.

A memory still carries exactly one `src`, a lowercase kebab-case token. A malformed `src` on an update is a 400 at request coercion, the same rule a create already holds. A bare confirm, the empty update from ticket 17, carries no `src` and changes nothing.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] A `PUT /memories/:id` that carries a `src` replaces the memory's src, returns 200, and reads back with the new src.
- [x] A batch update that carries a `src` replaces the src in the same atomic batch.
- [x] An update that omits `src` leaves the current src unchanged.
- [x] A malformed `src` on an update is a 400 at coercion.
- [x] The engram-recall skill doc describes `src` as a field a correction can change.
- [x] Tests cover a PUT src change and a batch src change, each reading the new src back.

# 02: Constrain labels, src, and related to kebab tokens

**What to build:** Every label, every `src`, and every `related` value must be a
lowercase kebab-case token. The token must read as a valid Clojure keyword
literal. One predicate, `kebab?`, is the single rule for all three. A malformed
value is rejected before the write.

Step 1 finding (the documented keyword grammar). The Clojure reader reference
gives the grammar. A symbol begins with a non-numeric character and can contain
alphanumeric characters and `* + ! - _ ' ? < > =`. The characters `/`, `.`, and
`:` are special: `/` separates a namespace, `.` marks a class or a namespace, and
a name that begins or ends with `:` is reserved. A keyword is a symbol that
begins with a colon, so a keyword name follows the same rule, including the
non-numeric first character. The reference also notes the reader can accept
other characters later, and it does accept more in practice. engram validates
against the documented grammar, not the looser reader.

Step 2 result. Constrain that grammar to lowercase kebab-case. Drop the uppercase
letters. Drop every punctuation mark except the hyphen. Drop the `/` and `.`
namespace forms. What remains is a lowercase letter, then lowercase letters or
digits, in words joined by single hyphens. `kebab?` is
`[a-z][a-z0-9]*(?:-[a-z0-9]+)*`.

The first character must be a letter, because the grammar requires a non-numeric
start. So `3d` is rejected, together with `node.js`, `c++`, and `asp.net` from
the earlier broaden idea. A digit is fine after the first letter, for example
`clojure-1-12` or `d3`.

**Blocked by:** None (can start immediately).

**Status:** resolved (commit 3a9ebbc)

- [ ] `kebab?` is `[a-z][a-z0-9]*(?:-[a-z0-9]+)*`, with a code comment that cites
      the Clojure reader grammar and the lowercase-kebab constraint.
- [ ] `kebab?` accepts `clojure`, `datalevin`, `foo-bar-2`, `clojure-1-12`, and
      `d3`.
- [ ] `kebab?` rejects `Clojure`, `node.js`, `c++`, and `asp.net`. It also
      rejects `a_b`, `a/b`, `-x`, `x-`, `a--b`, `3d`, and a token with a space.
- [ ] The server applies `kebab?` to every label, to `src`, and to each
      `related` value. A malformed value is rejected before the write with a
      clear error, on the same 409 path as a bad label today.
- [ ] The `related` rule tests only the token shape. It does not require the
      referenced `src` to already exist, so the behavior in feedback item 5 still
      holds.
- [ ] Tests cover a valid and an invalid `src`, a valid and an invalid `related`
      value, and the label cases above.

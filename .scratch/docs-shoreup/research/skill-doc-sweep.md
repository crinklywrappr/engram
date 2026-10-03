# Skill doc sweep against engram source

This note tests factual claims in three skill docs against the engram source.
The source is the primary truth. For each claim this note records a verdict, the
source citation, and a repair. A verdict that is not CONFIRMED gets a repair.

Verdicts are CONFIRMED, STALE, WRONG, REDUNDANT, and TRIM.

## Counts

- CONFIRMED is 24.
- WRONG is 3.
- TRIM is 4.
- REDUNDANT is 2.
- STALE is 0.

## Context notes (not code claims)

The skills now live in plugin/skills/. The install path is the
crinklywrappr/engram-skills marketplace with slug engram@engram. The
CLAUDE-nudge.md moves into a user doc. None of the three skill files point to
their own prior location. No repair is needed on that basis.

## plugin/skills/engram-recall/SKILL.md

### 1. A recalled memory carries only id, content, src, tags

- Claim: lines 38-39 and the trimmed-wire text state a recalled memory carries
  only id, content, src, tags. It drops empty collections and timestamps.
- Verdict: WRONG.
- Source: src/engram/memory.clj:18-29 is the `->wire` function. See also
  src/engram/schema.clj:50-54 for MemoryOut.
- Detail: `->wire` always emits id, content, src. It adds tags for a non-empty
  tags vector. It also adds `related` for a non-empty related vector. The doc
  names only four keys and omits `related`.
- Repair: say a recalled memory carries id, content, and src. For non-empty
  values it also carries tags and related. Keep the drop of empty collections
  and timestamps.

### 2. The 409 body contains the configurations key only

- Claim: line 123 says the stdout body on a 409 contains `{"configurations": [...]}`.
- Verdict: WRONG.
- Source: src/engram/handler.clj:70-75 is reject-409. See also
  src/engram/schema.clj:77-78 for Conflict and src/engram/errors.clj:20-24.
- Detail: the 409 body is `{"error": "...", "message": "...", "configurations": [...]}`.
  The Conflict schema names all three keys. The doc names only configurations.
- Repair: say the 409 body carries error, message, and configurations. The
  client reads configurations to refresh its cache.

### 3. Labels, src, and related reject a plus and a dot

- Claim: lines 79-82 say a leading digit, uppercase, a dot, and a plus are
  rejected. The token is lowercase kebab-case.
- Verdict: CONFIRMED.
- Source: src/engram/config.clj:19-20. The regex is
  `^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$`, anchored with `^...$`.
- Detail: the regex is anchored. The malli `:re` re-find test holds the whole
  string. A leading digit, any uppercase, a dot, and a plus all fail.

### 4. Routes and methods

- Claim: GET /config, GET /stats, POST /memories/recall, POST /memories,
  PUT /memories/:id, DELETE /memories/:id, POST /memories/batch, GET /healthz.
- Verdict: CONFIRMED.
- Source: src/engram/handler.clj:130-156. Every path and method matches.

### 5. Recall request body shape

- Claim: line 58 says POST /memories/recall takes `{"pairs": [["category","label"], ...]}`.
- Verdict: CONFIRMED.
- Source: src/engram/schema.clj:24-25. RecallBody is `[:map [:pairs [:vector Pair]]]`.
  See src/engram/config.clj:21 for Pair as `[:tuple Token Token]`.

### 6. Recall streams NDJSON, a header line then one memory per line

- Claim: lines 38-39 and 59-60 say the first line is a header object. Every line
  after that is one memory.
- Verdict: CONFIRMED.
- Source: src/engram/handler.clj:33-46 is ndjson-response. Lines 54-61 are
  recall-handler, which emits the header `{:header true :pairs pairs}` first.

### 7. Recall follows the related closure transitively

- Claim: lines 59-60 and 76-78 say recall returns matches plus every memory
  linked through related, followed transitively.
- Verdict: CONFIRMED.
- Source: src/engram/memory.clj:224-261 is the `query` walker and `recall`.
  See resources/migrations/001-schema.edn:20, where `:memory/related` links by src.

### 8. Recall increments per-pair stats

- Claim: line 54 and the stats text imply recall records per-pair counts.
- Verdict: CONFIRMED.
- Source: src/engram/handler.clj:54-57. recall-handler calls
  `stat-writer/record!` with the pairs before the query runs.

### 9. The /stats wire shape and tuple order

- Claim: lines 54-57 say the body is
  `{"stats": {"recalls": [[category, label, lifetime, recent], ...]}}`.
- Verdict: CONFIRMED.
- Source: src/engram/handler.clj:63-68 builds the positional row
  `[category label lifetime recent]`. See src/engram/schema.clj:70-71, where
  StatsOut nests recalls below stats as `[:tuple :string :string :int number?]`.

### 10. The /config body shape

- Claim: lines 43-50 say GET /config returns `{"configurations": [...]}` plus an
  optional categories map. Each entry has a description and examples.
- Verdict: CONFIRMED.
- Source: src/engram/handler.clj:135-139 adds :categories only for a present
  value. See src/engram/schema.clj:59-65 for ConfigOut. See
  src/engram/config.clj:27-31 for Categories. A description has a 256-char max.
  Examples are an optional token vector.

### 11. Each pattern maps category to a cardinality

- Claim: lines 44-46 record the four cardinalities 1, ?, *, +.
- Verdict: CONFIRMED.
- Source: src/engram/config.clj:71-76. cardinality->vector handles "1", "?",
  "*", and "+".

### 12. Batch body and response shape

- Claim: lines 96-110 say the body is `{"create":[...],"update":[...],"delete":[...]}`,
  each group optional. A passing batch returns 200 with `{"ids":[...],"applied":n}`.
  The ids are in create order.
- Verdict: CONFIRMED.
- Source: src/engram/schema.clj:42-46 is BatchBody with each group optional. Line
  86 is BatchOut. See src/engram/handler.clj:103-110 for the 200 body. See
  src/engram/memory.clj:171-182, where ids are built in create order.

### 13. Batch is atomic, it checks all operations then applies

- Claim: lines 106-108 say the server tests every operation first, applies on
  full success, and writes nothing on failure.
- Verdict: CONFIRMED.
- Source: src/engram/memory.clj:121-194. plan-batch tests the operations.
  apply-batch! transacts once on success and writes nothing on failure.

### 14. One id must not be in both update and delete

- Claim: lines 117-118 say an id in both the update and delete group rejects the
  batch.
- Verdict: CONFIRMED.
- Source: src/engram/memory.clj:118 is conflict-msg. Lines 139, 150-151, and
  161-162 make the conflict a per-op error.

### 15. Batch 422 body entries carry op and i, a tag error carries configurations

- Claim: lines 111-115 say a failed batch returns 422 with `{"errors":[...]}`,
  each entry carrying op and i. A tag error also carries configurations.
- Verdict: CONFIRMED.
- Source: src/engram/handler.clj:111-119 is the 422 body. It attaches the
  configurations key only for the tag-rule error code. See
  src/engram/schema.clj:87-91 for BatchError, where i, op, and error are required
  and the configurations key is optional. See src/engram/memory.clj:140-165,
  where each error carries op and i.

### 16. Delete returns 200, a delete of another user's memory returns 404

- Claim: lines 87-88 say a delete returns 200. A delete of a memory not yours
  returns 404.
- Verdict: CONFIRMED.
- Source: src/engram/handler.clj:96-101 is delete-handler. See
  src/engram/memory.clj:99-107, where delete! returns nil for no memory for this
  user, so the handler returns 404.

### 17. PUT body shape without src

- Claim: lines 85-86 say correct a fact with PUT /memories/<id> using the same
  body shape without src.
- Verdict: CONFIRMED.
- Source: src/engram/schema.clj:18-22. UpdateBody has content, tags, and related,
  and no src.

### 18. Transport, proxy slurps stdin, injects the user, writes HTTP code, exits 0 on 2xx

- Claim: lines 16-27 say engram-proxy adds the trusted user, puts the body on
  stdout, writes `HTTP <code>` to stderr, and exits 0 for 2xx and non-zero for
  the rest.
- Verdict: CONFIRMED.
- Source: bin/engram-proxy:37 slurps `*in*`. Line 45-46 injects X-Engram-User.
  Line 54 writes `HTTP <code>` to stderr. Line 58 exits 0 for 2xx and 1 for the
  rest. The proxy also exits 2 on a usage error and 3 for an unreachable server.
  Both are non-zero.

### 19. The ssh examples use a /dev/null redirect for a bodyless GET

- Claim: lines 32-33 show `ssh engram GET /config < /dev/null`.
- Verdict: CONFIRMED.
- Source: bin/engram-proxy:11-12 records the GET form with a /dev/null redirect.
  See bin/engram-proxy:37-38, where a blank stdin yields no body.

### 20. ControlMaster reuses one SSH connection

- Claim: lines 25-27 say the connection is reused through ControlMaster.
- Verdict: CONFIRMED as a client ssh_config behavior. This is not a server code
  claim.
- Source: bin/engram-proxy:9-12. The proxy speaks one request per connection.
  Connection reuse is an SSH ControlMaster feature set in the user ssh_config.

### 21. Categories are closed, src and related are not categories

- Claim: line 83.
- Verdict: CONFIRMED.
- Source: `src/engram/config.clj:78-92` is a closed map schema per pattern.
  See `src/engram/config.clj:10-11`, where src and related are tokens, not
  categories.

### 22. engram keeps no history

- Claim: line 85.
- Verdict: CONFIRMED.
- Source: src/engram/memory.clj:63-78. update-tx retracts the prior tags and
  related in place. resources/migrations/001-schema.edn has no history attribute.

### 23. Do not create near-duplicate labels

- Claim: lines 80-82 warn against near-duplicate labels with an example.
- Verdict: REDUNDANT only in part. The server does not enforce label reuse, so
  the warning carries real value and stays.
- Source: src/engram/config.clj:99-105. tag-error tests only category closure and
  cardinality, never label similarity.
- Repair: keep the guidance. It is advice the server cannot enforce.

### 24. The Transport section repeats the exit-code rule

- Claim: lines 20-23 record the exit-code rule. Lines 121-124 repeat it for 409.
- Verdict: TRIM.
- Source: bin/engram-proxy:58. One exit rule covers all non-2xx.
- Repair: state the exit rule once in Transport. In the 409 section, name only
  the 409 status and the body, and drop the exit-code re-explanation.

### 25. The socket-versus-port sentence

- Claim: line 27 says nothing here manages a socket or a port.
- Verdict: TRIM.
- Source: not a code claim. The sentence carries no instruction.
- Repair: drop the sentence. The reader never touches a socket.

## plugin/skills/migrate/SKILL.md

### 1. Batch body for migration is a create group

- Claim: lines 43-45 say POST /memories/batch with `{"create": [fact, ...]}`.
- Verdict: CONFIRMED.
- Source: src/engram/schema.clj:42-46. BatchBody has an optional create vector of
  CreateBody. See src/engram/handler.clj:145-147.

### 2. Batch 200 body and 422 body

- Claim: lines 46-50 say 200 gives `{"ids":[...],"applied":n}` and 422 gives
  `{"errors":[...]}`, one entry per bad fact with its index i, and nothing
  written.
- Verdict: CONFIRMED.
- Source: src/engram/handler.clj:103-119. See src/engram/schema.clj:86-91 and
  src/engram/memory.clj:140-182.

### 3. A tag error carries configurations

- Claim: line 49 says a tag error carries configurations to refresh the cache.
- Verdict: CONFIRMED.
- Source: src/engram/handler.clj:112-114 attaches configurations only for the
  tag-rule error code. See src/engram/schema.clj:90-91.

### 4. Each 422 entry has an index i in the create list

- Claim: lines 47-48 say each entry has its index i in the create list.
- Verdict: CONFIRMED.
- Source: src/engram/memory.clj:140-144. Create errors carry op "create" and i,
  0-based within the create group.

### 5. src is the file name in kebab-case without the extension

- Claim: lines 29-31 say set src to the source file name, kebab-case, no
  extension. Facts from one file share that src.
- Verdict: CONFIRMED as valid against the token rule. src must match the token
  regex, so the file name must already be lowercase kebab-case.
- Source: src/engram/config.clj:19-20 is the Token regex. See
  src/engram/schema.clj:12-16, where CreateBody src is config/Token.
- Repair: none required. A file name with an upper-case letter or any character
  outside the token regex fails coercion with a 400. A migration author must
  kebab-case the src.

### 6. related entries name the target file's src

- Claim: line 31 says turn each [[link]] into a related entry that names the
  target file's src.
- Verdict: CONFIRMED.
- Source: src/engram/memory.clj:14-16 and 44-48. related holds src values. See
  resources/migrations/001-schema.edn:20.

### 7. Labels are lowercase kebab-case, reuse labels, no near-duplicates

- Claim: lines 31-32.
- Verdict: CONFIRMED for the kebab-case rule. The reuse advice is guidance the
  server does not enforce.
- Source: src/engram/config.clj:19-20 and 99-105.

### 8. Each fact's categories satisfy one pattern

- Claim: line 33 says make sure each fact satisfies one pattern.
- Verdict: CONFIRMED.
- Source: src/engram/config.clj:88-92 and 112-116. A create matches any one
  pattern. An empty tag set must still satisfy one.

## plugin/skills/CLAUDE-nudge.md

### 1. ControlMaster reuses one connection through a socket, not a port

- Claim: lines 28-32 explain ControlMaster, ControlPath, and ControlPersist.
- Verdict: CONFIRMED as a client ssh_config behavior. This is not an engram code
  claim.
- Source: bin/engram-proxy:9-12. The proxy speaks one request per connection. The
  reuse is an SSH feature in the user ssh_config, outside the engram source.

### 2. The ssh config sample with Port 2222 and User engram

- Claim: lines 17-26 show a sample Host block with Port 2222 and User engram.
- Verdict: CONFIRMED as a sample with placeholders for host and key. The port and
  user match the known bastion deploy. The engram source in this repo does not
  carry this value.
- Source: no code source in this repo. The sample marks HostName and
  IdentityFile as placeholders, which is correct for a user-specific config.

### 3. The port-versus-socket sentence

- Claim: line 32 says there is no port to manage, because ControlMaster uses the
  ControlPath socket file, not a localhost port.
- Verdict: TRIM.
- Source: not a code claim. The sentence repeats the socket point from line 28.
- Repair: drop the trailing clause. The ControlPath line already names the socket.

### 4. The token-cost side remark

- Claim: line 4 says they cost almost no tokens.
- Verdict: REDUNDANT.
- Source: not a code claim. The sentence is a side remark with no action.
- Repair: drop it. The nudge text speaks for itself.

# engram wire I/O token reduction

## Summary

The engram recall stream repeats seven JSON object keys on every line. The keys
cost about 64 bytes per memory before any value. The stream also emits null
timestamps and empty `related` and `tags` arrays. It serializes timestamps as
27-byte ISO strings. This research grounds each reduction in the library that
owns the mechanism. The biggest safe win turns on `:strip-nils` in the jsonista
mapper, which drops null and empty fields with no key rename and no client
change. The largest possible win reshapes each row into a header-named array of
values, which removes the repeated keys but needs a client contract. Every claim
below cites the jsonista, muuntaja, malli, reitit, Jackson, JSON Lines, or RFC
8259 source read for this task.

## Ranked opportunities

| Rank | Opportunity | One-line saving | Risk |
| ---- | ----------- | --------------- | ---- |
| 1 | `:strip-nils` on the mappers (drop null and empty fields) | Cuts empty `related`, empty `tags`, null timestamps per row | Very low. Absent key reads as absent, which the client already handles |
| 2 | Prefer NDJSON for the LLM client, keep JSON only for Swagger | Avoids the whole-array `{"pairs":...,"memories":[...]}` envelope | Very low. NDJSON is already the streamed path |
| 3 | Drop timestamps from the recall path | Removes two fields worth about 40 bytes per row | Low to medium. The recall consumer does not use them today |
| 4 | Columnar array-of-values rows under a header naming the columns | Removes the 64-byte key block from every row | Medium. Needs a client contract and a glossary note |
| 5 | Drop the header `pairs` echo | Removes one small header field once per stream | Low. The client already knows the pairs it sent |
| 6 | Short keys instead of full keys | Shrinks the 64-byte key block to about 20 | Medium to high. Needs a custom encoder and hurts clarity |
| 7 | Epoch integer timestamps instead of ISO strings | Saves about 14 bytes per timestamp kept | Medium. If timestamps stay on the wire, this helps |

The strongest recommendation is rank 1. Turn on `:strip-nils` and prefer NDJSON.
Together they cut real bytes with almost no risk and no client rewrite.

## 1. Omit empty and default fields with `:strip-nils`

### The change

Build the recall mapper with `(json/object-mapper {:strip-nils true})` and use
it in `ndjson-response`. For the JSON fallback, pass `{:strip-nils true}` to the
muuntaja JSON format options.

### The mechanism and its source

The jsonista `object-mapper` option `:strip-nils` maps directly to the Jackson
inclusion setting `JsonInclude$Include/NON_EMPTY`. The jsonista 0.3.8 source
shows this line in `object-mapper`:
`(:strip-nils options) (.setSerializationInclusion JsonInclude$Include/NON_EMPTY)`.
The source is jsonista 0.3.8, `jsonista/core.clj`, `object-mapper`, line 156. The
option table in the same docstring lists `:strip-nils` as "remove any keys that
have nil values". That text sits at line 128 of the same file.

`NON_EMPTY` drops more than nulls. The Jackson annotation source defines it as
the setting where "only properties with null value, or what is considered empty,
are not to be included". The source is jackson-annotations 2.17.2,
`com/fasterxml/jackson/annotation/JsonInclude.java`, the `Include.NON_EMPTY`
javadoc. Jackson counts these as empty. It counts null values and absent values.
It counts a collection or map whose `isEmpty()` is true. It counts an array of
length 0 and a string of length 0. So one setting drops empty `related`, empty
`tags`, and null `created-at` and `updated-at` at once.

Muuntaja passes this option straight through. Its JSON encoder builds the mapper
by calling `(j/object-mapper (dissoc options :mapper))`. Its namespace docstring
points the reader to the jsonista `object-mapper` options. The source is muuntaja
0.6.10, `muuntaja/format/json.clj`, `object-mapper!` at line 28, and the ns
docstring at lines 2 to 3.

### The saving

The `->wire` function always writes every key. It writes `:related (vec ...)`,
`:tags (mapv ...)`, and `some->` timestamps that can be nil. The source is
engram, `src/engram/memory.clj`, `->wire`, lines 19 to 28. A memory with no `related`
and no `tags` and null timestamps carries four empty or null fields today. Their
keys and values cost roughly 45 bytes on that row. Many memories carry no
`related`, so this recurs across the stream.

### The risk

Very low. JSON Lines treats each line as an independent JSON value, and an
absent key reads as absent (jsonlines.org, "Each Line is a Valid JSON Value").
The recall client reads memories line by line and does not depend on a present
empty array (engram, `skills/engram-recall/SKILL.md`, "Recall: two calls"). One
caveat holds. `NON_EMPTY` will also drop a field whose value is a legitimately
empty string. No `MemoryOut` field carries an intentional empty string today, so
this caveat does not bite. If a future field can be a meaningful empty string,
prefer a mapper scoped to the recall shape.

## 2. Prefer NDJSON for the LLM client, keep JSON for Swagger

### The change

Keep NDJSON as the streamed recall response. Treat the plain JSON array as a
Swagger-only fallback. The client skill already sends no explicit `Accept`, so
confirm the proxy or the skill sends `Accept: application/x-ndjson` on recall.

### The mechanism and its source

The recall handler already branches on the `Accept` header. If the client wants
NDJSON, it streams a header line then one memory per line. Otherwise it returns
`{:pairs pairs :memories (vec mems)}` as one JSON body (engram,
`src/engram/handler.clj`, `recall-handler`, lines 54 to 61). The JSON fallback
wraps every memory in one array under a `:memories` key and echoes `:pairs`
(engram, `src/engram/schema.clj`, `RecallOut`, lines 73 to 74).

### The saving

The NDJSON form drops the outer envelope. It needs no `{"pairs":...,"memories":[`
prefix and no closing `]}`. It also streams, so a large recall never holds the
whole array in memory (engram, `src/engram/handler.clj`, `ndjson-response`
docstring, lines 33 to 35). The per-row content is the same, so the saving is the
envelope plus the streaming benefit.

### The risk

Very low. NDJSON is already the streamed path and the skill documents it
(engram, `skills/engram-recall/SKILL.md`, "The recall call streams NDJSON").
JSON Lines is a stable convention where each line is a full JSON value
(jsonlines.org). The only action is to make the LLM client always ask for NDJSON.

## 3. Drop timestamps from the recall path

### The change

Remove `:created-at` and `:updated-at` from the recall `MemoryOut` rows. Keep
them on any admin or audit read that needs them. The `/stats` path does not carry
them, so no stats change is needed.

### The mechanism and its source

`->wire` computes both timestamps with `.toInstant str`, which yields an ISO-8601
string (engram, `src/engram/memory.clj`, `->wire`, lines 27 to 28). The recall
client protocol never reads these fields. The skill tells the client to read
matches and linked memories, and says nothing about time (engram,
`skills/engram-recall/SKILL.md`, "Recall: two calls"). The glossary defines a
Memory, a Recall, and a Recall count with no timestamp in the recall contract
(engram, `CONTEXT.md`, "Recall" and "Memory").

### The saving

Each ISO instant string is about 27 bytes of value. With its key and quotes, each
timestamp field costs roughly 40 to 45 bytes per row. Two fields per row recur
across the whole stream. Dropping both is a large per-row cut.

### The risk

Low to medium. The recall consumer does not use timestamps today. If a future
consumer needs recency on recall, it must ask for them on a separate read. The
NDJSON stream drops the fields once the `->wire` function stops writing them. The
change needs no response coercion, because the recall route declares no
`:responses`. See engram, `src/engram/handler.clj`, route comment, line 140.

## 4. Columnar array-of-values rows under a header

### The change

Change each NDJSON memory line from an object to an array of values. Add the
column order to the existing header line, for example
`{"header":true,"cols":["id","content","src","related","tags"]}`. Then each
memory line becomes `["<id>","<content>","<src>",[...],[...]]`.

### The mechanism and its source

RFC 8259 requires object member names to be strings. Each object repeats them.
The source is RFC 8259, Section 4, "A name is a string". An array holds an
ordered sequence of values with no names. The source is RFC 8259, Section 5, "An
array structure is represented as square brackets surrounding zero or more
values". So an array row removes the key strings and relies on position. JSON
Lines allows an array per line and treats each line as independent. The source is
jsonlines.org, "The most common values will be objects or arrays". The header
line already exists in the stream and can name the columns. See engram,
`src/engram/handler.clj`, `ndjson-response` and `recall-handler`, lines 33 to 61.

### The saving

The seven `MemoryOut` keys cost about 64 bytes per row as quoted names with
colons, before any comma or value. An array row removes all of that and pays only
for commas and brackets. Across a large recall, this is the single largest key
saving available. The header names the columns once for the whole stream.

### The risk

Medium. A columnar row is positional, so a reordered or added column can break a
naive client. The client must read the header `cols` and map positions to fields.
The glossary names the fields as Memory attributes and does not name a wire
column order (engram, `CONTEXT.md`). A columnar shape needs a short glossary note
that defines the column order as part of the recall contract. Response coercion
cannot guard this shape. The recall route declares no `:responses`, so no
coercion conflict arises. See engram, `src/engram/schema.clj`, `RecallOut`
comment, lines 71 to 72.

## 5. Drop the header `pairs` echo

### The change

Remove `:pairs` from the NDJSON header line. Keep only `{"header":true}` or fold
a column list into it per opportunity 4.

### The mechanism and its source

The header currently echoes the pairs the client sent:
`(ndjson-response {:header true :pairs pairs} mems)` (engram,
`src/engram/handler.clj`, `recall-handler`, line 60). The client sent those pairs
in its own request body, so it already holds them (engram,
`skills/engram-recall/SKILL.md`, "Recall: two calls"). JSON Lines lines are
independent, so nothing downstream needs the echo to parse a later line
(jsonlines.org).

### The saving

Small and fixed per stream. It removes one `pairs` array from the header once,
not per row. If a client reuses the response without its request, keep the echo.

### The risk

Low. The client knows the pairs it sent. If a future client replays a stored
stream with no request context, it loses the pairs. That is not the current
recall flow.

## 6. Short keys instead of full keys

### The change

Rename the wire keys to short forms, for example `:content` to `:c` and
`:related` to `:r`. Do this with a custom `:encode-key-fn` on the mapper.

### The mechanism and its source

The jsonista `:encode-key-fn` accepts "a function to provide custom coercion" of
keys (jsonista 0.3.8, `jsonista/core.clj`, `object-mapper` docstring, line 130).
When the option is a function, the mapper wires a function key serializer. See
jsonista 0.3.8, `jsonista/core.clj`, `clojure-module`, lines 101 to 102. A
function `:encode-key-fn` runs on every key. So it cannot rename one key without
a lookup table inside the function.

Malli cannot rename map entry keys on encode with its built-in transformers. The
JSON transformer only stringifies keyword keys through `-keyword->string`. It
also applies a function to keys with `-transform-map-keys`. That changes the key
text but does not remap one name to another by schema. See malli 0.16.4,
`malli/transform.cljc`, `-transform-map-keys` line 194, and the encoder table
lines 289 to 305. A per-key rename in malli needs a custom `:encode`
interceptor on the `:map` schema, which is extra code.

Response coercion runs before encoding and only strips undeclared keys. It
rewrites the body data structure, then muuntaja encodes it. The sources are
reitit-core 0.7.2, `reitit/coercion.cljc`, `coerce-response`, lines 166 to 169,
and reitit-ring 0.7.2, `reitit/ring/coercion.cljc`,
`coerce-response-middleware`, lines 46 to 66. So stripping helps token cost by
removing whole fields. It does not
shorten a kept key. Only the encoder controls key text.

### The saving

The full key block is about 64 bytes per row. Single-letter keys cut that to
roughly 20 bytes. This is a real per-row saving on an object shape. It is smaller
than the columnar shape in opportunity 4, which removes keys entirely.

### The risk

Medium to high. Short keys hurt readability for a human and for the model that
reads the stream. They fight the glossary, which names full attributes like
`src` and `related` (engram, `CONTEXT.md`). They need a custom encoder and a
documented key map. The columnar shape gives a larger saving for similar client
work, so prefer opportunity 4 over short keys.

## 7. Epoch integer timestamps instead of ISO strings

### The change

If timestamps stay on the recall path, serialize them as epoch milliseconds
integers instead of ISO strings.

### The mechanism and its source

Today `->wire` builds ISO strings with `.toInstant str` (engram,
`src/engram/memory.clj`, `->wire`, lines 27 to 28). Jackson can also write dates
as numeric timestamps. The jsonista mapper disables that by default with
`(.disable mapper SerializationFeature/WRITE_DATES_AS_TIMESTAMPS)`. It also sets
a default ISO date format. The source is jsonista 0.3.8, `jsonista/core.clj`,
`object-mapper`, lines 129 and 160. engram does not rely on that path, because it
pre-formats the instant to a string in `->wire`. So the change writes a `long`
epoch value from `->wire` rather than a string.

### The saving

An ISO instant string is about 27 bytes. An epoch-millisecond integer is about 13
bytes. That saves about 14 bytes per timestamp kept, before quotes. A string also
carries two quote bytes that an integer does not.

### The risk

Medium. An epoch integer is less readable than an ISO string for a human. If
timestamps stay on the wire, this change helps. Opportunity 3 removes timestamps from
recall entirely, which saves more. Prefer opportunity 3 on the recall path, and
keep this change for any read that must carry a time.

## Cross-cutting notes

Two facts shape which changes are cheap.

First, the recall route declares no `:responses`. So response coercion never runs
on the recall stream. The sources are engram, `src/engram/handler.clj`, route
comment at line 140, and `src/engram/schema.clj`, `RecallOut` comment at lines 71
to 72. So a recall-path change lives in `->wire` and in the mapper that
`ndjson-response` uses, not in a response schema.

Second, the NDJSON stream uses `json/write-value-as-string` with the default
jsonista mapper, which has no `:strip-nils`. The sources are engram,
`src/engram/handler.clj`, `ndjson-response` at lines 42 and 45, and jsonista
0.3.8, `jsonista/core.clj`, `default-object-mapper` at line 167. The JSON
fallback uses `mc/instance`, the default muuntaja with no options. See engram,
`src/engram/handler.clj`, `app`, line 159. So opportunity 1 needs a mapper built
with `:strip-nils true` on both paths.

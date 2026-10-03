# Ticket 10 research: time-to-first-token consumption of a subprocess stream in Claude Code

> Home of this note: the repo keeps tickets under `.scratch/review-fixes/issues/`.
> There is no existing research-notes convention, so this `.scratch/review-fixes/research/`
> subfolder is the sensible home for findings that back a ticket. This note records
> that choice.

Investigated against primary sources only: the official Claude Code documentation
(`code.claude.com/docs`, which is where `docs.claude.com/en/docs/claude-code`
now 301-redirects). Every claim below is followed by its source URL and section.
Where wording matters, the docs are quoted verbatim.

## Summary answer

A **foreground** Bash tool call does not hand the model any output until the
command finishes. Claude Code streams the command's output to a working file
while it runs, then "when the command finishes, Claude Code reads the output
back from that file." So a single `ssh engram POST /memories/query` foreground
call gives Claude nothing until the `ssh` process exits. There is no way to read
its stream mid-call.

Background execution exists (`run_in_background: true`), and its output is written
to a file that Claude reads with the `Read` tool. But `Read` returns file contents
from the start, not "new since last read," and the model only sees a background
task's growing output when it *chooses* to poll it with another tool call. Each
such poll is a separate turn.

There is one purpose-built streaming tool: **`Monitor`**. It runs a command in
the background and "feeds each output line back to Claude" as an event that lands
mid-conversation, so Claude reacts to lines as they arrive. This is the closest
thing to genuine time-to-first-token consumption of a subprocess stream. It is the
"tail -f into the conversation" mechanism.

On token cost: any incremental-consumption pattern costs *more* tokens than one
read after completion, because Claude Code "sends your full conversation with
every request, and each time Claude uses tools it sends another request carrying
that batch of tool results." More events or polls means more request round-trips,
each re-sending the whole (growing) context.

Bottom line: there **is** a real streaming mechanism (`Monitor`), so the honest
answer to ticket 10 is not "impossible." It is "possible via `Monitor`
(or background run + repeated `Read` polling), at extra token/latency cost, and
only worth it when first-memory latency actually matters." Because a streaming
consumer does exist, ticket 09 (server flushes each NDJSON line early) delivers
real value to a Claude client, not only to non-Claude consumers.

---

## Q1. Can Claude Code read a live, growing stream (tail -f)?

### 1a. Foreground Bash: incremental during the call, or only on exit?

Only on exit. The output is streamed to a file *by the harness*, but it is read
back and delivered to the model only after the process finishes:

> "Claude Code streams a command's output to a working file as the command runs;
> a command whose output passes 5 GB is killed. **When the command finishes,
> Claude Code reads the output back from that file**, up to the read-back window
> described below."

Source: Bash tool behavior > Output limits,
https://code.claude.com/docs/en/tools-reference#bash-tool-behavior

So within a single foreground call the model gets one tool result, produced after
the command exits. There is no mid-call incremental delivery to the model. (The
ticket 10 text already assumed this; the docs confirm it.)

Auto-backgrounding on timeout is the one twist: if a foreground command hits its
timeout without finishing, the harness moves it to the background rather than
killing it, and the result reports "the task ID and the path of the file the
output is being written to." That converts the call into the background case
below, it does not give incremental foreground delivery.

Source: Bash tool behavior > Background commands,
https://code.claude.com/docs/en/tools-reference#bash-tool-behavior

### 1b. Does run_in_background exist, and does it stream output somewhere readable?

Yes, it exists, and output goes to a file:

> "For long-running processes such as dev servers or watch builds, Claude can set
> `run_in_background: true` to start the command as a background task and continue
> working while it runs. List and stop background tasks with `/tasks`."

Source: Bash tool behavior > Background commands,
https://code.claude.com/docs/en/tools-reference#bash-tool-behavior

The background command's output is written to a file whose path the harness
reports (see the timeout-move result text quoted in 1a, "the path of the file the
output is being written to"). The harness does not push that growing output into
the model on its own. The model must issue a tool call to read the file.

### 1c. Is there a BashOutput tool (or equivalent)? Incremental or whole-buffer? Pollable?

There is **no tool named `BashOutput`** in the current tools reference. The
documented mechanisms are:

- **`TaskOutput`** — retrieves output from a background task, and is
  **deprecated in favor of `Read` on the task's output file path**:
  > "Retrieves output from a background task. Deprecated in favor of `Read` on the
  > task's output file path."

  Source: Tools reference (tool table),
  https://code.claude.com/docs/en/tools-reference

- **`Read`** — the current, non-deprecated way to read a background task's output.
  `Read` "Reads the contents of files"; it reads the file (from the start / by
  offset+limit), so it returns the file contents, **not** a "new since last read"
  delta. Reading a growing output file again re-reads the file, it does not tail
  only the new bytes.

  Source: Tools reference (tool table) and Read tool behavior,
  https://code.claude.com/docs/en/tools-reference

  Polling: because the background command "keeps running after a final response"
  (for main-conversation and background-subagent commands), Claude can call `Read`
  on the output file repeatedly across turns while the command runs. So repeated
  polling is possible, but each poll is a separate `Read` tool call and re-reads
  the file rather than receiving only the incremental tail.

  Source: Bash tool behavior > Background commands,
  https://code.claude.com/docs/en/tools-reference#bash-tool-behavior

- **`Monitor`** — the purpose-built streaming tool, and the real answer to "can
  Claude tail -f a stream?":
  > "Runs a command in the background and feeds each output line back to Claude,
  > so it can react to log entries, file changes, or polled status
  > mid-conversation. Can also open a WebSocket and treat each incoming message as
  > an event."

  Source: Tools reference (tool table),
  https://code.claude.com/docs/en/tools-reference

  Its dedicated section confirms per-line delivery:
  > "For most watches, Claude writes a small script, runs it in the background, and
  > **receives each output line as it arrives**. ... You keep working in the same
  > session and Claude interjects when an event arrives."

  Source: Tools reference > Monitor tool,
  https://code.claude.com/docs/en/tools-reference#monitor-tool

  So `Monitor` *is* incremental (line by line) and does not require the model to
  poll: the harness pushes each line to the model as an event. Constraints worth
  noting for engram: every watch has a deadline of "5 minutes by default, at most
  30 minutes, and at most 10 minutes in a non-interactive run given a single
  prompt with `-p`." A memory fetch is short-lived, so the deadline is not a
  practical limit for this use.

  Source: Tools reference > Monitor tool,
  https://code.claude.com/docs/en/tools-reference#monitor-tool

## Q2. Token cost: does incremental reading cost more than one read after completion?

Yes, incremental consumption costs more tokens. The mechanism is the agent loop:
the whole conversation, including all prior tool results, is re-sent on every
model request, and every tool use is its own request carrying that batch of
results.

> "Long context: Claude Code sends your full conversation with every request, and
> **each time Claude uses tools it sends another request carrying that batch of
> tool results.** With prompt caching, Claude Code re-reads that history at the
> cached token rate ..."

Source: Manage costs > Why usage climbs in a long session,
https://code.claude.com/docs/en/costs#why-usage-climbs-in-a-long-session

> "Claude's context window holds your conversation history, file contents, command
> outputs, ... As you work, context fills up."

Source: How Claude Code works > The context window,
https://code.claude.com/docs/en/how-claude-code-works#the-context-window

Why incremental costs more:

- **Per-poll / per-event round-trip overhead.** With foreground-after-completion,
  the stream is one tool result in one turn. With `Monitor` (one event per line)
  or with repeated `Read` polling, each line/poll is a separate model turn. Every
  such turn re-sends the full conversation as input. N events therefore trigger up
  to N extra request round-trips, each paying for the whole accumulated context.

- **Content is not counted once; the growing context is re-read each turn.** The
  memory lines land in the transcript and stay there, and every later request
  re-sends them. Prompt caching softens this (cached tokens are billed at the
  cheaper cached rate), but a fresh line at the tail is new content past the cached
  prefix, and each new turn still re-processes the conversation.

  Source: Manage costs > Why usage climbs in a long session (prompt-cache note),
  https://code.claude.com/docs/en/costs#why-usage-climbs-in-a-long-session

- **`Read` polling additionally re-reads overlap.** Since `Read` returns file
  contents rather than a since-last-read delta, naive re-reads of a growing file
  re-ingest lines already seen unless offset bookkeeping is used. `Monitor` avoids
  that overlap (one line = one event), so `Monitor` is the cheaper of the two
  incremental options, but still costs more turns than a single completion read.

Net: a single read after completion is the cheapest. Any time-to-first-token
pattern trades tokens (and per-turn latency) for earlier access to the first
memories.

## Q3. Bottom line for ticket 10

Honest conclusion: there **is** a genuine streaming-consumption mechanism, so this
is not "not really possible."

- Foreground single call: no incremental consumption. One tool result after exit.
  (Q1a.)
- Background + `Read` polling: works, but `Read` is whole-file not delta, each poll
  is a turn, extra token/latency cost. (Q1b, Q1c, Q2.)
- `Monitor`: the "super good" mechanism for this shape of problem. The harness runs
  the fetch in the background and pushes each NDJSON line to Claude as it arrives,
  mid-conversation, with no polling by the model. This is real time-to-first-token
  consumption of a subprocess stream. (Q1c.) Cost: more turns, hence more tokens,
  than a single completion read. (Q2.)

So the realistic recommendation for ticket 10 is: "yes, time-to-first-token is
achievable, via `Monitor` (preferred) or background run + incremental `Read`
polling, at extra token/latency cost." It is worth doing only where the first
memories are useful before the whole set arrives, i.e. where the fetch is large
or slow enough that first-memory latency matters to the user or to a downstream
decision. For a small, fast memory query the extra turns likely cost more than the
latency they save.

## Q4. Does ticket 09 (server flushes each NDJSON line early) give real value to a Claude client?

Yes. Because a streaming consumer (`Monitor`) genuinely exists, the server-side
early flush has a real client on the Claude side, not only non-Claude consumers.

`Monitor` "receives each output line as it arrives"
(https://code.claude.com/docs/en/tools-reference#monitor-tool). That guarantee is
only meaningful if the line actually reaches the client early. Ticket 09 makes
engram-proxy flush each NDJSON line as the server produces it instead of holding
it in `System/out`'s buffer until the end. Without ticket 09, `Monitor` would
receive nothing until the proxy's final flush, collapsing the streaming win. So
ticket 09 is the precondition that lets ticket 10's `Monitor` (or `Read`-polling)
pattern deliver early first-memory latency to a Claude client.

Caveat that ties the two together: ticket 09's value to Claude is contingent on
ticket 10 choosing a streaming consumer. If the skill instead uses a plain
foreground call (cheapest, no early read), ticket 09's early flush buys the Claude
client nothing, because a foreground call only reads back the whole file after the
process exits (Q1a). So the tickets should be decided together: adopt ticket 09
if and only if the ticket 10 skill will consume the stream with `Monitor`
(or incremental `Read`).

---

## Recommendation

- **Ticket 10:** Time-to-first-token is achievable. Prefer the `Monitor` tool: it
  runs the fetch in the background and pushes each NDJSON line to Claude as it
  arrives, with no model-side polling and no whole-file re-reads. Background run +
  incremental `Read` polling is the fallback, but it is whole-file (not delta) and
  costs more overlap. Document in the engram-recall skill that the streaming path
  costs extra tokens/turns versus a single foreground read, and reserve it for
  fetches large or slow enough that first-memory latency matters. For small, fast
  queries, a plain foreground `ssh ... POST /memories/query` call is the cheaper
  default.

- **Ticket 09:** Worth doing *iff* ticket 10's skill will use a streaming consumer
  (`Monitor` or incremental `Read`). Under that pairing the early per-line flush
  is what makes each line reach the Claude client early, so it provides real value
  to a Claude client and is not merely for non-Claude consumers. If ticket 10 ends
  up using a plain foreground call, ticket 09 gives the Claude client no benefit
  (the whole file is only read back after exit). Decide the two together.

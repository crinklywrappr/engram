---
name: review
description: >-
  Revalidate the stale memories you lean on in engram. Use on demand to walk the
  stale list in confirm-priority order and keep, correct, or delete each memory.
  Works four memories at a time and writes one batch per page.
---

# review

Revalidate the stale memories in engram. A stale memory is one overdue for
reconfirmation. The skill walks the stale list in confirm-priority order, highest
first, and presents each memory for a decision. The user keeps, corrects, or
deletes each one. The skill works four memories at a time and writes one batch per
page.

## Transport

Every call is `ssh engram <METHOD> <PATH>`, with any request body on stdin. A GET
carries no body, so close stdin with `< /dev/null`.

```bash
ssh engram GET /memories/stale < /dev/null
ssh engram GET /memories/<id>  < /dev/null
cat batch.json | ssh engram POST /memories/batch
```

## Step 1: read the stale memories once

Run `ssh engram GET /memories/stale < /dev/null`. The response is an NDJSON stream.
The first line is a header object. Every line after it is one stale memory, in
confirm-priority order, highest first. Each memory carries `id`, `content`, `src`,
and `freshness`. Non-empty `tags` and `related` appear too. Read the whole stream
one time.

Do not run the stale route again during the run. A memory you confirm or correct
moves back toward fresh, so it drops off the next run on its own. A second read
renumbers the list under the user.

## Step 2: prepare a page of four

Work from the top of the list. Take the next four memories as one page. For each
memory, decide whether you can propose a change. Base a proposed change only on
what you can observe this session: the conversation, the project and its code, and
the memories you already recalled. When you find no evidence the fact drifted,
propose no change for that memory.

A proposed change is always one in-place correction to the one memory. It is never
a split or a merge. Keep it small. The user reaches a split or a merge through the
free-text path in Step 3.

## Step 3: present the page as a selection menu

Present the four memories as one prompt with the question tool. Give one question
per memory. Show the memory content and its freshness in the question. Offer these
options for each memory:

1. the proposed change
2. keep
3. delete
4. skip

The question tool adds an "Other" choice on its own. The user types free text
there. When you have no proposed change for a memory, offer only keep, delete, and
skip. The "Other" choice still carries the free-text path.

The user answers each question and submits the page once. A question the user
dismisses, or a skip, leaves that memory stale. The skill asks for no further
approval, because each menu choice is itself the decision.

## Step 4: map the page to one batch

Fold the four decisions into one batch. The batch is a map of grouped operations:

```json
{"create": [ ... ], "update": [ ... ], "delete": [ "<id>", ... ]}
```

Map each decision:

- Keep becomes an update that carries only the `id`. A bare update confirms the
  memory. It stamps the confirmation and leaves the content untouched.
- The proposed change becomes an update that carries the `id` and the new
  `content`. When the user also changes tags or related, the update carries them
  too. An update replaces the whole set it names. When you change tags, send the
  full `tags` set.
- Delete becomes an entry in the delete group.
- A dismissed question or a skip contributes nothing. The memory stays stale.

The free-text path is the open one. Read the user's words and map them to an
action on this memory: a correction, a keep, a delete, a split, or a merge. A
split is a delete of this memory plus new creates under one src. A merge is
several deletes plus one create. Two rules bind you, and you defend them: the
batch payload schema, and the rule that every memory is one atomic fact. Otherwise
accommodate the user.

When free text names a memory outside the page, read its current state with
`ssh engram GET /memories/<id> < /dev/null` before you build the replacement. On a
merge, repoint only the `related` of the memories you already edit in the batch.
On a delete, repoint nothing, because a `related` link to a missing src finds
nothing on recall and raises no error.

## Step 5: send the page, then advance

Write the page's batch to a file and send it. `POST /memories/batch` with the file
on stdin. On `HTTP 200` the body is `{"ids":[...],"applied":n}`, and the page is
done. On `HTTP 422` the body is `{"errors":[...]}`, and the server wrote nothing,
because the batch is atomic. A tag error carries `configurations`. Replace your
cached configuration, fix the named entries, and resend the whole page.

Then advance to the next four memories. When the user stops, or when the stale
list is exhausted, the run ends. The skill sets no priority floor. It walks down
even to the memories that score zero.

## Notes

- One `/batch` call per page covers the keeps, the corrections, and the deletes
  together, because a bare update confirms.
- A keep is cheap and reversible on the next run. A correction and a delete are
  not, because engram keeps no history. Show a correction or a delete plainly in
  the menu before the user submits the page.

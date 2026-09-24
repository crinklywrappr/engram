# Issue tracker: Local Markdown

Issues and specs for this repo live as markdown files in `.scratch/`.

## Conventions

- One directory for each feature: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- One file holds each ticket, at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`. Do not combine tickets into one file.
- A `Status:` line near the top of each issue file records the triage role. See `triage-labels.md` for the role strings.
- Comments and history go at the bottom of the file, below a `## Comments` heading.

## When a skill says "publish to the issue tracker"

If the `.scratch/<feature-slug>/` directory does not exist, make it. Make a new file in that directory.

## When a skill says "fetch the relevant ticket"

Read the file at the named path. The user usually gives you the path or the issue number.

## Wayfinding operations

`/wayfinder` uses these operations. The map is a file. It holds one child file for each ticket.

- Map (`.scratch/<effort>/map.md`): the Notes, Decisions-so-far, and Fog body.
- Child ticket (`.scratch/<effort>/issues/NN-<slug>.md`): numbered from `01`, with the question in the body. A `Type:` line records the ticket type (`research`, `prototype`, `grilling`, or `task`). A `Status:` line records `claimed` or `resolved`.
- Blocking: a `Blocked by: NN, NN` line near the top. When every file it lists is `resolved`, the ticket is unblocked.
- Frontier: scan `.scratch/<effort>/issues/` for files that are open, unblocked, and unclaimed. The lowest number wins.
- Claim: set `Status: claimed` and save before any work.
- Resolve: append the answer below an `## Answer` heading. Set `Status: resolved`. Append a short pointer to the Decisions-so-far list in `map.md`. The pointer is a summary and a link.

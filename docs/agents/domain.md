# Domain Docs

This file tells the engineering skills how to read this repo's domain documentation before they explore the codebase.

## Before exploring, read these

- `CONTEXT.md` at the repo root, or
- `CONTEXT-MAP.md` at the repo root. When this file is there, it points at one `CONTEXT.md` for each context. Read each `CONTEXT.md` related to the topic.
- `docs/adr/`: read the ADRs that touch the area you are about to work in. In a multi-context repo, also read `src/<context>/docs/adr/` for decisions that belong to one context.

If one of these files is missing, continue without a word. Do not flag the missing file. Do not offer to make it first. When terms or decisions get resolved, the `/domain-modeling` skill makes these files. You reach that skill through `/grill-with-docs` and `/improve-codebase-architecture`.

## File structure

Single-context repo (most repos):

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

Multi-context repo (a `CONTEXT-MAP.md` at the root):

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← system-wide decisions
└── src/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← context-specific decisions
    └── billing/
        ├── CONTEXT.md
        └── docs/adr/
```

## Use the glossary's vocabulary

Your output can name a domain concept in an issue title, a refactor proposal, a hypothesis, or a test name. Use the term as `CONTEXT.md` defines it. Do not change to a synonym that the glossary avoids.

The glossary can lack the concept you need. That is a signal. Either you invent language the project does not use, so reconsider, or there is a real gap, so note it for `/domain-modeling`.

## Flag ADR conflicts

If your output contradicts an ADR, say so. Do not override the ADR without a word.

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_

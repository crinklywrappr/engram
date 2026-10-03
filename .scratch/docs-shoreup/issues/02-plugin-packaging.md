# 02: Ship the skills as a Claude plugin from a thin repo

**What to build:** The two skills install through the Claude Code `/plugin` command from a small dedicated repo. A marketplace add then clones only the plugin, not the whole engram project. A user adds the thin `engram-skills` marketplace, installs the plugin, and the `engram-recall` and `migrate` skills load. The skills stay in the engram monorepo as the source of truth, and a publish step mirrors them to the thin repo.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

Background: a `marketplace add` git-clones the repo that holds `marketplace.json`. When that file lives in the engram repo, the whole project clones. A thin dedicated repo keeps the clone small.

- [ ] The monorepo groups the plugin under one directory, `plugin/`. It holds `plugin/.claude-plugin/plugin.json`, `plugin/.claude-plugin/marketplace.json`, and `plugin/skills/` with the two skills. One subtree prefix then captures the whole plugin.
- [ ] The skills stay in the monorepo as normal files, so the server and the skills share one history.
- [ ] The marketplace lives in a thin repo, `crinklywrappr/engram-skills`. A `marketplace add crinklywrappr/engram-skills` clones only the plugin.
- [ ] The plugin `source` stays `"./"`, because the thin repo root is both the marketplace and the plugin root.
- [ ] The install slug stays `engram@engram`, because the names inside `marketplace.json` and `plugin.json` do not change.
- [ ] A local publish runs `git subtree push --prefix=plugin skills main`, where `skills` is a remote for the thin repo.
- [ ] A dedicated GitHub workflow publishes on a push to master that touches `plugin/**`. The job checks out `engram-skills`, copies the `plugin/` contents into it, commits, and pushes. It uses a deploy key or a PAT with write access to `engram-skills`, because the default token reaches only the engram repo.
- [ ] The thin repo is publish-only. No one commits to it directly, so each publish stays a fast-forward.
- [ ] The `CLAUDE-nudge.md` guidance still fits the plugin install path, or the ticket notes what changes.
- [ ] The two skills load in a fresh session after a plugin install.

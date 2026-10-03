# 02: Make the migrate preview a JSON batch file in the project dir

**What to build:** The `migrate` skill writes its dry-run preview as a JSON file.
The file is already the batch request body. It lives in the project's Claude
directory, not in the project tree.

Two changes to the preview, both in the `migrate` skill:

- Format: the preview is JSON in the `POST /memories/batch` shape
  (`{"create": [ fact, fact, ... ]}`), not EDN. On approval the same file becomes
  the batch body, so no EDN-to-JSON transform sits between review and the batch
  call.
- Location: the preview lives at `~/.claude/projects/<project-slug>/`, not under
  the project's `.scratch`. The project slug is the one Claude Code already uses
  for the project (the munged project path).

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] The `migrate` skill tells Claude to write the preview as JSON in the
      `{"create": [...]}` batch shape.
- [ ] The preview path is `~/.claude/projects/<project-slug>/` with a clear file
      name (for example `<project>-preview.json`).
- [ ] Phase 2 states that Claude sends the approved preview file as the batch body
      with no reformatting.
- [ ] The skill has no remaining reference to an EDN preview or a
      `.scratch/migrate/` preview path.
- [ ] The edits pass the blocking simple-english lint hook.

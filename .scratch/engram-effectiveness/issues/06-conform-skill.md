# 06: Add the `/conform` skill

**What to build:** The `/conform` skill brings nonconforming memories back into line with the configuration. The skill reads `GET /config` for the live configurations and categories. The skill reads `GET /memories/nonconforming` for the memories that fail. The skill reads `GET /recalls` for the label vocabulary already in use.

The skill splits the nonconforming memories into fixed-size chunks. The skill spawns one Haiku subagent per chunk, with a model override. The skill runs about ten subagents at a time. Each subagent proposes the smallest tag change that makes a memory satisfy a configuration. The subagent judges the change from the memory's content and existing tags. The subagent reuses a label from the vocabulary over a near-duplicate. The subagent writes the fixes it is confident about to a shared directory. The subagent writes a declined list of the memory ids it does not touch.

The skill works in waves. For each wave, the skill writes the chunk files and runs the subagents. The skill folds the proposals and the declined lists in memory. The skill then deletes that wave's files. The working directory is `~/.claude/projects/<project-slug>/conform/`.

The master agent takes the union of the declined lists. The master attempts to conform each declined memory itself. The master asks the user in grouped questions only where it stays unsure.

The skill assembles one batch in the `update` shape, one entry per memory. A batch update replaces a memory's whole tag set, so each entry lists the full corrected set. The skill writes the batch to a review file and sends nothing to the server. After the user approves, the skill sends the review file as the batch body with `POST /memories/batch`. On `HTTP 422` the skill fixes the named entries and resends the whole batch. The skill clears the working directory after the server applies the batch.

The skill reasons only from the live configuration and the live nonconforming set. The skill names no fixed category. The skill survives any configuration change.

**Blocked by:** 05 (Return the memories that do not conform to the configuration).

**Status:** done

- [x] The skill reads the live configurations and categories from `GET /config`.
- [x] The skill reads the nonconforming memories from `GET /memories/nonconforming`.
- [x] The skill reads the label vocabulary from `GET /recalls`, and the subagents and the master reuse an existing label over a near-duplicate.
- [x] The skill splits the memories into chunks and runs about ten Haiku subagents at a time.
- [x] Each subagent proposes the smallest conforming tag change for the memories it is confident about.
- [x] Each subagent writes a declined list for the memories it does not touch.
- [x] The skill works in waves and deletes each wave's files after it folds the results.
- [x] The master agent conforms the declined memories and asks the user only where it stays unsure.
- [x] Each batch entry lists the memory's full corrected tag set.
- [x] The skill writes the batch to a review file and sends nothing before approval.
- [x] The skill names no fixed category and survives any configuration change.

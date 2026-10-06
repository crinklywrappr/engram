# 06: Add the `/conform` skill

**What to build:** The `/conform` skill brings nonconforming memories back into line with the configuration. The skill calls `GET /config` for the live configurations. The skill calls the nonconforming route for the memories that fail. The skill spawns a Haiku subagent with a model override. The subagent proposes the smallest tag change that makes each memory satisfy a configuration. The subagent judges the change from the memory's content and existing tags. The subagent includes only the memories it is highly confident about. The subagent leaves the rest out for later review. The skill saves the proposal as a batch payload in the `update` shape, one entry per memory. The skill writes the payload to a review file and sends nothing to the server. On approval the same file is the batch body. The skill reasons only from the live configuration and the live nonconforming set. The skill names no fixed category. The skill survives any configuration change. A batch update replaces a memory's whole tag set. Each entry lists the full corrected set. The in-session agent can help review the out-file.

**Blocked by:** 05 (Return the memories that do not conform to the configuration).

**Status:** ready-for-agent

- [ ] The skill reads the live configurations from `GET /config`.
- [ ] The skill reads the nonconforming memories from the route in ticket 05.
- [ ] The skill spawns a Haiku subagent with a model override.
- [ ] The subagent proposes the smallest conforming tag change per memory.
- [ ] The subagent includes only memories it is highly confident about.
- [ ] Each batch entry lists the memory's full corrected tag set.
- [ ] The skill saves the payload to a review file and sends nothing to the server.
- [ ] The skill names no fixed category and survives any configuration change.

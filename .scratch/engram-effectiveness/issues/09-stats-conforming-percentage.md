# 09: Report the fraction of conforming memories on `/stats`

**What to build:** `/stats` reports the fraction of the caller's memories that satisfy a configuration. The value is a single decimal truncated to four decimal places. A value of `0.08` means eight percent. A value of `0.0844` means 8.44 percent. The fraction reuses the conformance machinery from the nonconforming route. The fraction reflects the configuration the server currently holds. The computation covers only the caller's own memories.

**Blocked by:** 05 (Return the memories that do not conform to the configuration) and 08 (Move the recall counts off `/stats`).

**Status:** done

- [x] `/stats` reports the fraction of the caller's memories that satisfy a configuration.
- [x] The value is a single decimal truncated to four decimal places.
- [x] The fraction reuses the conformance machinery from the nonconforming route.
- [x] The fraction reflects the server's current configuration.
- [x] The computation covers only the caller's own memories.
- [x] The `/stats` response schema names the new field.

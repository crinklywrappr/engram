# 01: Load the config, not just any file

**What to build:** `config/load-config` owns the `ENGRAM_CONFIG` resolution instead of parsing whatever path it is handed. When `ENGRAM_CONFIG` is set, the function reads that path. When `ENGRAM_CONFIG` is unset, the function reads `default-path`. The config component in `system.clj` stops reading `ENGRAM_CONFIG` and passes only the default path. This mirrors how `data-path` already wraps `ENGRAM_DATA_DIR`.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `load-config` takes a `default-path` parameter, not `path`.
- [ ] When `ENGRAM_CONFIG` is set, `load-config` reads that file.
- [ ] When `ENGRAM_CONFIG` is unset, `load-config` reads `default-path`.
- [ ] The `:engram.config/config` init-key calls `load-config` with the default path and no longer reads `ENGRAM_CONFIG` itself.
- [ ] A test covers the env override and the default fallback.
- [ ] The full suite passes.

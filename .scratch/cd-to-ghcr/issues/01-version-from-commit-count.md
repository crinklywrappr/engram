# 01: Version from commit count and a test-free uber task

**What to build:** `build.clj` computes the version from the commit count. It gains a build task that makes the uberjar without running the tests.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `build.clj` sets `version` to `1.0.` joined with the commit count from `b/git-count-revs`.
- [ ] A new build task makes the uberjar and does not run the test suite.
- [ ] The `ci` task still runs the tests and then makes the uberjar.
- [ ] The uberjar file name carries the computed version.
- [ ] The `0.1.0-SNAPSHOT` literal is gone.
- [ ] A full-depth checkout is required for the count to be correct. A shallow checkout gives the wrong number.

# 05: README rewrite

**What to build:** A README that sells engram to a hobbyist and sends each reader to the right document. A visitor lands on the page and understands why engram beats a file-based or token-heavy memory setup. The visitor then follows a link to the admin doc or the user doc for the detail.

**Blocked by:** 01 (admin doc) and 04 (user doc), because the README links to both.

**Status:** ready-for-agent

- [ ] The opening sells the project to a hobbyist: self-hosted, per-user, private by SSH key, token-light, one small container.
- [ ] The README links to the admin deployment doc and the user install doc, and moves the deep deploy and use detail into them.
- [ ] The README keeps a short design summary, the develop commands, the API table, and the license.
- [ ] The README no longer duplicates the configuration structure, the deploy mounts, or the SSH setup now that the docs own them.
- [ ] The README respects the simple-english lint hook.

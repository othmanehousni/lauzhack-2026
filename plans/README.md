# Plans

Animation and surface plans for the mole overlay bar in `mole/`. Written at commit 5e9ee76.

| # | Title | Severity | Status |
| --- | --- | --- | --- |
| 001 | [Drive the mole with a procedural spring rig](001-procedural-rig-engine.md) | HIGH | DONE |
| 002 | [Stop the words from jumping and mask the title crossfade](002-words-without-jumps.md) | MEDIUM | DONE |
| 003 | [Flatten the bar and rebuild the playground as a clean poster](003-flat-surfaces-and-playground.md) | MEDIUM | TODO |
| 004 | [Add five states: thinking, waiting, tunneling, failed, hello](004-new-states.md) | LOW | TODO (proposal) |

## Order

1. **001** first. It replaces the puppet's CSS motion with the engine that 002 and 004 build on.
2. **002** and **003** next. They touch different parts of `mole/mole.css` (002 the words, 003 one shadow line) and different files otherwise (002 `mole/mole.js`, 003 `mole/index.html`). Run them one after the other, not in parallel, because both edit `mole/mole.css`.
3. **004** last. It needs 001's rig and 003's state row. Trim its state list with the user before running it.

## Dependencies

- 002 depends on 001 (its reduced-motion step assumes the puppet's keyframes are gone).
- 004 depends on 001 and 003.
- 003 depends on nothing.

# 004 — Add five states the copilot already needs: thinking, waiting, tunneling, failed, hello

- **Status**: DONE. The feel check failed on the starting values, so these were retuned: `thinking` `{ x: 0, y: 14, rot: -8, head: -34 }`, `waiting` `{ x: -14, y: 36, rot: 12, head: 8 }`, `failed` `{ x: 0, y: 14, rot: 2, head: 30 }`, `KICKS.hello.specsY` -180, molehill `rx 18 ry 11` with specks at ±18 and speed 70–95, and the countdown drawn as `x2 = 30 + 36 · ground` so it runs on the visible stretch of ground.
- **Commit**: 5e9ee76
- **Severity**: LOW (additive)
- **Category**: Missed opportunities
- **Estimated scope**: 4 files (`mole/mole.js` about 70 lines, `mole/mole.css` about 4 lines, `mole/index.html` about 15 lines, `mole/check.mjs` about 20 lines)
- **Depends on**: 001 (the rig engine), 003 (the state row lays out any number of states)

## Problem

The mole has seven states. The copilot's real flow, described in `README.md`, has moments none of them covers, so the bar would show the wrong thing or nothing:

1. **The router or vision model is deciding.** `README.md` says the d1 router runs "at your first short pause (0.3 s) and again at the end (0.6 s)" and that the vision model "take[s] a second or more". Showing `heard` ("I've got it.") before the copilot has decided anything claims a certainty it doesn't have.
2. **The copilot is waiting for the rest of a sentence.** `README.md`: `"Open up, um…" waits 8 s for the target in your next words.` Nothing shows the presenter that the copilot is holding, or for how long.
3. **The screen agent runs a multi-step job.** `copilot.py:370` prints one line per agent step (`step N: action ref text (reason)`); a job lasts seconds to tens of seconds. `working` (dig in place) is for one quick lookup and gets monotonous over a long job.
4. **An action failed.** `copilot.py:753` prints `not done` when the agent gives up. `unsure` means "I didn't understand you"; a failure means "I understood and couldn't do it". These need different faces.
5. **The first appearance of a session** gets no greeting. It happens once per talk, the one tier where AUDIT.md allows delight.

## Target

Five new states on top of plan 001's engine. Every value below is a starting value; poses were set by reasoning about the rig's geometry, not by eye. The executor renders them, compares them against the existing poses, and reports. The feel check is mandatory.

**New channels** (added to plan 001's channel table):

| Channel | duration | bounce | Drawn as |
| --- | --- | --- | --- |
| `ponder` | 0.4 | 0 | envelope; adds `5 · E.ponder · sin(2π·t / 2.4)` degrees to `.m-head` rotation |
| `tunnel` | 0.4 | 0 | envelope for the mound's travel |
| `mound` | 0.5 | 0.2 | `.m-mound` transform `translate(mx  8·(1 − mound))`, with `mx = 12 + 30 · E.tunnel · sin(2π·t / 2.6)` |
| `ground` | 0.2 | 0 | ground line attribute `x2 = -44 + 110 · ground` (target 1 except in `waiting`) |
| `tie` | 0.7 | 0.2 | `.m-tie` transform `rotate(tie)`, degrees |

**New poses** (same shape as plan 001's `POSES`; `mound` target 1 only in `tunneling`):

```js
thinking:  { x: 0,  y: 8,   rot: -4, head: -20, specsX: 0, specsY: 0, card: 0 },
waiting:   { x: -6, y: 30,  rot: -8, head: -16, specsX: 0, specsY: 0, card: 0 },
tunneling: { x: -4, y: 118, rot: 12, head: 30,  specsX: 0, specsY: 0, card: 0 }, // body underground, the mound travels instead
failed:    { x: 0,  y: 12,  rot: 2,  head: 6,   specsX: 0, specsY: 0, card: 0 },
hello:     { x: 0,  y: -4,  rot: 0,  head: -6,  specsX: 0, specsY: 0, card: 0 },
```

**Behaviour per state**:

| State | Default title | Dot | Motion |
| --- | --- | --- | --- |
| `thinking` | `Thinking…` | sage, pulsing | half out, looking up; the head sways ±5° on a 2.4 s sine (`LOOPS.thinking = ["ponder"]`) |
| `waiting` | `Go on…` | sage, steady | half out, head cocked; the ground line shortens from full to nothing over `timeout` ms (`ground.target = remaining / timeout`, linear in time, the AUDIT.md curve for progress), then stays at 0 until the next state |
| `tunneling` | `On it…` | sage, pulsing | the mole stays underground and a molehill (`<ellipse class="m-ink m-mound" rx="13" ry="8">`) rises and travels back and forth along the ground, throwing specks at its leading edge (`LOOPS.tunneling = ["tunnel"]`) |
| `failed` | `Couldn't do that.` | ember | slumps a little and shakes its head once: for `u` seconds since entry, `.m-head` gets `scale(yaw 1)` after its rotation, `yaw = 1 − 0.38 · e^(−3.2u) · |sin(2πu / 0.3)|` for `u < 1.2`, else 1 (a profile can't turn its head, so the snout foreshortens instead) |
| `hello` | `Hi. I'm listening.` | sage, steady | pops a little higher than `heard`; on entry the bow tie spins once (`tie.target += 360`) and the glasses get pushed up (`KICKS.hello = { specsY: -60 }`) |

**Tunneling specks** reuse plan 001's pool and gravity (300). They are emitted at `(mx + 10 · sign(cos(2π·t / 2.6)), 0)`, one every `0.2 + uniform(-0.05, 0.05)` s while `E.tunnel > 0.5`. Launch angle `uniform(-110, -70)` degrees, speed `uniform(50, 75)`.

**API**: `set(state, { title, heard, timeout })`. `timeout` (ms) is read only by `waiting`; default 8000, the copilot's wait in `README.md`.

**Status dot CSS** (`mole/mole.css`, extend the existing selectors):

```css
[data-state=unsure] .mole-dot, [data-state=failed] .mole-dot { background: var(--ember); }
[data-state=working] .mole-dot, [data-state=thinking] .mole-dot, [data-state=tunneling] .mole-dot { animation: mole-pulse calc(1s * var(--slow)) ease-in-out infinite; }
```

## Repo conventions to follow

- States are rows in tables, not branches: `TITLES` in `mole/mole.js`, and `POSES`, `LOOPS`, `KICKS` added by plan 001. A new state is new rows. The only state-specific code is the three drawn effects above (`ground`, `mound` travel, `failed` yaw), each one expression in `draw()` or `step()`.
- Durations in CSS are `calc(<n> * var(--slow))`; rig time scales through `Mole.speed`.
- The playground lists states through `SAMPLES` in `mole/index.html`; the state row (plan 003) and the keys derive from it.

## Steps

1. In `mole/mole.js`, wrap the bow tie so it can spin about its own centre: change `<path class="m-tie" transform="translate(13 -54)" d="..."/>` to `<g transform="translate(13 -54)"><path class="m-tie" d="..."/></g>`, keeping the `d` attribute. Add `<ellipse class="m-ink m-mound" rx="13" ry="8"/>` just before the ground `<line>`, outside `.m-body`.
2. In `mole/mole.js`, add the five `TITLES`, the five `POSES`, `LOOPS.thinking` and `LOOPS.tunneling`, `KICKS.hello`, the five new channels, and the tie spin (`tie.target += 360` on entering `hello`). Track the time of the last state entry so `failed` can compute `u`, and track the `waiting` start time and `timeout`.
3. In `mole/mole.js`, extend `draw()` with the mound transform, the ground line's `x2`, the tie rotation, and the `failed` yaw in `.m-head`'s transform (`rotate(head) scale(yaw 1)`). Extend the speck emitter with the tunneling source.
4. Extend `rig.asleep` so it is false while a `waiting` countdown is still running.
5. In `mole/mole.css`, replace the two status-dot rules with the ones in **Target**.
6. In `mole/index.html`, add to `SAMPLES`:
   ```js
   thinking: { title: "Thinking…", heard: "show them how the warehouse robot picks" },
   waiting: { title: "Open… what?", heard: "open up, um", timeout: 8000 },
   tunneling: { title: "Step 2 · clicking Search", heard: "search robot grippers on youtube" },
   failed: { title: "Couldn't find “roadmap”.", heard: "pull up the roadmap" },
   hello: { title: "Hi. I'm listening.", heard: "" },
   ```
   Make `show(state)` pass `SAMPLES[state]` through unchanged (it already does). Number keys cover only the first ten entries of `KEYS`; the buttons cover all.
7. In `mole/index.html`, add a second scene to `story()` after the first `hidden` beat: `hello` (1600 ms), `idle` (600 ms), `thinking` with heard text (1400 ms), `tunneling` with title `Step 1 · opening YouTube` (1500 ms) then `Step 2 · clicking Search` (1500 ms), `ready` with title `It's on screen.` (1500 ms), `back` (1300 ms), `hidden`.
8. In `mole/check.mjs`, extend the settle and teleport checks to all twelve states, and add: in `waiting` with `timeout: 1000`, `ground` is below 0.05 after 1.2 s and back above 0.95 within 0.6 s of leaving; in `failed`, `yaw` is exactly 1 after 1.3 s.

## Boundaries

- Do NOT change the seven existing poses or their springs.
- Do NOT add a state the copilot has no moment for; if a state above has no caller once the copilot is wired up, delete it rather than keeping it "for later".
- Do NOT add dependencies.
- If a step doesn't match the code you find (drift since commit 5e9ee76 plus plans 001 and 003), STOP and report instead of improvising.

## Verification

- **Mechanical**: `node mole/check.mjs` prints `ok`; the Chrome console on `mole/index.html` shows no errors.
- **Feel check** (poses here were not tuned by eye, so this is where they get judged):
  - In the state row, every new pose is distinguishable from every old one at 132 px, and again inside the 62 px live bar. `thinking` must not read as `heard`; `failed` must not read as `unsure`. Report any pair that doesn't separate; don't retune silently.
  - `waiting`: the ground line visibly shrinks over 8 s and returns smoothly when you press another state.
  - `tunneling`: the mound travels without a seam at the turnaround, and specks come from its leading edge. Watch for 20 s; it must not feel frantic. If it does, the knobs are the 2.6 s period and the 0.2 s emission interval.
  - `failed`: one clear head shake, then stillness. With S (slow-mo) on, the snout foreshortens rather than flipping.
  - `hello`: the bow tie spins once and settles; the glasses nudge up and settle. Play it ten times in a row; if it becomes irritating, that confirms it should run once per session, which is the copilot's job, not the bar's.
  - Emulate `prefers-reduced-motion: reduce`: every new state shows its pose instantly, the `waiting` line still shortens (it is information, not decoration), and there is no mound travel, shake, or spin.
- **Done when**: all twelve states pass `node mole/check.mjs`, and the user has seen the state row and kept or cut each new state.

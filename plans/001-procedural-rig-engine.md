# 001 — Drive the mole with a procedural spring rig instead of CSS transitions and keyframes

- **Status**: DONE
- **Commit**: 5e9ee76
- **Severity**: HIGH
- **Category**: Interruptibility (also Physicality & origin)
- **Estimated scope**: 3 files edited (`mole/mole.js` grows by about 150 lines, `mole/mole.css` shrinks by about 60, `mole/index.html` 2 lines), 1 new file (`mole/check.mjs`, about 80 lines)

## Problem

The mole's poses are CSS transitions keyed off `data-state`, its life loops are CSS keyframes, and its spring is a pre-baked `linear()` curve. Three things follow, all visible whenever the copilot changes state quickly (commands fire mid-sentence, `heard` to `working` is often under 1 s apart).

1. **Loops and dirt snap on every state exit.** The loops are attached by state selectors, so the frame the state changes they are removed and the element jumps to identity. Measured in Chrome at commit 5e9ee76 by flipping `working` to `ready` mid-dig: `.m-life` went from `matrix(0.9997, 0.0232, -0.0232, 0.9997, 0.498, 0.996)` to `matrix(1, 0, 0, 1, 0, 0)` in one frame (worst case is the full 3 px and 4 degrees of the dig), and a dirt speck went from `translateY(-11.6)` to `translateY(4)` (below the ground, so it vanished) while `.m-dirt` opacity was still `1`. The dirt fade-out never shows.

   ```css
   /* mole/mole.css:83-87 — current */
   [data-state=heard] .m-life, [data-state=ready] .m-life { animation: mole-breathe calc(3.2s * var(--slow)) ease-in-out infinite; }
   [data-state=idle] .m-nose { animation: mole-sniff calc(2.8s * var(--slow)) ease-in-out infinite; }
   [data-state=working] .m-life { animation: mole-dig calc(.5s * var(--slow)) ease-in-out infinite; }
   [data-state=working] .m-nose { animation: mole-nod calc(.5s * var(--slow)) ease-in-out calc(.08s * var(--slow)) infinite; }
   [data-state=unsure] .m-nose { animation: mole-huh calc(.6s * var(--slow)) var(--ease-out) calc(.15s * var(--slow)); }
   ```
   ```css
   /* mole/mole.css:99-102 — current */
   .m-dirt { opacity: 0; transition: opacity calc(150ms * var(--slow)) var(--ease-out); }
   [data-state=working] .m-dirt { opacity: 1; transition-delay: calc(250ms * var(--slow)); }
   [data-state=working] .m-fx { animation: mole-fx calc(var(--t) * var(--slow)) linear calc(var(--d) * var(--slow)) infinite backwards; }
   [data-state=working] .m-fy { animation: mole-fy calc(var(--t) * var(--slow)) calc(var(--d) * var(--slow)) infinite backwards; }
   ```

2. **Interrupted moves lose their velocity.** A CSS transition that is retargeted starts again from the current value with zero velocity and replays the whole 785 ms `linear()` spring curve, overshoot included. Two quick state changes produce a visible kink and a second overshoot.

   ```css
   /* mole/mole.css:48-53 — current */
   .m-body, .m-head, .m-specs, .m-card {
     transition: transform calc(var(--spring-dur) * var(--slow)) var(--spring), opacity calc(150ms * var(--slow)) var(--ease-out);
   }
   .m-head { transition-delay: calc(40ms * var(--slow)); }
   .mole-bar[data-from=hidden] .m-body { transition-delay: calc(90ms * var(--slow)); }
   .mole-bar[data-from=hidden] .m-head { transition-delay: calc(130ms * var(--slow)); }
   ```
   ```js
   // mole/mole.js:17-29 — current
   function spring(bounce, perceived) { /* samples a damped spring into linear(...) */ }
   const s = spring(0.3, 0.5);
   document.documentElement.style.setProperty("--spring", s.easing);
   document.documentElement.style.setProperty("--spring-dur", `${s.ms}ms`);
   ```

3. **Follow-through is faked.** The head lags the body through fixed `transition-delay`s (above), the "huh" in `unsure` is a fixed keyframe, and nothing squashes or stretches when the mole pops out or dives.

## Target

One small deterministic engine inside `mole/mole.js` owns every moving part of the puppet. CSS keeps only the bar's own fade, the words, and the status dot.

**Data shape.** A rig is a set of named spring channels plus a clock, a seeded random generator, and a speck pool:

```js
// channel = { x, v, target, k, c }   (position, velocity, rest target, stiffness, damping; mass is 1)
const springOf = (duration, bounce) => ({ k: (2 * Math.PI / duration) ** 2, c: (4 * Math.PI * (1 - bounce)) / duration });
```

This is Apple's spring parametrisation (`duration`, `bounce`), the one AUDIT.md recommends (`{ type: "spring", duration: 0.5, bounce: 0.2 }`).

**Channels and their springs** (exact values):

| Channel | Unit | duration | bounce | Note |
| --- | --- | --- | --- | --- |
| `x`, `y`, `rot` | mole units, degrees | 0.5 | 0.2 | body, turns about the base of the body at (0, 0) |
| `head` | degrees | 0.6 | 0.25 | softer than the body, so it lags behind on its own (replaces the 40 ms delay) |
| `specsX`, `specsY` | mole units | 0.35 | 0.1 | glasses slide |
| `card` | 0..1 | 0.45 | 0.2 | slide card presence |
| `breathe`, `dig`, `sniff` | 0..1 | 0.4 | 0 | loop envelopes; critically damped so loops fade in and out instead of snapping |

**Poses** (the numbers are today's CSS poses, unchanged):

```js
const POSES = {
  hidden:  { x: -4,  y: 118, rot: 12, head: 30, specsX: 0,  specsY: 0,  card: 0 },
  idle:    { x: -30, y: 62,  rot: 28, head: -9, specsX: 0,  specsY: 0,  card: 0 },
  heard:   { x: 0,   y: 0,   rot: 0,  head: -4, specsX: 0,  specsY: 0,  card: 0 },
  working: { x: -22, y: 40,  rot: 30, head: 52, specsX: 0,  specsY: 0,  card: 0 },
  ready:   { x: -4,  y: 2,   rot: -3, head: -8, specsX: 0,  specsY: 0,  card: 1 },
  unsure:  { x: -4,  y: 20,  rot: 4,  head: 12, specsX: 10, specsY: -4, card: 0 },
  back:    { x: -20, y: 50,  rot: 24, head: 38, specsX: 0,  specsY: 0,  card: 0 },
};
const LOOPS = { heard: ["breathe"], ready: ["breathe"], working: ["dig"], idle: ["sniff"] };
const KICKS = { unsure: { head: -140 }, back: { head: 90 } }; // added to the channel's velocity (deg/s) on entering the state
```

**Integration.** Fixed sub-step `1 / 240` s, semi-implicit Euler, per channel:

```js
s.v += (s.k * (s.target - s.x) - s.c * s.v) * dt;
s.x += s.v * dt;
```

`step(dt)` clamps `dt` to at most 0.1 s, then runs as many sub-steps as fit, carrying the remainder to the next call. The rig clock `t` advances by every sub-step.

**What gets drawn each frame** (`bump(p) = 0.5 - 0.5 * Math.cos(2 * Math.PI * p)`; `E.name` is the envelope channel's `x`):

| SVG group | `transform` attribute |
| --- | --- |
| `.m-body` | `translate(x y) rotate(rot)` |
| `.m-life` | `translate(1.5·E.dig·bump(t/0.5)  3·E.dig·bump(t/0.5)) rotate(4·E.dig·bump(t/0.5)) scale(sx sy)` |
| `.m-head` | `rotate(head)` |
| `.m-nose` | `rotate(10·E.dig·bump((t-0.08)/0.5) + E.sniff·sniffAngle)` |
| `.m-specs` | `translate(specsX specsY)` |
| `.m-card` | `translate(-8+8c  24-24c) rotate(-24+24c) scale(0.9+0.1c)` and attribute `opacity = clamp(c, 0, 1)`, with `c` the `card` channel |

Squash and stretch comes from the body's vertical velocity, so it appears only while the mole moves:

```js
const sy0 = 1 + Math.min(0.08, Math.max(-0.06, -y.v / 6000)); // rising (negative v) stretches, landing squashes
const sx = (1 / Math.sqrt(sy0)) * (1 + 0.012 * E.breathe * bump(t / 3.2));
const sy = sy0 * (1 + 0.025 * E.breathe * bump(t / 3.2));
```

**Sniff** is a random event, not a fixed 2.8 s loop. While `E.sniff > 0.01`, schedule the next sniff at `t + uniform(1.8, 4.2)`. A sniff is two twitches of 0.336 s each: for `u = t - start` in `[0, 0.672)`, `sniffAngle = -4 * Math.sin(Math.PI * ((u % 0.336) / 0.336)) ** 2`, else `0`.

**Dirt** is ballistic. Pool of 12 `<circle class="m-ink">` inside `.m-dirt`; a dead speck has `r="0"`.
- Emit only while `E.dig > 0.5`, one speck every `0.11 + uniform(-0.03, 0.03)` s. Skip if all 12 are alive.
- Emission point: the snout tip in mole space is `T = body + R(rot) · (P + R(head) · (44, -17.5))` with `P = (8, -86)` and `R(a)` a rotation by `a` degrees; the snout base `B` is the same with `(14.8, -2.6)` in place of `(44, -17.5)`. If `T.y >= 0` and `B.y < 0`, emit at `x = B.x + (T.x - B.x) * (-B.y) / (T.y - B.y)`, `y = 0`; otherwise at `x = T.x`, `y = 0`.
- Launch: angle `uniform(-80, -40)` degrees from +x (negative is up), speed `uniform(70, 105)` units/s, radius `uniform(1.4, 2.2)`, gravity `300` units/s² downward.
- A speck dies when `y > 2` and it is falling. Leaving `working` only stops emission; specks in the air finish their arc.

**Random numbers** come from a seeded generator so the check script is deterministic:

```js
const mulberry32 = a => () => { a |= 0; a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
```

**Targeting.** `rig.set(state, { reduce, delay })`:
- Sets every pose channel's `target` from `POSES[state]`; envelopes listed in `LOOPS[state]` target 1, all others 0; adds `KICKS[state]` to channel velocities.
- `delay` (seconds of rig time) postpones applying the targets. The DOM layer passes `0.09` when the previous state was `hidden`, so the bar fades in before the mole pops (replaces `mole.css:52-53`).
- `reduce: true` snaps: every channel `x = target`, `v = 0`; every envelope `x = target = 0`; no kicks; no dirt. Reduced motion keeps the pose change (it is state, and it is instant) and drops all movement.

**Sleep.** `rig.asleep` is true when every channel has `|target - x| < 0.01` and `|v| < 0.01`, every envelope target is 0, and no speck is alive. The DOM layer stops its `requestAnimationFrame` loop when the rig is asleep (the bar is hidden most of a talk, so this means zero work then) and restarts it on `set()`.

**Slow motion.** `window.Mole.speed` (default `1`) multiplies the frame `dt`. The playground's slow-mo sets it to `0.2`.

**Public API stays the same**: `Mole.create(el)` returns `{ el, set(state, { title, heard }) }`. `window.Mole` becomes `{ create, createRig, states, speed: 1 }`.

## Repo conventions to follow

- Classic script, no modules, no build, no dependencies: `mole/mole.js` is one IIFE that ends in `window.Mole = {...}` (`mole/mole.js:3`, `mole/mole.js:106`). It must load from `file://`.
- Comments only for a non-obvious why; see the header comment at `mole/mole.js:1-2` and the coordinate note at `mole/mole.js:39-40`.
- The text side already uses WAAPI and the `--slow` CSS multiplier (`mole/mole.js:72-88`); leave it alone. Plan 002 owns it.

## Steps

1. In `mole/mole.js`, delete the `spring()` function and the two `setProperty` lines (`mole/mole.js:17-29`). After this the script must not touch `document` at load time, only inside `create()`.
2. In `mole/mole.js`, delete the `DIRT` array (`mole/mole.js:31-37`) and replace the dirt markup (`mole/mole.js:60-62`) with an empty `<g class="m-dirt"></g>`. Leave every other part of the `FIGURE` template exactly as it is.
3. In `mole/mole.js`, add above `create()`: `mulberry32`, `springOf`, `POSES`, `LOOPS`, `KICKS`, and `createRig(seed)` returning `{ set(state, opts), step(dt), pose(), get asleep() }`. `pose()` returns plain numbers: `{ body: {x, y, rot}, life: {x, y, rot, sx, sy}, head, nose, specs: {x, y}, card, specks: [{x, y, r}] }`. All formulas and constants come from **Target**; do not invent others.
4. In `create()`, build the rig with `createRig(1)`, look up the groups `.m-body .m-life .m-head .m-nose .m-specs .m-card .m-dirt`, append 12 `<circle class="m-ink" r="0">` to `.m-dirt` (use `document.createElementNS("http://www.w3.org/2000/svg", "circle")`), and add a `draw()` that writes each group's `transform` attribute with numbers formatted by `n.toFixed(3)`. Use `setAttribute("transform", ...)`, not `style.transform`.
5. Add the frame loop in `create()`:
   ```js
   let raf = 0, last = 0;
   const frame = now => {
     rig.step((Math.min(100, now - last) / 1000) * window.Mole.speed);
     last = now;
     draw();
     raf = rig.asleep ? 0 : requestAnimationFrame(frame);
   };
   const wake = () => { if (!raf) { last = performance.now(); raf = requestAnimationFrame(frame); } };
   ```
   In `set()`, when the state changes, call `rig.set(state, { reduce: reduce.matches, delay: prev === "hidden" ? 0.09 : 0 })` and then `wake()`. Call `draw()` once at the end of `create()` so the first paint already shows the hidden pose.
6. In `mole/mole.css`, delete the puppet motion: the transition block and delays (`mole/mole.css:48-53`), the pose comment and every pose rule (`mole/mole.css:55-80`), the loop rules and their keyframes (`mole/mole.css:82-96`), and the dirt rules and keyframes (`mole/mole.css:98-109`). CSS `transform` would override the SVG `transform` attribute, so none of these may remain. Keep the static fills at `mole/mole.css:39-46`.
7. In the reduced-motion block (`mole/mole.css:148-152`), change line 150 to `.mole-bar, .mole-words { transition-property: opacity !important; }` and delete line 151 (`.m-dirt { display: none; }`). The rig handles reduced motion itself.
8. In `mole/index.html`, in `toggleSlow()` (`mole/index.html:95-99`), add `Mole.speed = slow === 1 ? 1 : 0.2;` next to the `--slow` line.
9. Create `mole/check.mjs`. It loads `mole.js` into a `node:vm` context whose globals are `{ window: {}, matchMedia: () => ({ matches: false }), console }`, takes `createRig` and `states` from `window.Mole`, and asserts the following, printing `ok` at the end:
   - **Settles.** For every ordered pair of distinct states `(a, b)`: settle the rig at `a` (step 3 s), `set(b)`, then step in 1/240 s increments; each pose channel is within `0.05` of `POSES[b]` by 1.6 s.
   - **Never teleports.** With seed 7, run 200 state changes at random intervals of 20 to 400 ms. Between consecutive 1/240 s sub-steps, no drawn number may move by more than 6: body `x` and `y`, every rotation including `life.rot` and `nose`, `life.sx` and `life.sy` multiplied by 100, and `card` multiplied by 100.
   - **Dirt finishes its arc.** Run `working` for 2 s, then `set("ready")`: every speck that was alive and above the ground (`y < 0`) before the change is still alive one sub-step later, and at least one speck is still alive 0.15 s later.
   - **Reduced motion snaps.** `set("ready", { reduce: true })` followed by one `step(1/240)` leaves every pose channel exactly on target and every envelope at 0.
   - **Sleeps.** After `set("hidden")` and 3 s of stepping, `asleep` is true.

## Boundaries

- Do NOT change the poses' numbers, the SVG shapes, the colours, or the bar layout.
- Do NOT touch the words side: `swap()`, the title and heard elements, `.mole-dot`, `.mole-words`. Plan 002 owns them.
- Do NOT add dependencies, a bundler, ES modules, or a canvas renderer.
- Do NOT add states. Plan 004 adds them on top of this engine.
- If a step doesn't match the code you find (drift since commit 5e9ee76), STOP and report instead of improvising.

## Verification

- **Mechanical**: `node mole/check.mjs` prints `ok` and exits 0. Open `mole/index.html` in Chrome; the console shows no errors.
- **Feel check**: in `mole/index.html`:
  - Press 3 (working), wait 2 s, press 4 (ready). No frame jumps; the dig bob eases out over about 0.4 s while the mole rises; specks already in the air land instead of vanishing.
  - Press 2 then 3 within a quarter of a second, several times. The mole changes direction smoothly each time, with no restarted overshoot.
  - Press 0 then 2. The mole stretches slightly as it shoots up and squashes slightly as it lands. If either reads as rubbery, the knob is the `6000` divisor (larger means subtler). Judge by eye, not by numbers.
  - Press S for slow motion (speed 0.2) and replay 0, 2, 3, 4, 6, 0. The head visibly trails the body on every move.
  - Leave it on 1 (idle) for 15 s. The sniffs come at irregular intervals.
  - In DevTools, Rendering panel, emulate `prefers-reduced-motion: reduce`, then press 2, 3, 4. Each pose appears instantly, nothing loops, there is no dirt, and the bar and words still fade.
  - Press 0 and wait 2 s. In the DevTools Performance panel, a 3 s recording shows no `requestAnimationFrame` callbacks for the live bar.
- **Done when**: `node mole/check.mjs` prints `ok`, `grep -n "@keyframes mole-\(breathe\|sniff\|dig\|nod\|huh\|fx\|fy\)" mole/mole.css` prints nothing, and every feel check above passes.

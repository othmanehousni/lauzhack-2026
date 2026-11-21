# 002 — Stop the words from jumping and mask the title crossfade

- **Status**: DONE
- **Commit**: 5e9ee76
- **Severity**: MEDIUM
- **Category**: Missed opportunities (a layout jump), Cohesion & tokens, Accessibility
- **Estimated scope**: 2 files (`mole/mole.css` about 15 lines, `mole/mole.js` about 10 lines)
- **Depends on**: 001 (step 4 below assumes 001 already removed the puppet's CSS keyframes)

## Problem

1. **The title jumps 8.5 px with no transition** whenever the heard line appears or clears. The heard line is removed from layout when empty, so the vertically centred text block changes height and the title teleports. Measured in Chrome at commit 5e9ee76 by going from `idle` (no heard text) to `heard` (with heard text): the title's top moved 8.5 px in one frame. It happens at every story beat that sets `heard: ""`.

   ```css
   /* mole/mole.css:144 — current */
   .mole-heard:empty { display: none; }
   ```
   ```js
   // mole/mole.js:100 — current
   if (h !== undefined) heard.textContent = h.length > HEARD_MAX ? `…${h.slice(1 - HEARD_MAX).trimStart()}` : h;
   ```

2. **The title crossfade double-exposes.** Old and new titles share one grid cell; for about 120 ms both are partly visible, 12 px apart, and read as a smear.

   ```js
   // mole/mole.js:78-87 — current
   const shift = reduce.matches ? 0 : 6, timing = { easing: EASE_OUT, fill: "forwards" };
   for (const old of [...title.children]) {
     old.animate([{ opacity: 0, transform: `translateY(${-shift}px)` }], { ...timing, duration: 160 * slow() })
       .finished.then(() => old.remove(), () => {});
   }
   title.append(next);
   next.animate(
     [{ opacity: 0, transform: `translateY(${shift}px)` }, { opacity: 1, transform: "none" }],
     { ...timing, duration: 220 * slow(), delay: 40 * slow() },
   );
   ```

3. **The ease-out curve is typed twice**, once as a CSS token and once as a JS string.

   ```css
   /* mole/mole.css:8 — current */
   --ease-out: cubic-bezier(0.23, 1, 0.32, 1);
   ```
   ```js
   // mole/mole.js:14 — current
   const EASE_OUT = "cubic-bezier(0.23, 1, 0.32, 1)";
   ```

4. **Reduced motion removes the only "busy" signal.** The status dot's pulse is opacity-only, which reduced motion should keep, but this rule removes every animation in the bar:

   ```css
   /* mole/mole.css:149 — current */
   .mole-bar, .mole-bar * { animation: none !important; }
   ```

## Target

- The heard line always takes its 16 px in layout. When there is no heard text, it fades to `opacity: 0` and the words block slides down by `8.5px` (half of the 16 px line plus its 1 px margin), so the title sits centred. Both moves use the existing token: `transform 260ms var(--ease-out)`, `opacity 200ms var(--ease-out)`, scaled by `--slow` like every other duration in the file.
- When the heard text is cleared, the old words stay in the DOM while they fade, so the fade shows something.
- The title swap masks the overlap with `filter: blur(2px)` on the outgoing and incoming spans (the AUDIT.md remedy for a double-exposing crossfade). Durations stay 160 ms out, 220 ms in, 40 ms delay, offset 6 px.
- JS reads the curve from the CSS token, so there is one definition.
- Reduced motion keeps opacity animations (the dot pulse) and keeps its current rule of no movement.

```css
/* target, mole/mole.css words section */
.mole-words {
  --wx: 0px;
  --wy: 0px;
  padding-left: 16px;
  transform: translate(var(--wx), var(--wy));
  transition: transform calc(260ms * var(--slow)) var(--ease-out);
}
[data-state=idle] .mole-words, [data-state=back] .mole-words { --wx: -16px; }
.mole-words[data-one-line] { --wy: 8.5px; }

.mole-heard {
  height: 16px;
  margin-top: 1px;
  font-size: 12.5px;
  line-height: 16px;
  color: rgb(31 28 26 / .55);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: opacity calc(200ms * var(--slow)) var(--ease-out);
}
.mole-words[data-one-line] .mole-heard { opacity: 0; }
```

## Repo conventions to follow

- Every CSS duration is written `calc(<n>ms * var(--slow))` so the playground's slow-mo scales it; see `mole/mole.css:28`.
- Curves come from `:root` tokens in `mole/mole.css:2-10`. Use `var(--ease-out)`; add no new curve.
- The WAAPI swap already scales with `slow()` (`mole/mole.js:72`); keep that.

## Steps

1. In `mole/mole.css`, replace the `.mole-words` rule and the idle/back shift (`mole/mole.css:130-131`) with the `.mole-words` block from **Target**.
2. In `mole/mole.css`, replace the `.mole-heard` rule (`mole/mole.css:135-143`) with the `.mole-heard` rule from **Target**, add `.mole-words[data-one-line] .mole-heard { opacity: 0; }`, and delete `.mole-heard:empty { display: none; }` (`mole/mole.css:144`). Keep the two quote pseudo-elements (`mole/mole.css:145-146`).
3. In `mole/mole.css`, in the reduced-motion block, delete `.mole-bar, .mole-bar * { animation: none !important; }` (`mole/mole.css:149`). Plan 001 already removed the puppet's keyframes, so the only CSS animation left is the dot's opacity pulse, which should stay.
4. In `mole/mole.js`:
   - Delete `const EASE_OUT = ...` (`mole/mole.js:14`).
   - In `create()`, look up `const words = root.querySelector(".mole-words")` and read `const ease = getComputedStyle(document.documentElement).getPropertyValue("--ease-out").trim() || "ease-out";`. Use `ease` in `timing`.
   - Right after the markup is inserted, call `words.toggleAttribute("data-one-line", true)`.
   - In `swap()`, add `const blur = reduce.matches ? 0 : 2;` and add `filter: \`blur(${blur}px)\`` to the outgoing keyframe and to the incoming first keyframe, and `filter: "blur(0px)"` to the incoming last keyframe.
   - Replace the heard line write (`mole/mole.js:100`) with:
     ```js
     if (h !== undefined) {
       words.toggleAttribute("data-one-line", !h);
       if (h) heard.textContent = h.length > HEARD_MAX ? `…${h.slice(1 - HEARD_MAX).trimStart()}` : h;
     }
     ```

## Boundaries

- Do NOT touch the SVG puppet, `createRig`, or anything plan 001 added.
- Do NOT change the bar's size, padding, font sizes, or colours.
- Do NOT animate the heard text itself as it streams. It updates several times a second, which is the frequency tier where animation is wrong.
- If a step doesn't match the code you find (drift since commit 5e9ee76 plus plan 001), STOP and report instead of improvising.

## Verification

- **Mechanical**: open `mole/index.html` in Chrome; the console shows no errors. In the console, run `Mole.create(document.body.appendChild(document.createElement("div")))` and check that `getComputedStyle(document.querySelector(".mole-heard")).height` is `"16px"` even when empty.
- **Feel check**:
  - Press 1 (idle, no heard line), then 2 (heard). The title glides up 8.5 px over about a quarter of a second while the heard line fades in. Nothing teleports.
  - Press Space to play the story. Every title change blurs through instead of showing two crisp overlapping lines; with S (slow-mo) on, no frame shows two legible titles at once.
  - Emulate `prefers-reduced-motion: reduce` in the DevTools Rendering panel and press 3 (working). The green square still pulses. Titles still crossfade, without sliding.
- **Done when**: `grep -n "EASE_OUT\|:empty" mole/mole.js mole/mole.css` prints nothing and the feel checks pass.

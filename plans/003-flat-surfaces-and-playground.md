# 003 — Flatten the bar and rebuild the playground as a clean poster

- **Status**: DONE
- **Commit**: 5e9ee76
- **Severity**: MEDIUM (requested by the user: "make the background and cards flatter, make the overall website cleaner")
- **Category**: Cohesion & tokens
- **Estimated scope**: 2 files (`mole/mole.css` 1 line, `mole/index.html` style block, markup and sheet builder rewritten, about 60 lines)
- **Depends on**: none. Runs before or after 001 and 002. Plan 004 depends on this.

## Problem

1. **The bar floats on a heavy three-layer shadow.** The deepest layer is a 36 px blur at 40 % ink, which reads as a raised card, not a flat cut-out like the reference poster.

   ```css
   /* mole/mole.css:23 — current */
   box-shadow: 0 0 0 1px rgb(31 28 26 / .08), 0 1px 2px rgb(31 28 26 / .1), 0 16px 36px -10px rgb(31 28 26 / .4);
   ```

2. **The demo wallpapers are gradients**, and the screen has a shadow ring:

   ```css
   /* mole/index.html:12-14 — current */
   .screen { position: relative; aspect-ratio: 16 / 10; border-radius: 14px; overflow: hidden; box-shadow: 0 0 0 1px rgb(31 28 26 / .1); transition: background .3s ease; }
   .screen[data-wall="0"] { background: radial-gradient(120% 90% at 20% 10%, #f7d7b9, #e48a5c 45%, #6d3a4f); }
   .screen[data-wall="1"] { background: radial-gradient(120% 90% at 70% 0%, #3a4a72, #151a2c 60%, #0a0b10); }
   ```

3. **The state sheet is six full bars stacked in a column**, each with that shadow, next to a two-column layout. It is busy and does not look like the reference, where each pose stands on its own ground line with one caption under it.

   ```css
   /* mole/index.html:26-28 — current */
   .sheet { display: grid; gap: 18px; padding-top: 8px; }
   .sheet figure { margin: 0; display: grid; gap: 8px; justify-items: start; }
   .sheet figcaption { font-size: 12px; color: rgb(31 28 26 / .55); }
   ```
   ```js
   // mole/index.html:54-60 — current
   for (const [state, words] of Object.entries(SAMPLES)) {
     const fig = document.createElement("figure");
     const bar = Mole.create(document.createElement("div"));
     bar.set(state, words);
     fig.append(bar.el, Object.assign(document.createElement("figcaption"), { textContent: `${KEYS.indexOf(state)}  ${state}` }));
     sheet.append(fig);
   }
   ```

4. **The buttons are outlined**, which adds a fourth kind of edge on a page that already has hairlines and shadows (`mole/index.html:20`).

## Target

A single centred column on flat paper. No drop shadows anywhere. Edges come from a 1 px ink hairline at 10 % only where a surface would otherwise vanish.

- **Bar**: `box-shadow: 0 0 0 1px rgb(31 28 26 / .1);` and nothing else.
- **Page**: header, then the live screen with its controls, then the state row.
- **Screen**: 16:9, radius 12 px, no shadow. Four flat wallpapers: ember `#f2531c` (the reference poster's block), ink `#1f1c1a`, white `#ffffff` with an inset hairline, sage tint `#dfe6dc`. The colour change uses `transition: background-color .3s ease` (colour change, so `ease` is the AUDIT.md curve).
- **State row**: one transparent button per state, laid out in a responsive grid. Each shows the mole large (132 × 128 px) on its ground line with the status square and title under it, like the reference's bottom row. Clicking one plays that state on the live bar.
- **Buttons**: filled with ink at 5 %, no border; 9 % on hover (gated to fine pointers); press feedback `scale: .97` over 160 ms on `var(--ease-out)`.

```html
<!-- target: mole/index.html, everything from <style> to </main> -->
<style>
  body { margin: 0; background: var(--paper); color: var(--ink); font: 14px/1.5 -apple-system, system-ui, sans-serif; -webkit-font-smoothing: antialiased; }
  main { max-width: 960px; margin: 0 auto; padding: 56px 32px 80px; display: grid; gap: 48px; }
  h1 { font: 600 56px/1 ui-serif, "New York", Georgia, serif; letter-spacing: -0.03em; margin: 0 0 12px; }
  header p { margin: 0; max-width: 52ch; color: rgb(31 28 26 / .6); text-wrap: pretty; }
  h2 { font: 500 12px/1 -apple-system, system-ui, sans-serif; letter-spacing: .02em; color: rgb(31 28 26 / .5); margin: 0 0 14px; }

  .screen { position: relative; aspect-ratio: 16 / 9; border-radius: 12px; overflow: hidden; transition: background-color .3s ease; }
  .screen[data-wall="0"] { background-color: #f2531c; }
  .screen[data-wall="1"] { background-color: #1f1c1a; }
  .screen[data-wall="2"] { background-color: #ffffff; box-shadow: inset 0 0 0 1px rgb(31 28 26 / .1); }
  .screen[data-wall="3"] { background-color: #dfe6dc; }
  .screen .mole-bar { position: absolute; left: 50%; bottom: 24px; translate: -50% 0; }

  .controls { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; }
  .controls button { font: 500 13px/1 -apple-system, system-ui, sans-serif; color: var(--ink); background: rgb(31 28 26 / .05); border: 0; border-radius: 8px; height: 32px; padding: 0 12px; display: inline-flex; align-items: center; gap: 8px; cursor: pointer; transition: background-color .15s ease, color .15s ease, scale .16s var(--ease-out); }
  .controls button[aria-pressed=true] { background: var(--ink); color: var(--paper); }
  .controls button:active { scale: .97; }
  kbd { font: 500 11px/1 ui-monospace, monospace; opacity: .5; }

  .sheet { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 8px; }
  .sheet button { all: unset; display: grid; padding: 12px; border-radius: 12px; cursor: pointer; transition: background-color .15s ease; }
  .sheet button:focus-visible { outline: 2px solid var(--ink); outline-offset: 2px; }
  .sheet .mole-bar { width: auto; height: auto; padding: 0; flex-direction: column; align-items: stretch; gap: 10px; background: none; box-shadow: none; border-radius: 0; }
  .sheet .mole-fig { align-self: flex-start; width: 132px; height: 128px; margin: 0; }
  .sheet .mole-heard { display: none; }

  @media (hover: hover) and (pointer: fine) {
    .controls button:hover:not([aria-pressed=true]) { background: rgb(31 28 26 / .09); }
    .sheet button:hover { background: rgb(31 28 26 / .04); }
  }
</style>

<main>
  <header>
    <h1>The Mole.</h1>
    <p>Pops out of the overlay bar while the copilot works, says what it understood, and dives back when you have the screen again.</p>
  </header>
  <section>
    <h2>On a screen</h2>
    <div class="screen" data-wall="0"><div id="live"></div></div>
    <div class="controls" id="controls"></div>
  </section>
  <section>
    <h2>States</h2>
    <div class="sheet" id="sheet"></div>
  </section>
</main>
```

```js
// target: mole/index.html, replaces the sheet loop
for (const [state, words] of Object.entries(SAMPLES)) {
  const pick = Object.assign(document.createElement("button"), { onclick: () => show(state) });
  pick.setAttribute("aria-label", `Show ${state} on the screen`);
  const bar = Mole.create(document.createElement("div"));
  bar.el.setAttribute("aria-hidden", "true");
  bar.set(state, words);
  pick.append(bar.el);
  sheet.append(pick);
}
```

## Repo conventions to follow

- Colours come from the `:root` tokens in `mole/mole.css:2-10` (`--paper`, `--ink`, `--ember`, `--sage`); the new wallpapers reuse `#f2531c` (= `--ember`) and `#1f1c1a` (= `--ink`) and add only white and `#dfe6dc`.
- Hover is already gated the right way at `mole/index.html:22`; keep that pattern.
- The playground stays one file with inline `<style>` and `<script>`, loading `mole.css` and `mole.js`.

## Steps

1. In `mole/mole.css`, replace line 23 with `box-shadow: 0 0 0 1px rgb(31 28 26 / .1);`.
2. In `mole/index.html`, replace everything from `<style>` through `</main>` (`mole/index.html:6-39`) with the target block above.
3. In `mole/index.html`, replace the sheet loop (`mole/index.html:54-60`) with the target loop above. `show` is a function declaration further down, so it is hoisted and safe to reference.
4. In `mole/index.html`, change the wallpaper cycle from `% 3` to `% 4` in both places: the Wallpaper button's handler and the `w` key handler.
5. Leave `SAMPLES`, `KEYS`, `story()`, `toggleSlow()` and the key bindings unchanged.

## Boundaries

- Do NOT change the bar's size, radius, padding, typography, or colours; only its shadow.
- Do NOT touch `mole/mole.js` or any motion in `mole/mole.css`.
- Do NOT add web fonts, images, or dependencies.
- If a step doesn't match the code you find (drift since commit 5e9ee76), STOP and report instead of improvising.

## Verification

- **Mechanical**: open `mole/index.html` in Chrome at 1280 × 900 and at 760 × 900; the console shows no errors and nothing overflows horizontally.
- **Feel check**:
  - No element on the page has a drop shadow. The only edges are the hairline around the bar and the inset hairline on the white wallpaper.
  - Press W four times. Each wallpaper is a flat colour; the bar stays legible on all four, including ink, where the cream bar itself is the edge.
  - Click each pose in the state row. The live bar switches to that state, and keyboard focus shows an outline.
  - The state row reads like the reference poster's bottom row: each mole stands on its ground line with the square and title under it.
  - Put a busy photo behind the bar (DevTools, set `.screen` background to any photo URL). If the hairline alone loses the bar's edge, report it rather than adding a shadow back; that is a design call for the user.
- **Done when**: `grep -n "radial-gradient\|16px 36px" mole/index.html mole/mole.css` prints nothing and the feel checks pass.

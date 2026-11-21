// node mole/check.mjs: runs the mole's rig headless and prints ok.
import { readFileSync } from "node:fs";
import vm from "node:vm";

const context = { window: {}, matchMedia: () => ({ matches: false }), console };
vm.runInNewContext(readFileSync(new URL("./mole.js", import.meta.url), "utf8"), context);
const { createRig, states } = context.window.Mole;

const assert = (ok, message) => {
  if (!ok) {
    console.error(`not ok: ${message}`);
    process.exit(1);
  }
};

const DT = 1 / 240;
const run = (rig, seconds, each = () => {}) => {
  for (let i = Math.round(seconds / DT); i > 0; i--) {
    rig.step(DT);
    each();
  }
};
const mulberry32 = a => () => { a |= 0; a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };

// A copy of mole.js's poses, so a changed pose number fails here too.
const POSES = {
  hidden:  { x: -4,  y: 118, rot: 12, head: 30, specsX: 0,  specsY: 0,  card: 0 },
  idle:    { x: -30, y: 62,  rot: 28, head: -9, specsX: 0,  specsY: 0,  card: 0 },
  heard:   { x: 0,   y: 0,   rot: 0,  head: -4, specsX: 0,  specsY: 0,  card: 0 },
  working: { x: -22, y: 40,  rot: 30, head: 52, specsX: 0,  specsY: 0,  card: 0 },
  ready:   { x: -4,  y: 2,   rot: -3, head: -8, specsX: 0,  specsY: 0,  card: 1 },
  unsure:  { x: -4,  y: 20,  rot: 4,  head: 12, specsX: 10, specsY: -4, card: 0 },
  back:    { x: -20, y: 50,  rot: 24, head: 38, specsX: 0,  specsY: 0,  card: 0 },
};
const channels = p => ({ x: p.body.x, y: p.body.y, rot: p.body.rot, head: p.head, specsX: p.specs.x, specsY: p.specs.y, card: p.card });

assert(states.length === Object.keys(POSES).length && states.every(s => s in POSES), "every state has a pose");

for (const a of states) {
  for (const b of states) {
    if (a === b) continue;
    const rig = createRig(1);
    rig.set(a);
    run(rig, 3);
    rig.set(b);
    run(rig, 1.6);
    const got = channels(rig.pose());
    for (const [name, want] of Object.entries(POSES[b])) {
      assert(Math.abs(got[name] - want) < 0.05, `${a} -> ${b}: ${name} settles within 0.05 of ${want} by 1.6 s (got ${got[name]})`);
    }
  }
}

{
  const random = mulberry32(7), rig = createRig(7);
  const drawn = p => [p.body.x, p.body.y, p.body.rot, p.life.rot, p.head, p.nose, p.life.sx * 100, p.life.sy * 100, p.card * 100];
  let state = "hidden", before = drawn(rig.pose()), worst = 0;
  for (let i = 0; i < 200; i++) {
    const others = states.filter(s => s !== state);
    const next = others[Math.floor(random() * others.length)];
    rig.set(next, { delay: state === "hidden" ? 0.09 : 0 });
    state = next;
    run(rig, 0.02 + random() * 0.38, () => {
      const now = drawn(rig.pose());
      worst = Math.max(worst, ...now.map((n, j) => Math.abs(n - before[j])));
      before = now;
    });
  }
  assert(worst <= 6, `200 quick state changes never move a drawn number by more than 6 in one sub-step (worst ${worst.toFixed(3)})`);
}

{
  const rig = createRig(1);
  rig.set("working");
  run(rig, 2);
  const flying = rig.pose().specks.flatMap((s, i) => (s.r > 0 && s.y < 0 ? [i] : []));
  assert(flying.length > 0, "dirt is in the air after 2 s of working");
  rig.set("ready");
  run(rig, DT);
  const after = rig.pose().specks;
  assert(flying.every(i => after[i].r > 0), "leaving working keeps every airborne speck alive");
  run(rig, 0.15);
  assert(rig.pose().specks.some(s => s.r > 0), "dirt is still landing 0.15 s after leaving working");
}

{
  const rig = createRig(1);
  rig.set("working");
  run(rig, 2);
  rig.set("ready", { reduce: true });
  rig.step(DT);
  const p = rig.pose(), got = channels(p);
  for (const [name, want] of Object.entries(POSES.ready)) assert(got[name] === want, `reduced motion puts ${name} exactly on the ready pose (got ${got[name]})`);
  const { life, nose } = p;
  assert(life.x === 0 && life.y === 0 && life.rot === 0 && life.sx === 1 && life.sy === 1 && nose === 0, "reduced motion stops every loop");
  assert(p.specks.every(s => s.r === 0), "reduced motion throws no dirt");
}

{
  const rig = createRig(1);
  rig.set("working");
  run(rig, 2);
  rig.set("hidden");
  run(rig, 3);
  assert(rig.asleep, "asleep 3 s after hiding");
  rig.set("idle", { delay: 0.09 });
  assert(!rig.asleep, "a delayed set keeps the rig awake until its pose applies");
}

console.log("ok");

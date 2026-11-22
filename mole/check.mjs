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
  hidden:     { x: -4,  y: 126, rot: 6,  head: 30,  specsX: 0, specsY: 0,  card: 0, cx: 44, cy: 20, crot: -14 },
  idle:       { x: -10, y: 70,  rot: 4,  head: 30,  specsX: 0, specsY: 0,  card: 0 },
  heard:      { x: 0,   y: 0,   rot: 0,  head: 0,   specsX: 0, specsY: 0,  card: 0 },
  working:    { x: -12, y: 46.5, rot: 8, head: 62,  specsX: 0, specsY: 0,  card: 0 },
  retrieving: { x: -10, y: 30,  rot: 0,  head: 43,  specsX: 0, specsY: 0,  card: 1, cx: 53, cy: -31, crot: -14, stack: 1 },
  ready:      { x: -4,  y: 0,   rot: 0,  head: -2,  specsX: 0, specsY: 0,  card: 1, cx: 36, cy: -33, crot: 0, stack: 0 },
  back:       { x: -16, y: 63,  rot: 4,  head: 58,  specsX: 0, specsY: 0,  card: 1, cx: 72, cy: -15.5, crot: 0 },
  unsure:     { x: -4,  y: 20,  rot: 2,  head: 12,  specsX: 9, specsY: -5, card: 0 },
  thinking:   { x: 0,   y: 14,  rot: -4, head: -24, specsX: 0, specsY: 0,  card: 0 },
  waiting:    { x: -10, y: 36,  rot: 4,  head: 14,  specsX: 0, specsY: 0,  card: 0 },
  tunneling:  { x: -4,  y: 126, rot: 6,  head: 30,  specsX: 0, specsY: 0,  card: 0, mound: 1 },
  failed:     { x: 0,   y: 14,  rot: 2,  head: 30,  specsX: 0, specsY: 0,  card: 0 },
  hello:      { x: 0,   y: -2,  rot: 0,  head: -4,  specsX: 0, specsY: 0,  card: 0 },
  choosing:   { x: -2,  y: 4,   rot: 0,  head: 14,  specsX: 0, specsY: 0,  card: 1, fan: 1, cx: 36, cy: -35, crot: -4 },
  picked:     { x: -4,  y: 0,   rot: 0,  head: -2,  specsX: 0, specsY: 0,  card: 1, fan: 0, cx: 36, cy: -33, crot: 0 },
};
const channels = p => ({ x: p.body.x, y: p.body.y, rot: p.body.rot, head: p.head, specsX: p.specs.x, specsY: p.specs.y, card: p.card, fan: p.fan,
  cx: p.cardAt.x, cy: p.cardAt.y, crot: p.cardAt.rot, stack: p.stack, mound: 1 - p.mound.y / 12 });

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
  // The tie counts in percent of a turn: one hello spin alone reaches 5.7 degrees per sub-step, two stacked ones just over 6.
  const drawn = p => [p.body.x, p.body.y, p.body.rot, p.life.rot, p.head, p.sway, p.nose, p.tie / 3.6, p.life.sx * 100, p.life.sy * 100, p.card * 100, p.fan * 13,
    p.cardAt.x, p.cardAt.y, p.cardAt.rot, p.stack * 12, p.scent[0] * 100, p.scent[1] * 100, p.mound.x, p.mound.y, p.ground * 100, p.yaw * 100];
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
  rig.set("tunneling");
  run(rig, 2);
  const flying = rig.pose().specks.flatMap((s, i) => (s.r > 0 && s.y < 0 ? [i] : []));
  assert(flying.length > 0, "dirt is in the air after 2 s of tunneling");
  rig.set("ready");
  run(rig, DT);
  const after = rig.pose().specks;
  assert(flying.every(i => after[i].r > 0), "leaving tunneling keeps every airborne speck alive");
  run(rig, 0.15);
  assert(rig.pose().specks.some(s => s.r > 0), "dirt is still landing 0.15 s after leaving tunneling");
}

{
  const rig = createRig(1);
  rig.set("working");
  let brightest = 0;
  run(rig, 2, () => (brightest = Math.max(brightest, ...rig.pose().scent)));
  const p = rig.pose();
  assert(brightest > 0.8 && p.specks.every(s => s.r === 0), `searching sniffs (marks reach ${brightest.toFixed(2)}) and throws no dirt`);
  assert(Math.abs(p.tip.y) < 4, `searching puts the snout's tip on the ground line (tip at y ${p.tip.y.toFixed(2)})`);
  rig.set("heard");
  run(rig, 1.6);
  assert(rig.pose().scent.every(o => o < 0.02), "the sniff marks fade once searching ends");
}

{
  const rig = createRig(1);
  rig.set("ready");
  run(rig, 2);
  rig.set("back");
  run(rig, 1.6);
  const { cardAt, card } = rig.pose();
  assert(card > 0.95 && Math.abs(cardAt.y + 15.5) < 0.05 && Math.abs(cardAt.rot) < 0.05, `handing back leaves the card standing upright on the ground (bottom at ${(cardAt.y + 15.5).toFixed(3)})`);
  rig.set("hidden");
  run(rig, 1.6);
  assert(rig.pose().card < 0.05 && rig.pose().cardAt.y - 15.5 > 0, "hiding sinks the card back under the ground");
}

for (const state of ["retrieving", "ready", "back", "thinking", "waiting", "tunneling", "failed", "hello", "choosing", "picked"]) {
  const rig = createRig(1);
  rig.set("working");
  run(rig, 2);
  rig.set(state, { reduce: true });
  rig.step(DT);
  const p = rig.pose(), got = channels(p);
  for (const [name, want] of Object.entries(POSES[state])) assert(got[name] === want, `reduced motion puts ${name} exactly on the ${state} pose (got ${got[name]})`);
  const { life, nose } = p;
  assert(life.x === 0 && life.y === 0 && life.rot === 0 && life.sx === 1 && life.sy === 1 && nose === 0, `reduced motion stops every loop in ${state}`);
  assert(p.sway === 0 && p.yaw === 1 && p.tie === 0 && p.mound.x === 12, `reduced motion has no sway, shake, spin or mound travel in ${state}`);
  assert(p.specks.every(s => s.r === 0) && p.scent.every(o => o === 0), `reduced motion throws no dirt and shows no sniff marks in ${state}`);
}

{
  const rig = createRig(1);
  rig.set("waiting", { timeout: 1000 });
  run(rig, 1.2);
  assert(rig.pose().ground < 0.05, `the waiting line is gone after its 1 s timeout (got ${rig.pose().ground})`);
  rig.set("heard");
  run(rig, 0.6);
  assert(rig.pose().ground > 0.95, `the line is back within 0.6 s of leaving waiting (got ${rig.pose().ground})`);
}

{
  const rig = createRig(1);
  rig.set("waiting", { reduce: true, timeout: 1000 });
  run(rig, 0.5);
  assert(Math.abs(rig.pose().ground - 0.5) < 0.01 && !rig.asleep, `reduced motion still counts the waiting line down (got ${rig.pose().ground})`);
}

{
  const rig = createRig(1);
  rig.set("failed");
  let least = 1;
  run(rig, 1.3, () => (least = Math.min(least, rig.pose().yaw)));
  assert(least < 0.9 && rig.pose().yaw === 1, `failed shakes its head once and ends exactly at full width (least ${least}, end ${rig.pose().yaw})`);
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

{
  const rig = createRig(1);
  rig.set("choosing");
  run(rig, 1.6);
  assert(rig.pose().fan > 0.95, `choosing fans out both cards (fan ${rig.pose().fan})`);
  rig.set("picked");
  run(rig, 1.6);
  assert(Math.abs(rig.pose().fan) < 0.05 && rig.pose().card > 0.95, `picked folds the fan into one held card (fan ${rig.pose().fan})`);
}

// No bullet, middle dot, em or en dash as a separator anywhere in the mole's files, comments included.
for (const file of ["index.html", "mole.js", "mole.css"]) {
  readFileSync(new URL(`./${file}`, import.meta.url), "utf8").split("\n").forEach((line, i) => {
    const found = line.match(/[\u2022\u00b7\u2014\u2013]/);
    assert(!found, `mole/${file}:${i + 1} has U+${found?.[0].codePointAt(0).toString(16).toUpperCase().padStart(4, "0")}: ${line.trim()}`);
  });
}

console.log("ok");

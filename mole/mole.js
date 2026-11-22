// The Mole. Mole.create(el, { onpick }) turns el into an overlay bar and returns { set(state, { title, heard, timeout, options, pick, card }) }.
// set() flips data-state, swaps the words and retargets the spring rig that draws the puppet. A classic script, so it
// loads from file://; it touches the DOM only inside create(), so check.mjs can run the rig in node.
//
// Finding a slide, in five beats: heard (01 Ready) → working (02 Searching) → retrieving (03 Retrieving)
// → ready (04 Ready to show) → back (05 Handing back).
(() => {
  const TITLES = {
    hidden: "",
    idle: "You're driving.",
    heard: "I've got it.",
    working: "Searching…",
    retrieving: "Found it.",
    ready: "Ready to show.",
    back: "Back to you.",
    unsure: "Didn't catch that.",
    thinking: "Thinking…",
    waiting: "Go on…",
    tunneling: "On it…",
    failed: "Couldn't do that.",
    hello: "Hi. I'm listening.",
    choosing: "Which one?",
    picked: "Opening it…",
  };
  const OPEN = new Set(["choosing", "picked"]); // states that show the options panel above the bar
  const NUMBERS = ["one", "two", "three", "four", "five"];
  // Tiny cut-paper documents in the card's style: white paper, ink edge, faded ink lines.
  const GLYPHS = {
    pdf: `<path d="M3.5 1.5h6l3 3v10h-9z"/><path class="g-line" d="M9.5 1.5v3h3M5.5 8h5M5.5 10.5h5M5.5 13h3"/>`,
    slides: `<rect x="1.5" y="3.5" width="13" height="9" rx="1"/><path class="g-line" d="M4 7h6M4 9.5h8"/>`,
    sheet: `<path d="M3.5 1.5h9v13h-9z"/><path class="g-line" d="M3.5 5.5h9M3.5 9.5h9M8 5.5v9"/>`,
    site: `<rect x="1.5" y="2.5" width="13" height="11" rx="1.5"/><path class="g-line" d="M1.5 5.5h13M4 8.5h8M4 11h5"/>`,
    image: `<rect x="1.5" y="2.5" width="13" height="11" rx="1"/><path class="g-line" d="M1.5 12l4-4 3 3 2-2 4 4M9.75 5.5a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0-2.5 0"/>`,
    other: `<path d="M3.5 1.5h6l3 3v10h-9z"/><path class="g-line" d="M9.5 1.5v3h3"/>`,
  };
  const CHECK = `<svg viewBox="0 0 12 12" aria-hidden="true"><path d="M2.6 6.3l2.2 2.2 4.6-4.9"/></svg>`;
  const HEARD_MAX = 44; // a live transcript keeps its newest words
  const reduce = matchMedia("(prefers-reduced-motion: reduce)");

  // The slide card: white paper, ink edge, a two-bar chart and the slide number. 52 × 31, centred on its origin.
  const CARD = `<rect class="m-paper" x="-26" y="-15.5" width="52" height="31" rx="1.8"/>` +
    `<rect class="m-bar" x="-18" y="-1" width="6.5" height="9.5"/><rect class="m-bar" x="-9.5" y="-10.5" width="6.5" height="19"/>` +
    `<text class="m-num" x="11" y="5.6">07</text>`;

  // Mole-space units: the body's base sits at (0, 0) on the ground line and the snout points to +x. The SVG viewport
  // ends just under the ground line, so whatever sinks below it is out of sight. The outline is traced from the
  // storyboard (The Mole Finds Slide 7.png): straight sides, a pinched neck, a flat-topped snout. The body and head are
  // separate pieces of paper: the head is a disc pinned at (22, -80) with the snout cut in one piece with it, so
  // turning it about the pin keeps the dome's outline whole.
  const FIGURE = `
<svg class="mole-fig" viewBox="-54 -114 154 114.8" aria-hidden="true">
  <g class="m-body"><g class="m-life">
    <path class="m-ink" d="M-49 60L-49 -8C-49 -38 -36.5 -61 -27 -73C-17 -86 3 -100 17 -100L26 -99.5L30 -80L37.7 -64C40 -52 49.5 -35 49.7 -14L49 0L49 60Z"/>
    <g transform="translate(18 -64) scale(.86)"><g class="m-tie">
      <path d="M-4 -3.5C-8 -7 -13 -10 -16 -8.5C-19 -7 -18 -2 -17.2 0C-18 2 -19 7 -16 8.5C-13 10 -8 7 -4 3.5ZM4 -3.5C8 -7 13 -10 16 -8.5C19 -7 18 -2 17.2 0C18 2 19 7 16 8.5C13 10 8 7 4 3.5Z"/>
      <path class="m-fold" d="M-5 0C-8.5 -2.2 -12.5 -2.2 -15.5 0C-12.5 2.2 -8.5 2.2 -5 0ZM5 0C8.5 -2.2 12.5 -2.2 15.5 0C12.5 2.2 8.5 2.2 5 0Z"/>
      <rect class="m-knot" x="-4.5" y="-4.5" width="9" height="9" rx="2"/>
    </g></g>
    <g transform="translate(22 -80)"><g class="m-head"><g class="m-nose">
      <circle class="m-ink" r="18.5"/>
      <path class="m-ink" d="M4 -19.5C14 -21 26 -23.6 35.5 -25C39 -25.5 40.8 -23.5 39.5 -21C32 -12 19 -2 13 13L0 0Z"/>
      <g class="m-specs">
        <line class="m-bridge" x1="2.9" y1="-16.3" x2="5.3" y2="-17"/>
        <circle class="m-lens" cx="-5.8" cy="-13.9" r="9"/>
        <circle class="m-lens" cx="12" cy="-18.8" r="7"/>
      </g>
    </g></g></g>
  </g></g>
  <g class="m-stack"><rect x="6" y="-6" width="68" height="6" rx="1"/><rect x="11" y="-12" width="58" height="6" rx="1"/></g>
  <g class="m-card"><g class="m-back">${CARD}</g><g class="m-front">${CARD}</g></g>
  <g class="m-scent"><path d="M0 -4.5Q5 -8 10 -5"/><path d="M1.5 0.5Q6.5 -3 11.5 0"/></g>
  <g class="m-dirt"></g>
  <ellipse class="m-ink m-mound" rx="20" ry="12"/>
  <line class="m-ground" x1="-54" y1="0" x2="100" y2="0"/>
</svg>`;

  const SVG = "http://www.w3.org/2000/svg";
  const DT = 1 / 240;
  const SPECKS = 12;
  const GRAVITY = 300;
  const TIP = [39.5, -22]; // the snout's tip, in head space

  const mulberry32 = a => () => { a |= 0; a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  // Apple's (duration, bounce) spring with mass 1: k is stiffness, c damping.
  const springOf = (duration, bounce) => ({ k: (2 * Math.PI / duration) ** 2, c: (4 * Math.PI * (1 - bounce)) / duration });

  const BODY = springOf(0.5, 0.2), GLASSES = springOf(0.35, 0.1), ENVELOPE = springOf(0.4, 0), CARDS = springOf(0.55, 0.2);
  const SPRINGS = {
    x: BODY, y: BODY, rot: BODY,
    head: springOf(0.6, 0.25), // softer than the body, so the head trails it on every move
    specsX: GLASSES, specsY: GLASSES,
    card: springOf(0.45, 0.2), // how present the card is: 0 gone, 1 there
    cx: CARDS, cy: CARDS, crot: CARDS, // where the card is, in ground space, so it can be set down and left behind
    fan: springOf(0.45, 0.2), // 1 = two cards fanned out (undecided), 0 = one card (decided)
    stack: springOf(0.4, 0.1), // the pile of slides the card is pulled from
    mound: BODY,
    breathe: ENVELOPE, scent: ENVELOPE, sniff: ENVELOPE, // critically damped, so loops fade in and out instead of snapping
    ponder: ENVELOPE, tunnel: ENVELOPE,
    ground: springOf(0.2, 0),
    tie: springOf(0.7, 0.2),
  };
  const ENVELOPES = ["breathe", "scent", "sniff", "ponder", "tunnel"];
  // Where the card waits when a pose doesn't hold it: underground, under the stack, so it always rises from there.
  const CARD_HOME = { cx: 44, cy: 20, crot: -14 };

  // Fitted to the storyboard: each pose's sink and head angle put the tie and spectacles where the drawing has them.
  const POSES = {
    hidden:     { x: -4,  y: 126, rot: 6,  head: 30,  specsX: 0, specsY: 0,  card: 0 },
    idle:       { x: -10, y: 70,  rot: 4,  head: 30,  specsX: 0, specsY: 0,  card: 0 }, // dozing: dome and spectacles above ground
    heard:      { x: 0,   y: 0,   rot: 0,  head: 0,   specsX: 0, specsY: 0,  card: 0 }, // 01 Ready: up tall, snout raised
    working:    { x: -12, y: 46.5, rot: 8, head: 62,  specsX: 0, specsY: 0,  card: 0 }, // 02 Searching: low, nose to the ground, sniffing
    retrieving: { x: -10, y: 30,  rot: 0,  head: 43,  specsX: 0, specsY: 0,  card: 1, cx: 53, cy: -31, crot: -14, stack: 1 }, // 03: pulls the card off the pile
    ready:      { x: -4,  y: 0,   rot: 0,  head: -2,  specsX: 0, specsY: 0,  card: 1, cx: 36, cy: -33, crot: 0 }, // 04 Ready to show: holds it up
    back:       { x: -16, y: 63,  rot: 4,  head: 58,  specsX: 0, specsY: 0,  card: 1, cx: 72, cy: -15.5, crot: 0 }, // 05: sinks, leaves the card standing
    unsure:     { x: -4,  y: 20,  rot: 2,  head: 12,  specsX: 9, specsY: -5, card: 0 },
    thinking:   { x: 0,   y: 14,  rot: -4, head: -24, specsX: 0, specsY: 0,  card: 0 },
    waiting:    { x: -10, y: 36,  rot: 4,  head: 14,  specsX: 0, specsY: 0,  card: 0 },
    tunneling:  { x: -4,  y: 126, rot: 6,  head: 30,  specsX: 0, specsY: 0,  card: 0, mound: 1 }, // body underground, the mound travels instead
    failed:     { x: 0,   y: 14,  rot: 2,  head: 30,  specsX: 0, specsY: 0,  card: 0 },
    hello:      { x: 0,   y: -2,  rot: 0,  head: -4,  specsX: 0, specsY: 0,  card: 0 },
    choosing:   { x: -2,  y: 4,   rot: 0,  head: 14,  specsX: 0, specsY: 0,  card: 1, fan: 1, cx: 36, cy: -35, crot: -4 }, // holds up both cards, weighing them
    picked:     { x: -4,  y: 0,   rot: 0,  head: -2,  specsX: 0, specsY: 0,  card: 1, fan: 0, cx: 36, cy: -33, crot: 0 }, // the fan folds into the one you picked
  };
  const LOOPS = { heard: ["breathe"], ready: ["breathe"], working: ["scent"], idle: ["sniff"], thinking: ["ponder"], tunneling: ["tunnel"], choosing: ["ponder"], picked: ["breathe"] };
  const KICKS = { unsure: { head: -140 }, back: { head: 90 }, hello: { specsY: -180 }, picked: { specsY: -90 } }; // added to the channel's velocity (per second) on entering the state
  const SPINS = { hello: { tie: 360 } }; // added to the channel's target on entering the state

  const bump = p => 0.5 - 0.5 * Math.cos(2 * Math.PI * p);
  const rotate = ([x, y], deg) => {
    const a = (deg * Math.PI) / 180, c = Math.cos(a), s = Math.sin(a);
    return [x * c - y * s, x * s + y * c];
  };

  function createRig(seed) {
    const random = mulberry32(seed), uniform = (a, b) => a + (b - a) * random();
    const ch = Object.fromEntries(Object.entries(SPRINGS).map(([name, s]) => {
      const x = POSES.hidden[name] ?? CARD_HOME[name] ?? 0;
      return [name, { x, v: 0, target: x, ...s }];
    }));
    ch.ground.x = ch.ground.target = 1;
    const specks = Array.from({ length: SPECKS }, () => ({ x: 0, y: 0, vx: 0, vy: 0, r: 0 }));
    let t = 0, acc = 0, pending = null, nextSpeck = 0, nextSniff = null, sniffStart = -Infinity;
    let entered = 0, waitFor = 0, reduced = false, shakeStart = -Infinity;

    function flush() {
      if (!pending || t < pending.at) return;
      const { state, reduce, timeout } = pending, pose = POSES[state], loops = LOOPS[state] || [];
      pending = null;
      entered = t;
      reduced = reduce;
      waitFor = state === "waiting" ? timeout / 1000 : 0;
      for (const [name, s] of Object.entries(ch)) {
        // tick() owns the ground line's countdown, and the tie keeps the angle it spun to so leaving hello doesn't unwind it.
        if (name !== "ground" && name !== "tie") {
          s.target = name in pose ? pose[name] : name in CARD_HOME ? CARD_HOME[name] : !reduce && loops.includes(name) ? 1 : 0;
        }
        if (reduce) { s.x = s.target; s.v = 0; }
      }
      if (reduce) {
        for (const p of specks) p.r = 0;
        shakeStart = -Infinity;
        return;
      }
      for (const [name, dv] of Object.entries(KICKS[state] || {})) ch[name].v += dv;
      for (const [name, d] of Object.entries(SPINS[state] || {})) ch[name].target += d;
      // A shake under way finishes instead of restarting, so the snout never jumps back to full length mid-shake.
      if (state === "failed" && t - shakeStart >= 1.2) shakeStart = t;
    }

    // A point in head space, in ground space (ignores the small loops, which is close enough to place the sniff marks).
    const snout = point => {
      const [hx, hy] = rotate(point, ch.head.x), [x, y] = rotate([22 + hx, -80 + hy], ch.rot.x);
      return [ch.x.x + x, ch.y.x + y];
    };
    const moundX = () => 12 + 30 * ch.tunnel.x * Math.sin((2 * Math.PI * t) / 2.6);

    function emit(p, x, angle, speed) {
      const a = (angle * Math.PI) / 180;
      Object.assign(p, { x, y: 0, vx: speed * Math.cos(a), vy: speed * Math.sin(a), r: uniform(1.4, 2.2) });
    }

    function tick() {
      flush();
      ch.ground.target = waitFor ? Math.max(0, 1 - (t - entered) / waitFor) : 1;
      // The countdown is information, not decoration, so reduced motion keeps it and only drops the spring.
      if (reduced) { ch.ground.x = ch.ground.target; ch.ground.v = 0; }
      for (const s of Object.values(ch)) {
        s.v += (s.k * (s.target - s.x) - s.c * s.v) * DT;
        s.x += s.v * DT;
      }
      if (ch.sniff.x > 0.01) {
        if (nextSniff === null) nextSniff = t + uniform(1.8, 4.2);
        else if (t >= nextSniff) { sniffStart = t; nextSniff = t + uniform(1.8, 4.2); }
      } else nextSniff = null;
      // Only tunnelling throws dirt; searching sniffs along the ground instead.
      if (ch.tunnel.x > 0.5 && t >= nextSpeck) {
        nextSpeck = t + 0.2 + uniform(-0.05, 0.05);
        const free = specks.find(p => !p.r);
        if (free) emit(free, moundX() + 18 * Math.sign(Math.cos((2 * Math.PI * t) / 2.6)), uniform(-110, -70), uniform(70, 95));
      }
      for (const p of specks) {
        if (!p.r) continue;
        p.vy += GRAVITY * DT;
        p.x += p.vx * DT;
        p.y += p.vy * DT;
        if (p.y > 2 && p.vy > 0) p.r = 0;
      }
      t += DT;
    }

    return {
      set(state, { reduce = false, delay = 0, timeout = 8000 } = {}) {
        pending = { state, reduce, timeout, at: t + delay };
        flush();
      },
      // Fixed sub-steps keep the springs stable and the check deterministic at any frame rate; the remainder carries over.
      step(dt) {
        acc += Math.min(0.1, Math.max(0, dt));
        for (; acc >= DT; acc -= DT) tick();
      },
      pose() {
        const E = name => ch[name].x;
        const scent = E("scent"), bob = scent * bump(t / 0.8), breathe = E("breathe") * bump(t / 3.2);
        const u = t - sniffStart, sniffAngle = u >= 0 && u < 0.672 ? -4 * Math.sin(Math.PI * ((u % 0.336) / 0.336)) ** 2 : 0;
        const sy0 = 1 + Math.min(0.08, Math.max(-0.06, -ch.y.v / 6000)); // rising (negative v) stretches, landing squashes
        const w = t - shakeStart, yaw = w < 1.2 ? 1 - 0.38 * Math.exp(-3.2 * w) * Math.abs(Math.sin((2 * Math.PI * w) / 0.3)) : 1;
        const [tx, ty] = snout(TIP);
        return {
          body: { x: ch.x.x, y: ch.y.x, rot: ch.rot.x },
          life: { x: 0.6 * bob, y: 1.4 * bob, rot: 1.2 * bob, sx: (1 / Math.sqrt(sy0)) * (1 + 0.012 * breathe), sy: sy0 * (1 + 0.025 * breathe) },
          head: ch.head.x,
          sway: 5 * E("ponder") * Math.sin((2 * Math.PI * t) / 2.4),
          yaw,
          nose: 5 * scent * bump((t - 0.08) / 0.4) + E("sniff") * sniffAngle,
          specs: { x: ch.specsX.x, y: ch.specsY.x },
          card: ch.card.x,
          cardAt: { x: ch.cx.x, y: ch.cy.x, rot: ch.crot.x },
          fan: ch.fan.x,
          stack: ch.stack.x,
          tip: { x: tx, y: ty },
          scent: [0, 1].map(i => scent * (0.2 + 0.8 * bump((t - i * 0.18) / 0.9))), // the two sniff marks pulse outward in turn
          tie: ch.tie.x,
          mound: { x: moundX(), y: 12 * (1 - ch.mound.x) },
          ground: ch.ground.x,
          specks: specks.map(({ x, y, r }) => ({ x, y, r })),
        };
      },
      get asleep() {
        return !pending && specks.every(p => !p.r) && ENVELOPES.every(n => ch[n].target === 0) &&
          t - entered >= waitFor && t - shakeStart >= 1.2 && Object.values(ch).every(s => Math.abs(s.target - s.x) < 0.01 && Math.abs(s.v) < 0.01);
      },
    };
  }

  // create(el, { onpick }): with onpick(i), the options are clickable rows; without it they are pictures (e.g. inside a button).
  function create(root, { onpick } = {}) {
    root.classList.add("mole-bar");
    root.dataset.state = "hidden";
    root.setAttribute("role", "status");
    root.innerHTML = `<div class="mole-opts"><div class="mole-opts-in"><div class="mole-hint"></div><ol class="mole-list"></ol></div></div>` +
      `<div class="mole-row">${FIGURE}<div class="mole-text"><i class="mole-dot"></i><div class="mole-words"><div class="mole-title"></div><div class="mole-heard"></div></div></div></div>`;
    const title = root.querySelector(".mole-title"), heard = root.querySelector(".mole-heard");
    const text = root.querySelector(".mole-text");
    const list = root.querySelector(".mole-list"), say = root.querySelector(".mole-hint");
    const [backNum, frontNum] = root.querySelectorAll(".m-num");
    let rows = [], label = "07";

    function renderOptions(options) {
      rows = options.map(({ name, meta = "", kind = "pdf" }, i) => {
        const li = document.createElement("li");
        li.className = "mole-opt";
        li.innerHTML = `<span class="mole-key"><b>${i + 1}</b>${CHECK}</span><svg class="mole-glyph" viewBox="0 0 16 16" aria-hidden="true">${GLYPHS[kind] || GLYPHS.pdf}</svg><span class="mole-name"></span><span class="mole-meta"></span>`;
        li.querySelector(".mole-name").textContent = name;
        li.querySelector(".mole-meta").textContent = meta;
        if (onpick) {
          li.setAttribute("role", "button");
          li.tabIndex = 0;
          li.onclick = () => onpick(i);
          li.onkeydown = e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onpick(i); } };
        }
        return li;
      });
      list.replaceChildren(...rows);
      const n = options.length, words = NUMBERS.slice(0, n).map(w => `“${w}”`);
      say.textContent = n < 2 ? "" : `Say ${words.slice(0, -1).join(", ")} or ${words.at(-1)}`;
    }
    const highlight = pick => rows.forEach((li, i) => li.toggleAttribute("data-on", i === pick));
    text.toggleAttribute("data-one-line", true);
    const ease = getComputedStyle(document.documentElement).getPropertyValue("--ease-out").trim() || "ease-out";
    const slow = () => parseFloat(getComputedStyle(root).getPropertyValue("--slow")) || 1;

    const rig = createRig(1);
    const g = Object.fromEntries(["body", "life", "head", "nose", "specs", "card", "back", "front", "stack", "scent", "dirt", "tie", "mound", "ground"].map(n => [n, root.querySelector(`.m-${n}`)]));
    const marks = [...g.scent.children];
    const inks = Array.from({ length: SPECKS }, () => {
      const ink = g.dirt.appendChild(document.createElementNS(SVG, "circle"));
      ink.setAttribute("class", "m-ink");
      ink.setAttribute("r", "0");
      return ink;
    });
    const f = n => n.toFixed(3);
    const unit = n => Math.min(1, Math.max(0, n));

    function draw() {
      const { body, life, head, sway, yaw, nose, specs, card: c, cardAt, fan, stack, tip, scent, tie, mound, ground, specks } = rig.pose();
      g.body.setAttribute("transform", `translate(${f(body.x)} ${f(body.y)}) rotate(${f(body.rot)})`);
      g.life.setAttribute("transform", `translate(${f(life.x)} ${f(life.y)}) rotate(${f(life.rot)}) scale(${f(life.sx)} ${f(life.sy)})`);
      // A profile can't turn its head, so the failed shake foreshortens the snout instead.
      g.head.setAttribute("transform", `rotate(${f(head + sway)}) scale(${f(yaw)} 1)`);
      g.nose.setAttribute("transform", `rotate(${f(nose)})`);
      g.specs.setAttribute("transform", `translate(${f(specs.x)} ${f(specs.y)})`);
      g.card.setAttribute("transform", `translate(${f(cardAt.x)} ${f(cardAt.y)}) rotate(${f(cardAt.rot)}) scale(${f(0.9 + 0.1 * unit(c))})`);
      g.card.setAttribute("opacity", f(unit(c)));
      // Both cards pivot near the bottom-left corner, where a paw would hold them; folded, the back card hides behind the front.
      g.front.setAttribute("transform", `rotate(${f(-12 * fan)} -19 14)`);
      g.back.setAttribute("transform", `rotate(${f(16 * fan)} -19 14) translate(${f(3 * fan)} 0)`);
      g.back.setAttribute("opacity", f(unit(fan * 4)));
      g.stack.setAttribute("transform", `translate(0 ${f(14 * (1 - stack))})`);
      g.scent.setAttribute("transform", `translate(${f(tip.x + 6)} ${f(tip.y - 1)})`);
      marks.forEach((m, i) => m.setAttribute("opacity", f(unit(scent[i]))));
      g.tie.setAttribute("transform", `rotate(${f(tie)})`);
      g.mound.setAttribute("transform", `translate(${f(mound.x)} ${f(mound.y)})`);
      // The countdown runs on the stretch of ground right of the mole; the part under it is always hidden.
      g.ground.setAttribute("x2", f(40 + 60 * ground));
      specks.forEach((s, i) => {
        inks[i].setAttribute("cx", f(s.x));
        inks[i].setAttribute("cy", f(s.y));
        inks[i].setAttribute("r", f(s.r));
      });
    }

    let raf = 0, last = 0;
    const frame = now => {
      rig.step((Math.min(100, now - last) / 1000) * window.Mole.speed);
      last = now;
      draw();
      raf = rig.asleep ? 0 : requestAnimationFrame(frame);
    };
    const wake = () => { if (!raf) { last = performance.now(); raf = requestAnimationFrame(frame); } };

    function swap(text, animate) {
      if (title.lastElementChild?.textContent === text) return;
      const next = Object.assign(document.createElement("span"), { textContent: text });
      if (!animate) return title.replaceChildren(next);
      const shift = reduce.matches ? 0 : 6, timing = { easing: ease, fill: "forwards" };
      const blur = reduce.matches ? 0 : 2;
      for (const old of [...title.children]) {
        old.animate([{ opacity: 0, transform: `translateY(${-shift}px)`, filter: `blur(${blur}px)` }], { ...timing, duration: 160 * slow() })
          .finished.then(() => old.remove(), () => {});
      }
      title.append(next);
      next.animate(
        [{ opacity: 0, transform: `translateY(${shift}px)`, filter: `blur(${blur}px)` }, { opacity: 1, transform: "none", filter: "blur(0px)" }],
        { ...timing, fill: "both", duration: 220 * slow(), delay: 40 * slow() },
      );
    }

    // options: [{ name, meta, kind: "pdf" | "slides" | "sheet" | "site" | "image" | "other" }], kept until new ones arrive. pick: the row to mark (-1 for none).
    // card: what the slide card says ("07"); kept until changed. While choosing, the two fanned cards read 1 and 2.
    function set(state, { title: t, heard: h, timeout, options, pick, card } = {}) {
      if (!(state in TITLES)) throw new Error(`unknown mole state: ${state}`);
      if (options) renderOptions(options);
      if (state === "choosing" && pick === undefined) pick = -1;
      if (pick !== undefined) highlight(pick);
      if (card !== undefined) label = card;
      else if (state === "picked" && pick >= 0) label = String(pick + 1);
      frontNum.textContent = state === "choosing" ? "1" : label;
      backNum.textContent = state === "choosing" ? "2" : label;
      root.toggleAttribute("data-open", OPEN.has(state) && rows.length > 0);
      const prev = root.dataset.state;
      if (state !== prev) {
        root.dataset.from = prev;
        root.dataset.state = state;
        t ??= TITLES[state] || undefined;
        if (prev === "hidden") h ??= "";
        rig.set(state, { reduce: reduce.matches, delay: prev === "hidden" ? 0.09 : 0, timeout });
        wake();
      }
      if (t !== undefined) swap(t, prev !== "hidden" && state !== "hidden");
      if (h !== undefined) {
        text.toggleAttribute("data-one-line", !h);
        if (h) heard.textContent = h.length > HEARD_MAX ? `…${h.slice(1 - HEARD_MAX).trimStart()}` : h;
      }
    }

    draw();
    return { el: root, set };
  }

  window.Mole = { create, createRig, states: Object.keys(TITLES), speed: 1 };
})();

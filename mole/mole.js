// The Mole. Mole.create(el) turns el into an overlay bar and returns { set(state, { title, heard, timeout }) }.
// set() flips data-state, swaps the words and retargets the spring rig that draws the puppet. A classic script, so it
// loads from file://; it touches the DOM only inside create(), so check.mjs can run the rig in node.
(() => {
  const TITLES = {
    hidden: "",
    idle: "You're driving.",
    heard: "I've got it.",
    working: "Working on it…",
    ready: "Ready.",
    unsure: "Didn't catch that.",
    back: "Back to you.",
    thinking: "Thinking…",
    waiting: "Go on…",
    tunneling: "On it…",
    failed: "Couldn't do that.",
    hello: "Hi. I'm listening.",
  };
  const HEARD_MAX = 44; // a live transcript keeps its newest words
  const reduce = matchMedia("(prefers-reduced-motion: reduce)");

  // Mole-space units: the body's base sits at (0, 0) on the ground line and the snout points to +x. The SVG viewport
  // ends just under the ground line, so whatever sinks below it is out of sight.
  const FIGURE = `
<svg class="mole-fig" viewBox="-46 -112 116 112.8" aria-hidden="true">
  <g class="m-body"><g class="m-life">
    <path class="m-ink" d="M-38 60L-38 0C-38 -40-31 -70-17 -88C-9 -98 0 -101 9 -101C17 -101 24 -95 26 -86C27.5 -79 25 -72 25.5 -62C26.5 -44 38 -30 38 0L38 60Z"/>
    <g transform="translate(13 -54)"><path class="m-tie" d="M-10.5 -6L-1.5 -1.2L-1.5 1.2L-10.5 6ZM10.5 -6L1.5 -1.2L1.5 1.2L10.5 6ZM-2.2 -2.2h4.4v4.4h-4.4Z"/></g>
    <g transform="translate(8 -86)"><g class="m-head"><g class="m-nose">
      <circle class="m-ink" r="15"/>
      <path class="m-ink" d="M8.6 -12.3C20 -14 32 -16 44 -17.5C34 -12 24 -7 14.8 -2.6L0 0Z"/>
      <g class="m-specs">
        <line class="m-bridge" x1="10.4" y1="-8" x2="15" y2="-10.6"/>
        <circle class="m-lens" cx="5.5" cy="-6" r="5.2"/>
        <circle class="m-lens" cx="19" cy="-12" r="4.4"/>
      </g>
    </g></g></g>
    <g transform="translate(37 -54) rotate(-7)"><g class="m-card">
      <rect x="-15" y="-11" width="30" height="22" rx="1.5"/>
      <path d="M-9 -4.5H5M-9 0H9M-9 4H2"/>
    </g></g>
  </g></g>
  <g class="m-dirt"></g>
  <ellipse class="m-ink m-mound" rx="13" ry="8"/>
  <line class="m-ground" x1="-44" y1="0" x2="66" y2="0"/>
</svg>`;

  const SVG = "http://www.w3.org/2000/svg";
  const DT = 1 / 240;
  const SPECKS = 12;
  const GRAVITY = 300;

  const mulberry32 = a => () => { a |= 0; a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  // Apple's (duration, bounce) spring with mass 1: k is stiffness, c damping.
  const springOf = (duration, bounce) => ({ k: (2 * Math.PI / duration) ** 2, c: (4 * Math.PI * (1 - bounce)) / duration });

  const BODY = springOf(0.5, 0.2), GLASSES = springOf(0.35, 0.1), ENVELOPE = springOf(0.4, 0);
  const SPRINGS = {
    x: BODY, y: BODY, rot: BODY,
    head: springOf(0.6, 0.25), // softer than the body, so the head trails it on every move
    specsX: GLASSES, specsY: GLASSES,
    card: springOf(0.45, 0.2),
    mound: BODY,
    breathe: ENVELOPE, dig: ENVELOPE, sniff: ENVELOPE, // critically damped, so loops fade in and out instead of snapping
    ponder: ENVELOPE, tunnel: ENVELOPE,
    ground: springOf(0.2, 0),
    tie: springOf(0.7, 0.2),
  };
  const ENVELOPES = ["breathe", "dig", "sniff", "ponder", "tunnel"];

  const POSES = {
    hidden:  { x: -4,  y: 118, rot: 12, head: 30, specsX: 0,  specsY: 0,  card: 0 },
    idle:    { x: -30, y: 62,  rot: 28, head: -9, specsX: 0,  specsY: 0,  card: 0 },
    heard:   { x: 0,   y: 0,   rot: 0,  head: -4, specsX: 0,  specsY: 0,  card: 0 },
    working: { x: -22, y: 40,  rot: 30, head: 52, specsX: 0,  specsY: 0,  card: 0 },
    ready:   { x: -4,  y: 2,   rot: -3, head: -8, specsX: 0,  specsY: 0,  card: 1 },
    unsure:  { x: -4,  y: 20,  rot: 4,  head: 12, specsX: 10, specsY: -4, card: 0 },
    back:    { x: -20, y: 50,  rot: 24, head: 38, specsX: 0,  specsY: 0,  card: 0 },
    thinking:  { x: 0,  y: 8,   rot: -4, head: -20, specsX: 0, specsY: 0, card: 0 },
    waiting:   { x: -6, y: 30,  rot: -8, head: -16, specsX: 0, specsY: 0, card: 0 },
    tunneling: { x: -4, y: 118, rot: 12, head: 30,  specsX: 0, specsY: 0, card: 0, mound: 1 }, // body underground, the mound travels instead
    failed:    { x: 0,  y: 12,  rot: 2,  head: 6,   specsX: 0, specsY: 0, card: 0 },
    hello:     { x: 0,  y: -4,  rot: 0,  head: -6,  specsX: 0, specsY: 0, card: 0 },
  };
  const LOOPS = { heard: ["breathe"], ready: ["breathe"], working: ["dig"], idle: ["sniff"], thinking: ["ponder"], tunneling: ["tunnel"] };
  const KICKS = { unsure: { head: -140 }, back: { head: 90 }, hello: { specsY: -60 } }; // added to the channel's velocity (per second) on entering the state
  const SPINS = { hello: { tie: 360 } }; // added to the channel's target on entering the state

  const bump = p => 0.5 - 0.5 * Math.cos(2 * Math.PI * p);
  const rotate = ([x, y], deg) => {
    const a = (deg * Math.PI) / 180, c = Math.cos(a), s = Math.sin(a);
    return [x * c - y * s, x * s + y * c];
  };

  function createRig(seed) {
    const random = mulberry32(seed), uniform = (a, b) => a + (b - a) * random();
    const ch = Object.fromEntries(Object.entries(SPRINGS).map(([name, s]) => {
      const x = POSES.hidden[name] ?? 0;
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
        if (name !== "ground" && name !== "tie") s.target = name in pose ? pose[name] : !reduce && loops.includes(name) ? 1 : 0;
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

    const snout = point => {
      const [hx, hy] = rotate(point, ch.head.x), [x, y] = rotate([8 + hx, -86 + hy], ch.rot.x);
      return [ch.x.x + x, ch.y.x + y];
    };

    const digX = () => {
      const [tx, ty] = snout([44, -17.5]), [bx, by] = snout([14.8, -2.6]);
      // A buried tip throws dirt from where the snout breaks the ground line.
      return ty >= 0 && by < 0 ? bx + ((tx - bx) * -by) / (ty - by) : tx;
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
      const digging = ch.dig.x > 0.5;
      if ((digging || ch.tunnel.x > 0.5) && t >= nextSpeck) {
        nextSpeck = t + (digging ? 0.11 + uniform(-0.03, 0.03) : 0.2 + uniform(-0.05, 0.05));
        const free = specks.find(p => !p.r);
        if (free && digging) emit(free, digX(), uniform(-80, -40), uniform(70, 105));
        else if (free) emit(free, moundX() + 10 * Math.sign(Math.cos((2 * Math.PI * t) / 2.6)), uniform(-110, -70), uniform(50, 75));
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
        const dig = E("dig") * bump(t / 0.5), breathe = E("breathe") * bump(t / 3.2);
        const u = t - sniffStart, sniffAngle = u >= 0 && u < 0.672 ? -4 * Math.sin(Math.PI * ((u % 0.336) / 0.336)) ** 2 : 0;
        const sy0 = 1 + Math.min(0.08, Math.max(-0.06, -ch.y.v / 6000)); // rising (negative v) stretches, landing squashes
        const w = t - shakeStart, yaw = w < 1.2 ? 1 - 0.38 * Math.exp(-3.2 * w) * Math.abs(Math.sin((2 * Math.PI * w) / 0.3)) : 1;
        return {
          body: { x: ch.x.x, y: ch.y.x, rot: ch.rot.x },
          life: { x: 1.5 * dig, y: 3 * dig, rot: 4 * dig, sx: (1 / Math.sqrt(sy0)) * (1 + 0.012 * breathe), sy: sy0 * (1 + 0.025 * breathe) },
          head: ch.head.x,
          sway: 5 * E("ponder") * Math.sin((2 * Math.PI * t) / 2.4),
          yaw,
          nose: 10 * E("dig") * bump((t - 0.08) / 0.5) + E("sniff") * sniffAngle,
          specs: { x: ch.specsX.x, y: ch.specsY.x },
          card: ch.card.x,
          tie: ch.tie.x,
          mound: { x: moundX(), y: 8 * (1 - ch.mound.x) },
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

  function create(root) {
    root.classList.add("mole-bar");
    root.dataset.state = "hidden";
    root.setAttribute("role", "status");
    root.innerHTML = `${FIGURE}<div class="mole-text"><i class="mole-dot"></i><div class="mole-words"><div class="mole-title"></div><div class="mole-heard"></div></div></div>`;
    const title = root.querySelector(".mole-title"), heard = root.querySelector(".mole-heard");
    const text = root.querySelector(".mole-text");
    text.toggleAttribute("data-one-line", true);
    const ease = getComputedStyle(document.documentElement).getPropertyValue("--ease-out").trim() || "ease-out";
    const slow = () => parseFloat(getComputedStyle(root).getPropertyValue("--slow")) || 1;

    const rig = createRig(1);
    const g = Object.fromEntries(["body", "life", "head", "nose", "specs", "card", "dirt", "tie", "mound", "ground"].map(n => [n, root.querySelector(`.m-${n}`)]));
    const inks = Array.from({ length: SPECKS }, () => {
      const ink = g.dirt.appendChild(document.createElementNS(SVG, "circle"));
      ink.setAttribute("class", "m-ink");
      ink.setAttribute("r", "0");
      return ink;
    });
    const f = n => n.toFixed(3);

    function draw() {
      const { body, life, head, sway, yaw, nose, specs, card: c, tie, mound, ground, specks } = rig.pose();
      g.body.setAttribute("transform", `translate(${f(body.x)} ${f(body.y)}) rotate(${f(body.rot)})`);
      g.life.setAttribute("transform", `translate(${f(life.x)} ${f(life.y)}) rotate(${f(life.rot)}) scale(${f(life.sx)} ${f(life.sy)})`);
      // A profile can't turn its head, so the failed shake foreshortens the snout instead.
      g.head.setAttribute("transform", `rotate(${f(head + sway)}) scale(${f(yaw)} 1)`);
      g.nose.setAttribute("transform", `rotate(${f(nose)})`);
      g.specs.setAttribute("transform", `translate(${f(specs.x)} ${f(specs.y)})`);
      g.card.setAttribute("transform", `translate(${f(-8 + 8 * c)} ${f(24 - 24 * c)}) rotate(${f(-24 + 24 * c)}) scale(${f(0.9 + 0.1 * c)})`);
      g.card.setAttribute("opacity", f(Math.min(1, Math.max(0, c))));
      g.tie.setAttribute("transform", `rotate(${f(tie)})`);
      g.mound.setAttribute("transform", `translate(${f(mound.x)} ${f(mound.y)})`);
      g.ground.setAttribute("x2", f(-44 + 110 * ground));
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

    function set(state, { title: t, heard: h, timeout } = {}) {
      if (!(state in TITLES)) throw new Error(`unknown mole state: ${state}`);
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

// The Mole. Mole.create(el) turns el into an overlay bar and returns { set(state, { title, heard }) }.
// set() only flips data-state and swaps the words; mole.css owns every pose. A classic script, so it loads from file://.
(() => {
  const TITLES = {
    hidden: "",
    idle: "You're driving.",
    heard: "I've got it.",
    working: "Working on it…",
    ready: "Ready.",
    unsure: "Didn't catch that.",
    back: "Back to you.",
  };
  const HEARD_MAX = 44; // a live transcript keeps its newest words
  const EASE_OUT = "cubic-bezier(0.23, 1, 0.32, 1)";
  const reduce = matchMedia("(prefers-reduced-motion: reduce)");

  // A damped spring (Apple's bounce and perceived duration) sampled into a CSS linear() curve, run until it settles.
  function spring(bounce, perceived) {
    const w = (2 * Math.PI) / perceived, z = 1 - bounce, wd = w * Math.sqrt(1 - z * z);
    const settle = Math.log(1000) / (z * w);
    const points = Array.from({ length: 61 }, (_, i) => {
      const t = (settle * i) / 60;
      return +(1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + ((z * w) / wd) * Math.sin(wd * t))).toFixed(4);
    });
    return { easing: `linear(${points.join(", ")})`, ms: Math.round(settle * 1000) };
  }
  const s = spring(0.3, 0.5);
  document.documentElement.style.setProperty("--spring", s.easing);
  document.documentElement.style.setProperty("--spring-dur", `${s.ms}ms`);

  const DIRT = [
    { dx: 10, dy: -20, t: 0.62, d: 0, r: 2 },
    { dx: 17, dy: -13, t: 0.55, d: 0.18, r: 1.6 },
    { dx: 5, dy: -26, t: 0.7, d: 0.3, r: 1.8 },
    { dx: 22, dy: -9, t: 0.5, d: 0.42, r: 1.5 },
    { dx: 13, dy: -17, t: 0.6, d: 0.1, r: 2.2 },
  ];

  // Mole-space units: the body's base sits at (0, 0) on the ground line and the snout points to +x. The SVG viewport
  // ends just under the ground line, so whatever sinks below it is out of sight.
  const FIGURE = `
<svg class="mole-fig" viewBox="-46 -112 116 112.8" aria-hidden="true">
  <g class="m-body"><g class="m-life">
    <path class="m-ink" d="M-38 60L-38 0C-38 -40-31 -70-17 -88C-9 -98 0 -101 9 -101C17 -101 24 -95 26 -86C27.5 -79 25 -72 25.5 -62C26.5 -44 38 -30 38 0L38 60Z"/>
    <path class="m-tie" transform="translate(13 -54)" d="M-10.5 -6L-1.5 -1.2L-1.5 1.2L-10.5 6ZM10.5 -6L1.5 -1.2L1.5 1.2L10.5 6ZM-2.2 -2.2h4.4v4.4h-4.4Z"/>
    <g transform="translate(8 -86)"><g class="m-head"><g class="m-nose">
      <circle class="m-ink" r="15"/>
      <path class="m-ink" d="M8.6 -12.3C20 -14 32 -16 44 -17.5C34 -12 24 -7 14.8 -2.6L0 0Z"/>
      <g class="m-specs">
        <line class="m-bridge" x1="10.4" y1="-8" x2="15" y2="-10.6"/>
        <circle class="m-lens" cx="5.5" cy="-6" r="5.2"/>
        <circle class="m-lens" cx="19" cy="-12" r="4.4"/>
      </g>
    </g></g></g>
    <g transform="translate(36 -44) rotate(-7)"><g class="m-card">
      <rect x="-14" y="-10" width="28" height="20" rx="1.5"/>
      <path d="M-9 -4.5H5M-9 0H9M-9 4H2"/>
    </g></g>
  </g></g>
  <g class="m-dirt" transform="translate(44 0)">
    ${DIRT.map(p => `<g class="m-fx" style="--dx:${p.dx}px;--dy:${p.dy}px;--t:${p.t}s;--d:${p.d}s"><circle class="m-fy m-ink" r="${p.r}"/></g>`).join("")}
  </g>
  <line class="m-ground" x1="-44" y1="0" x2="66" y2="0"/>
</svg>`;

  function create(root) {
    root.classList.add("mole-bar");
    root.dataset.state = "hidden";
    root.setAttribute("role", "status");
    root.innerHTML = `${FIGURE}<div class="mole-text"><i class="mole-dot"></i><div class="mole-words"><div class="mole-title"></div><div class="mole-heard"></div></div></div>`;
    const title = root.querySelector(".mole-title"), heard = root.querySelector(".mole-heard");
    const slow = () => parseFloat(getComputedStyle(root).getPropertyValue("--slow")) || 1;

    function swap(text, animate) {
      if (title.lastElementChild?.textContent === text) return;
      const next = Object.assign(document.createElement("span"), { textContent: text });
      if (!animate) return title.replaceChildren(next);
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
    }

    function set(state, { title: t, heard: h } = {}) {
      if (!(state in TITLES)) throw new Error(`unknown mole state: ${state}`);
      const prev = root.dataset.state;
      if (state !== prev) {
        root.dataset.from = prev;
        root.dataset.state = state;
        t ??= TITLES[state] || undefined;
        if (prev === "hidden") h ??= "";
      }
      if (t !== undefined) swap(t, prev !== "hidden" && state !== "hidden");
      if (h !== undefined) heard.textContent = h.length > HEARD_MAX ? `…${h.slice(1 - HEARD_MAX).trimStart()}` : h;
    }

    return { el: root, set };
  }

  window.Mole = { create, states: Object.keys(TITLES) };
})();

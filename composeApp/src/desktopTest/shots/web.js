// Evaluated in mole/index.html by web.sh after `const SPEC = {...}` (one entry of shots.json). It stops the page's
// story, builds one fresh mole, plays SPEC.steps on a fake clock so the rig lands on exactly the time the Compose shot
// renders, and freezes every CSS transition and animation at its age at that moment.
(() => {
  run++;
  document.documentElement.dataset.theme = SPEC.theme;
  let now = performance.now();
  const queue = [];
  performance.now = () => now;
  window.requestAnimationFrame = cb => queue.push(cb);
  const words = key => ({
    hidden: ["hidden", {}],
    "choosing-two": ["choosing", CHOICES[0].words],
    "choosing-three": ["choosing", CHOICES[1].words],
    picked: ["picked", CHOICES[2].words],
    "choosing-kinds": ["choosing", {
      heard: "show them the launch",
      options: [{ name: "lauzhack.ch", meta: "Site", kind: "site" }, { name: "Team photo.jpg", meta: "Image", kind: "image" }, { name: "Notes.txt", meta: "File", kind: "other" }],
    }],
  })[key] ?? [key, { card: "07", ...SAMPLES[key] }];

  const handset = document.getElementById("phone");
  present("slide7"); // while the shared frame it copies is still in the page
  const stage = document.createElement("div");
  stage.style.cssText = "position: fixed; inset: 0; background: #dfe6dc";
  document.body.replaceChildren(stage);
  const host = document.createElement("div");
  if (SPEC.phone) {
    stage.style.background = "var(--paper)";
    handset.style.cssText = "position: absolute; left: -8px; top: -8px";
    handset.dataset.mic = SPEC.mic ?? "laptop";
    stage.append(handset);
    handset.querySelector("#pocketMole").replaceWith(host);
  } else {
    host.style.cssText = "position: absolute; left: 12px; bottom: 12px";
    stage.append(host);
  }
  const mole = Mole.create(host);

  const born = new Map();
  SPEC.steps.forEach(({ words: key, ms }, i) => {
    const before = new Set(document.getAnimations());
    mole.set(...words(key));
    getComputedStyle(host).opacity; // start this step's transitions now
    for (const a of document.getAnimations()) if (!before.has(a)) born.set(a, i);
    for (let n = Math.round((ms * 60) / 1000); n > 0; n--) {
      now += 1000 / 60;
      queue.splice(0).forEach(cb => cb(now));
    }
  });
  const ages = SPEC.steps.map((_, i) => SPEC.steps.slice(i).reduce((sum, s) => sum + s.ms, 0));
  for (const a of document.getAnimations()) {
    a.pause();
    a.currentTime = ages[born.get(a) ?? 0];
  }
  return "ok";
})();

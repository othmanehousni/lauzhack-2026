"""Meeting copilot: what the presenter says, mouths or types -> what the shared screen shows (a Chrome stage in a sandbox VM).

  uv run copilot.py live [--input mic|lips|keys] [--dry]   speech, silent lip reading or typed lines -> shared screen
  uv run copilot.py eval eval/*.jsonl [--tts]              scripted talks, replayed word by word -> accuracy and latency
  uv run copilot.py agent "GOAL"                           run the screen agent loop on its own
"""

import argparse
import base64
import json
import os
import queue
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

import vm
from brain import EMBED, OLLAMA, ROUTER, Action, Brain, Catalog, Intents, evaluate, healthy, ollama, serve
from ear import INPUTS, LIPREAD, Ear
from intents import INTENTS
from stage import CONTROLS, Stage, render, wallpaper
from vm import agent_desktop

VISION = "qwen3.5:4b-q4_K_M"  # Ollama tags are the GGUF builds: the default MLX tags reject JSON schemas
HERE = Path(__file__).parent


def chat_json(model: str, system: str, user: str, schema: dict, images: list[str] | None = None) -> dict:
    msg = {"role": "user", "content": user} | ({"images": images} if images else {})
    r = ollama("/api/chat", {
        "model": model, "stream": False, "think": False, "keep_alive": "1h", "format": schema,
        "options": {"temperature": 0, "num_ctx": 8192},
        "messages": [{"role": "system", "content": system}, msg],
    })
    return json.loads(r["message"]["content"])


FETCH_SYSTEM = """You put things on the shared screen for a presenter in a live video call. From what the presenter just asked for, pick ONE thing to do right away.
- kind "url": a website. Give the real URL when you know it; for a search, the site's search URL (Google's when no site is named: https://www.google.com/search?q=...).
- kind "file": a document, image or video on the presenter's Mac. Give 1 to 3 distinctive words likely to be in its file name, separated by spaces.
- kind "control": act on what is on screen now. value is one of: """ + ", ".join(CONTROLS) + """, or "click:" followed by the words of the link or button to click.
- kind "agent": anything that needs several steps inside an app."""
FETCH_SCHEMA = {"type": "object", "properties": {"kind": {"enum": ["url", "file", "control", "agent"]}, "value": {"type": "string"}},
                "required": ["kind", "value"]}

AGENT_SYSTEM = """You operate a macOS desktop that is screen-shared in a meeting. Reach the GOAL in as few steps as possible.
Each turn you get the UI elements of the focused window (ref, role, name) and a screenshot. Reply with ONE action:
- click: ref of the element to press
- type: ref of a text field and the text to put in it
- press: a key combo in text, e.g. "cmd+l", "return", "right"
- launch: an app name in text, e.g. "Safari", "Preview", "Finder"
- done: the goal is visible on screen
- fail: the goal cannot be reached
Keep reason under 15 words."""
AGENT_SCHEMA = {
    "type": "object",
    "properties": {
        "reason": {"type": "string"},
        "action": {"enum": ["click", "type", "press", "launch", "done", "fail"]},
        "ref": {"type": ["string", "null"]},
        "text": {"type": ["string", "null"]},
    },
    "required": ["reason", "action", "ref", "text"],
}


class Files:
    """Showable files in the folders vm.py mounts, so their paths are the same in the VM. Spotlight lists them once in
    the background (about 13 s for 150k files); after that a lookup is a scan of names in memory. Until then, and for
    files newer than the list, Spotlight answers directly in about 0.8 s."""
    KINDS = ["public.image", "public.movie", "com.adobe.pdf", "public.presentation", "public.spreadsheet", "public.composite-content", "public.html"]

    def __init__(self):
        self.places = [a for f in vm.FOLDERS for a in ("-onlyin", str(Path.home() / f))]
        self.names: list[tuple[str, str]] = []
        self.ready = threading.Event()
        threading.Thread(target=self.load, daemon=True).start()

    @staticmethod
    def wanted(path: str) -> bool:
        """Not inside a hidden folder, a package or a library of code."""
        parts = Path(path).parts[:-1]
        return not any(p.startswith(".") or p in ("node_modules", "site-packages", "Library") or p.endswith((".app", ".framework", ".bundle"))
                       for p in parts)

    def spotlight(self, query: str) -> list[str]:
        found = subprocess.run(["mdfind", *self.places, query], capture_output=True, text=True).stdout.splitlines()
        return [p for p in found if p and self.wanted(p)]

    def load(self) -> None:
        self.names = [(p.rsplit("/", 1)[-1].lower(), p) for p in self.spotlight(" || ".join(f'kMDItemContentTypeTree == "{k}"' for k in self.KINDS))]
        self.ready.set()

    def newest(self, n: int = 1500) -> list[str]:
        """The n most recently changed files, for brain.py to search by meaning."""
        self.ready.wait(60)
        # ponytail: newest by mtime only; a presenter's older file is still found by name through the vision model
        times = {p: t for _, p in self.names if (t := self.mtime(p))}
        return sorted(times, key=times.__getitem__, reverse=True)[:n]

    @staticmethod
    def mtime(path: str) -> float:
        try:
            return os.path.getmtime(path)
        except OSError:
            return 0.0

    def find(self, query: str) -> str | None:
        """Newest file whose name holds every word of the query."""
        words = query.lower().split()[:3]
        if not words:
            return None
        hits = [p for name, p in self.names if all(w in name for w in words)]
        hits = [h for h in hits or self.spotlight(" && ".join(f'kMDItemDisplayName == "*{w}*"cd' for w in words)) if os.path.exists(h)]
        return max(hits, key=os.path.getmtime) if hits else None


def walk(node: dict):
    yield node
    for child in node.get("children", []):
        yield from walk(child)


class Screen:
    """The shared screen: the Chrome stage (stage.py) for what the audience sees, and agent-desktop for the screen agent,
    in the sandbox VM or on this Mac when sandbox is None. With dry=True it only prints what it would do."""

    def __init__(self, sandbox: str | None, library: Path, dry: bool, vision: str = VISION):
        self.sandbox, self.dry, self.vision = sandbox, dry, vision
        self.stage = Stage(sandbox, dry)
        self.decks = {pdf.stem: render(pdf) for pdf in sorted(library.glob("*.pdf"))}
        self.files = Files()
        self.apps: list[str] = []

    def up(self) -> None:
        self.stage.up()
        self.stage.preload([png for pages in self.decks.values() for png in pages] + [wallpaper()])
        folders = "/Applications /System/Applications /System/Applications/Utilities"
        listing = vm.guest(self.sandbox, f"ls {folders}", check=False) if self.sandbox and not self.dry else \
            subprocess.run(f"ls {folders}", shell=True, capture_output=True, text=True).stdout
        self.apps = sorted({l[:-4] for l in listing.splitlines() if l.endswith(".app")})

    def do(self, a: Action, history: list[str]) -> Action:
        """Put `a` on screen. Returns what the screen then shows: for a request, what the vision model made of it."""
        if a.kind == "slide":
            doc, page = a.target.rsplit(" p", 1)
            pages, i = self.decks[doc], int(page) - 1
            self.stage.show(pages[i], pages[max(i - 1, 0):i] + pages[i + 1:i + 3])
        elif a.kind == "control":
            self.stage.control(a.target)
        elif a.kind == "close":
            self.stage.close()
        elif a.kind in ("url", "file"):
            self.stage.open(a.target)
        elif a.kind == "app":
            self.stage.app(a.target)
        else:
            return self.fetch(a.target, history)
        return a

    def fetch(self, request: str, history: list[str]) -> Action:
        """A request brain.py found no target for: the vision model turns it into a website, a file or a control in one
        step; anything else goes to the screen agent."""
        a = chat_json(self.vision, FETCH_SYSTEM, f"EARLIER: {' '.join(history[-3:])}\nREQUEST: {request}", FETCH_SCHEMA)
        kind, value = a["kind"], a["value"].strip()
        if kind == "file":
            value = self.files.find(value) or ""
        elif kind == "url" and not value.startswith(("http://", "https://")):
            value = "https://www.google.com/search?q=" + urllib.parse.quote_plus(request)
        elif kind == "control" and value.partition(":")[0] not in {*CONTROLS, "click"}:
            value = ""
        if kind == "agent" or not value:
            self.agent(request)
            return Action("ask", request)
        return self.do(Action(kind, value), history)

    def ad(self, *args: str) -> dict:
        if self.dry:
            print("   $ agent-desktop", " ".join(args))
            return {"ok": True}
        if self.sandbox:
            out = json.loads(vm.guest(self.sandbox, "~/.local/bin/agent-desktop " + shlex.join(args), check=False) or "{}")
        else:
            out = agent_desktop(*args)
        if not out.get("ok"):
            print("   ! agent-desktop", " ".join(args[:2]), "->", out.get("error", {}).get("code"), out.get("error", {}).get("message"))
        return out

    def screenshot_b64(self, width: int = 1024) -> str | None:
        data = self.ad("screenshot").get("data", {}).get("data")
        if not data:
            return None
        with tempfile.NamedTemporaryFile(suffix=".png") as f:
            f.write(base64.b64decode(data))
            f.flush()
            subprocess.run(["sips", "-Z", str(width), f.name], capture_output=True)
            return base64.b64encode(Path(f.name).read_bytes()).decode()

    def agent(self, goal: str, max_steps: int = 8, vision: bool = True) -> bool:
        """Observe (AX tree + screenshot) -> one JSON action -> act, until done."""
        log: list[str] = []
        for step in range(max_steps):
            t = time.perf_counter()
            snap = self.ad("snapshot", "-i", "--compact").get("data", {})
            elements = "\n".join(f"{n['ref_id']} {n.get('role', '')} {n.get('name', '')!r}"
                                 for n in walk(snap.get("tree", {})) if n.get("ref_id"))[:6000]
            images = [img] if vision and (img := self.screenshot_b64()) else None
            user = f"GOAL: {goal}\nDONE SO FAR: {'; '.join(log) or 'nothing'}\nWINDOW: {snap.get('app')} / {snap.get('window', {}).get('title')}\nUI ELEMENTS:\n{elements}"
            a = chat_json(self.vision, AGENT_SYSTEM, user, AGENT_SCHEMA, images)
            print(f"   step {step + 1}: {a['action']} {a.get('ref') or ''} {a.get('text') or ''}  ({a['reason']}) {time.perf_counter() - t:.1f}s")
            if a["action"] in ("done", "fail"):
                return a["action"] == "done"
            args = {"click": ["click", a["ref"] or ""], "type": ["set-value", a["ref"] or "", a["text"] or ""],
                    "press": ["press", a["text"] or ""], "launch": ["launch", a["text"] or "", "--activate"]}[a["action"]]
            self.ad(*args)
            self.stage.covered = True
            log.append(f"{a['action']} {a.get('ref') or ''} {a.get('text') or ''}".strip())
        return False


def preflight(args) -> None:
    """Cheap checks before anything heavy loads, one actionable line per problem; then make sure the VM is up."""
    problems = []
    try:
        with urllib.request.urlopen(f"{OLLAMA}/api/tags", timeout=3) as r:
            pulled = {m["name"] for m in json.load(r)["models"]}
        problems += [f"Ollama model {tag} is missing: ollama pull {tag}" for tag in (EMBED, args.vision) if (tag if ":" in tag else f"{tag}:latest") not in pulled]
    except OSError:
        problems.append(f"Ollama is not answering at {OLLAMA}: open the Ollama app or run `ollama serve`")
    if args.fallback and not shutil.which("llama-server") and not healthy():
        problems.append("the decision model needs llama-server: brew install llama.cpp")
    if not any(args.library.glob("*.pdf")):
        problems.append(f"no PDFs in {args.library}/: add your decks, or make sample ones with `uv run --group dev fixtures/make_decks.py {args.library}`")
    if args.input == "lips" and not (LIPREAD / "weights/lipread.safetensors").exists():
        problems.append("no lip-reading weights in lipread/weights/: uv run --with torch lipread/convert.py")
    if not args.dry and not shutil.which("agent-desktop"):
        problems.append("agent-desktop is not installed: npm install -g agent-desktop")
    if problems:
        sys.exit("\n".join(problems))
    if args.dry or not args.sandbox:
        return
    box = vm.up(args.sandbox)
    if box.get("state") != "running":
        setup = next(s for s in (HERE / "README.md").read_text().split("\n## ") if s.startswith("One-time VM setup"))
        sys.exit(f"sandbox {args.sandbox} is {box.get('state', 'missing')}. {box.get('message', '')} {box.get('next', '')}".strip() + f"\n\n## {setup}")
    page = shlex.quote(str((HERE / "stage.html").resolve()))
    if vm.guest(args.sandbox, f"test -r {page} && test -d '/Applications/Google Chrome.app' && echo ok", check=False).strip() != "ok":
        sys.exit(f"the VM cannot read {page} or has no Chrome: run `uv run vm.py`")


MARKS = {"live": "~", "pause": "…", "final": ">"}


def act(brain: Brain, screen: Screen, inbox: queue.Queue) -> None:
    """Acts on each transcript off the input thread, so listening never waits. Every transcript of the sentence being
    spoken goes to the brain, so an action fires as soon as its words are heard. A transcript is skipped when a
    newer one of the same sentence is already waiting."""
    seen = None
    while True:
        said, stopped, heard, kind = inbox.get()
        end, line = kind != "live", ""
        try:
            if (kind == "final" or inbox.empty()) and (said, end) != seen:
                seen = (said, end)
                t = time.perf_counter()
                d = brain.decide(said, end)
                line = f'{MARKS[kind]} "{said}"  [text {(heard - stopped) * 1e3:.0f}ms, decision {(time.perf_counter() - t) * 1e3:.0f}ms] {d.why}'
                if d.do:
                    brain.moved(screen.do(d.do, brain.history))
                    line += f"  -> {d.do}, {(time.perf_counter() - stopped) * 1e3:.0f}ms after you stopped"
                elif d.offer:
                    line += "  ? " + " | ".join(brain.describe(a) for a in d.offer)
                elif not end:
                    line = ""
        except Exception as e:
            line += f"  ! {type(e).__name__}: {e}"
        if line:
            print(line)
        if kind == "final":
            brain.heard(said)
            seen = None
        inbox.task_done()


def run_live(args) -> None:
    preflight(args)
    if args.fallback:
        serve(args.router)
    screen = Screen(args.sandbox, args.library, args.dry, args.vision)
    screen.up()
    catalog, intents = Catalog(args.library, screen.apps, screen.files.newest()), Intents(INTENTS)
    print(f"{len(catalog.slides)} slides in {args.library}, {len(catalog.files)} files from Spotlight, {len(catalog.places)} apps and websites")
    Brain(catalog, intents, fallback=False).decide("Hi everyone, can you all hear me?", end=True)  # warm-up: loads the embedding model
    brain = Brain(catalog, intents, args.threshold, args.any_language, args.fallback)
    inbox: queue.Queue = queue.Queue()
    threading.Thread(target=act, args=(brain, screen, inbox), daemon=True).start()
    for said, stopped, kind in INPUTS[args.input](args, catalog.vocabulary):
        if said:
            inbox.put((said, stopped, time.perf_counter(), kind))
    inbox.join()


def run_eval(args) -> None:
    if args.fallback:
        serve(args.router)
    intents = Intents(INTENTS)
    if not args.tts:
        return evaluate(args.scripts, args.library, intents, args.threshold, args.step, fallback=args.fallback)
    ear = Ear()

    def hear(text: str) -> str:
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(["say", "-o", f"{tmp}/say.aiff", text], check=True)
            return ear.file(f"{tmp}/say.aiff")

    evaluate(args.scripts, args.library, intents, args.threshold, 0, hear, args.fallback)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--library", type=Path, default=Path("library"))
    p.add_argument("--sandbox", default="base")
    p.add_argument("--host", action="store_true", help="drive this Mac instead of the sandbox VM: the stage is a Chrome window here, and the screen agent clicks and types here")
    p.add_argument("--threshold", type=float, default=0.5, help="how likely the likeliest kind of request must be to act on it; "
                   "a less likely one goes to the decision model")
    p.add_argument("--router", default=ROUTER, help="decision model GGUF for llama-server, asked when the intent classifier is "
                   "unsure and started unless D1_HOST answers; none to go without it")
    p.add_argument("--vision", default=VISION, help="Ollama model for requests brain.py finds no target for, and for the screen agent")
    sub = p.add_subparsers(dest="cmd", required=True)
    live = sub.add_parser("live")
    live.add_argument("--input", choices=INPUTS, default="mic", help="microphone (Apple's on-device speech recognizer), the "
                      "microphone through Parakeet on MLX, silent lip reading from the webcam, or typed lines")
    live.add_argument("--dry", action="store_true", help="print VM commands instead of running them")
    live.add_argument("--camera", type=int, default=0, help="webcam index for --input lips")
    live.add_argument("--preview", action="store_true", help="with --input lips, show the mouth crop and the lip-motion level")
    live.add_argument("--sensitivity", type=float, help="with --input lips, how far lip motion must rise above still-lip jitter")
    live.add_argument("--focus", type=float, default=0.3, help="with --input parakeet, how loud a sound must be next to your recent speech "
                      "to count (0.3 ignores voices about 10 dB quieter than yours; lower it if your own words get cut)")
    live.add_argument("--any-language", action="store_true", help="act on every language, not only English")
    ev = sub.add_parser("eval")
    ev.add_argument("scripts", type=Path, nargs="+")
    ev.add_argument("--step", type=int, default=2, help="decide on the sentence so far every N words, as the microphone streams it; 0 for whole lines only")
    ev.add_argument("--tts", action="store_true", help="speak each line with `say` and transcribe it back (whole lines)")
    ag = sub.add_parser("agent")
    ag.add_argument("goal")
    ag.add_argument("--no-vision", action="store_true")
    args = p.parse_args()
    args.sandbox = None if args.host else args.sandbox
    args.fallback = args.router != "none"
    try:
        if args.cmd == "live":
            run_live(args)
        elif args.cmd == "eval":
            run_eval(args)
        else:
            screen = Screen(args.sandbox, args.library, False, args.vision)
            print("done" if screen.agent(args.goal, vision=not args.no_vision) else "not done")
    except KeyboardInterrupt:
        print()


if __name__ == "__main__":
    main()

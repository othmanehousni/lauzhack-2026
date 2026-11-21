"""Meeting copilot: what the presenter says, mouths or types -> intent -> shared screen (a Chrome stage in a sandbox VM).

  uv run copilot.py live [--input mic|lips|keys] [--dry]   speech, silent lip reading or typed lines -> shared screen
  uv run copilot.py eval eval/talk.jsonl [--tts]           scripted talk -> accuracy and latency
  uv run copilot.py agent "GOAL"                           run the screen agent loop on its own
"""

import argparse
import atexit
import base64
import collections
import json
import os
import queue
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from pypdf import PdfReader

import vm
from commands import Action, Catalog, Commands
from stage import CONTROLS, Stage, render, wallpaper
from vm import agent_desktop

OLLAMA = os.environ.get("OLLAMA_HOST", "http://127.0.0.1:11434")
ROUTER, VISION, EMBED = "d1", "qwen3.5:4b-q4_K_M", "embeddinggemma"  # Ollama tags are the GGUF builds: the default MLX tags reject JSON schemas
STT = "mlx-community/parakeet-tdt-0.6b-v3"
HERE = Path(__file__).parent
LIPREAD = HERE / "lipread"
NEXT = re.compile(r"\b(next slide|next one|next page|go forward|moving on|move on|let's continue)\b(?!\s+to\b)", re.I)
PREV = re.compile(r"\b(previous slide|last slide|go back one|back one slide)\b", re.I)
# Things said to the screen itself, acted on with no model. First match wins, so exit_fullscreen sits before fullscreen.
SAID_TO_SCREEN = [(action, re.compile(pattern, re.I)) for action, pattern in [
    ("scroll_down", r"\bscroll(?:ing)? (?:down|further)\b|\bkeep scrolling\b"),
    ("scroll_up", r"\bscroll(?:ing)? (?:back )?up\b"),
    ("top", r"\b(?:scroll|go) (?:back )?(?:up )?to the top\b"),
    ("zoom_in", r"\bzoom in\b"),
    ("zoom_out", r"\bzoom (?:back )?out\b"),
    ("pause", r"\bpause (?:the|this|that|it)\b"),
    ("play", r"\b(?:play|start|hit play on) (?:the|this|that) video\b|\bhit play\b"),
    ("exit_fullscreen", r"\b(?:exit|leave|get out of) full ?screen\b"),
    ("fullscreen", r"\b(?:make (?:it|this|that) |go )full ?screen\b|\bmaximi[sz]e (?:it|this|that|the video)\b"),
]]


def ollama(path: str, body: dict) -> dict:
    req = urllib.request.Request(f"{OLLAMA}{path}", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.load(r)


def embed(texts: list[str]) -> np.ndarray:
    v = np.array(ollama("/api/embed", {"model": EMBED, "input": texts, "keep_alive": "1h"})["embeddings"], np.float32)
    return v / np.linalg.norm(v, axis=1, keepdims=True)


def chat_json(model: str, system: str, user: str, schema: dict, images: list[str] | None = None) -> dict:
    msg = {"role": "user", "content": user} | ({"images": images} if images else {})
    r = ollama("/api/chat", {
        "model": model, "stream": False, "think": False, "keep_alive": "1h", "format": schema,
        "options": {"temperature": 0, "num_ctx": 8192},
        "messages": [{"role": "system", "content": system}, msg],
    })
    return json.loads(r["message"]["content"])


@dataclass(frozen=True)
class Slide:
    doc: str
    page: int
    title: str
    text: str


class Library:
    """Every PDF page in a folder, embedded once at startup."""

    def __init__(self, folder: Path):
        self.folder = folder
        self.slides: list[Slide] = []
        for pdf in sorted(folder.glob("*.pdf")):
            for n, page in enumerate(PdfReader(pdf).pages, 1):
                lines = [l.strip() for l in (page.extract_text() or "").splitlines() if l.strip()]
                self.slides.append(Slide(pdf.stem, n, lines[0] if lines else f"page {n}", " ".join(lines)))
        self.docs = sorted({s.doc for s in self.slides})
        self.vecs = embed([f"title: {s.doc.replace('_', ' ')} | text: {s.text}" for s in self.slides])
        self.by_id = {self.sid(s): s for s in self.slides}

    def count(self, doc: str) -> int:
        return sum(s.doc == doc for s in self.slides)

    def slide(self, doc: str, page: int) -> Slide:
        return next(s for s in self.slides if s.doc == doc and s.page == page)

    def match(self, query: str, k: int = 4) -> list[tuple[Slide, float]]:
        scores = self.vecs @ embed([f"task: search result | query: {query}"])[0]
        return [(self.slides[i], float(scores[i])) for i in np.argsort(-scores)[:k]]

    @staticmethod
    def sid(s: Slide) -> str:
        return f"{s.doc} p{s.page}"

    def outline(self, slides: list[Slide] | None = None) -> str:
        return "\n".join(f"[{self.sid(s)}] {s.text[:220]}" for s in slides or self.slides)

    def shortlist(self, onair: "OnAir | None", query: str, k: int = 6) -> list[Slide]:
        """Slides worth considering: neighbours of the one on air, best semantic matches, and every deck's cover."""
        keep = {self.sid(s) for s, _ in self.match(query, k)} | {f"{d} p1" for d in self.docs}
        if onair:
            keep |= {f"{onair.doc} p{onair.page + d}" for d in (-1, 0, 1, 2)}
        return [s for s in self.slides if self.sid(s) in keep]


@dataclass(frozen=True)
class OnAir:
    """What the audience sees. `None` in its place means nothing is shared (or an unknown app/page is)."""
    doc: str
    page: int


def label(lib: Library, onair: OnAir | None) -> str:
    if not onair:
        return "no slide is shared"
    return f'[{onair.doc} p{onair.page}] "{lib.slide(onair.doc, onair.page).title}"'


def state(onair: OnAir | None) -> tuple:
    return (onair.doc, onair.page) if onair else (None, None)


ROUTER_SYSTEM = """You run the SHARED SCREEN for a presenter in a live video call. After each new sentence of the presenter's speech, decide whether the shared screen should change.

Output intent "show" with the slide id when the presenter wants a slide shown NOW:
- explicit requests: "let's open the company slides", "pull up the Q3 numbers", "go back to the demo"
- implicit transitions: the presenter starts talking about content that is on a different slide than the one on air ("so how do we make money?" goes to the pricing slide)
Output intent "none" when:
- the new sentence fits the slide already on air
- it only MENTIONS something without asking to show it: "as I said earlier about pricing", "our CFO will send the financials later", "I'll show the demo in a minute"
- it is small talk ("can you hear me?", "thanks everyone")
Output "other" only when the presenter wants something on screen that is NOT one of the slides below (a website, an app, a file), and describe it in goal.
Opening a deck without a specific topic means its p1. Pick the slide whose content matches what is being said.
confidence: 0.0-1.0, how sure you are the presenter wants this on screen right now. Keep reason under 15 words.

SLIDES ([id] content):
"""


def llm_route(lib: Library, onair: OnAir | None, history: list[str], said: str) -> dict:
    candidates = "\n".join(f"- [{lib.sid(s)}] (match {score:.2f})" for s, score in lib.match(" ".join([*history[-1:], said])))
    recent = "\n".join(f"  {h}" for h in history[-4:]) or "  (start of talk)"
    user = f"ON AIR: {label(lib, onair)}\nCLOSEST SLIDES BY KEYWORDS:\n{candidates}\nEARLIER SPEECH:\n{recent}\nNEW SENTENCE: \"{said}\""
    schema = {
        "type": "object",
        "properties": {
            "reason": {"type": "string"},
            "intent": {"enum": ["none", "show", "other"]},
            "slide": {"enum": [*lib.by_id, "none"]},
            "goal": {"type": ["string", "null"]},
            "confidence": {"type": "number"},
        },
        "required": ["reason", "intent", "slide", "goal", "confidence"],
    }
    return chat_json(ROUTER, ROUTER_SYSTEM + lib.outline(), user, schema)


D1 = os.environ.get("D1_HOST", "http://127.0.0.1:8090")
D1_MODEL = "LiquidAI/d1-2B-GGUF:Q8_0"
SHORTLIST = os.environ.get("SHORTLIST", "1") == "1"
VERIFY = os.environ.get("VERIFY", "0") == "1"  # a second d1 pass per switch: +0.5 s, and it never vetoed one in a live talk


def d1_route(lib: Library, onair: OnAir | None, history: list[str], said: str) -> dict:
    """Liquid d1 decision model: one forward pass, calibrated probabilities instead of generated text."""
    said = said[-300:]  # the router's time grows with the prompt; the end of a long utterance is what matters
    recent = "\n".join(h[-160:] for h in history[-2:]) or "(start of talk)"
    slides = lib.shortlist(onair, " ".join([*history[-1:], said])) if SHORTLIST else lib.slides
    body = {
        "state": f"SLIDES:\n{lib.outline(slides)}\n\nON AIR: {label(lib, onair)}\n\nEARLIER SPEECH:\n{recent}\n\nNEW SENTENCE: {said}",
        "questions": {"screen": {"type": "choice", "criteria": {
            "keep": "Keep the screen as it is: the new sentence fits what is on air, is small talk, or only mentions something without moving on to it",
            "other": "Show something that is not one of these slides (a website, an image, a video, a file or an app), or act on what is on screen (scroll, zoom, play, pause, go back, click a link)",
            **{lib.sid(s): s.title for s in slides}},
            "instructions": "The presenter is screen-sharing slides in a live call. What should the shared screen show while the presenter says the NEW SENTENCE?"}},
    }
    a = d1_ask(body)["screen"]
    p = a["probabilities"]
    keep, other = p.get("keep", 0.0), p.get("other", 0.0)
    if a["choice"] == "other":
        # d1 picks options, it does not write text: the vision model turns the presenter's words into a URL, file or control.
        # Plain requests ("open YouTube") never get here; commands.py reads them first.
        return {"intent": "other", "slide": "none", "goal": said, "confidence": round(other, 2), "reason": f"P(other)={other:.2f}"}
    best = max((k for k in p if k not in ("keep", "other")), key=p.get)
    raw = {"intent": "none" if a["choice"] == "keep" else "show", "slide": best,
           "confidence": round(1 - keep - other, 2), "reason": f"P(keep)={keep:.2f} best={best} {p[best]:.2f}"}
    if raw["intent"] == "show" and VERIFY and (not onair or best != lib.sid(lib.slide(onair.doc, onair.page))):
        target = lib.by_id[best]
        moving = d1_ask({"state": body["state"] + f"\n\nCANDIDATE SLIDE: [{best}] {target.text[:220]}", "questions": {"moving": {
            "type": "noul", "instructions": "Is the presenter moving on to the CANDIDATE SLIDE's topic right now, so the audience "
            "should see it? Answer no if the NEW SENTENCE only refers back to it, postpones it, skips it, or mentions it in passing."}}})["moving"]["noul"]
        raw |= {"confidence": round(min(1 - keep - other, moving), 2), "reason": raw["reason"] + f" P(moving)={moving:.2f}"}
    return raw


def d1_ask(body: dict) -> dict:
    req = urllib.request.Request(f"{D1}/v1/systemone", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)["answers"]


def d1_healthy() -> bool:
    try:
        with urllib.request.urlopen(f"{D1}/health", timeout=2) as r:
            return r.status == 200
    except OSError:
        return False


def serve_d1() -> None:
    """With the d1 router, start llama-server unless one already answers at D1_HOST. It is killed when we exit."""
    if ROUTER != "d1" or d1_healthy():
        return
    url = urllib.parse.urlsplit(D1)
    # /v1/systemone reads the whole prompt in one batch, so the batch must hold the full context (the default 512 fails).
    cmd = ["llama-server", "-hf", D1_MODEL, "--host", url.hostname, "--port", str(url.port), "-c", "8192", "-b", "8192", "-ub", "8192", "-np", "1"]
    usage = subprocess.run(["llama-server", "--help"], capture_output=True, text=True)
    if "--offline" in usage.stdout + usage.stderr:
        cmd.append("--offline")
    log = tempfile.NamedTemporaryFile("w", prefix="d1-server-", suffix=".log", delete=False)
    print(f"starting the d1 router, log in {log.name}")
    server = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
    atexit.register(server.terminate)
    deadline = time.monotonic() + 120
    while not d1_healthy():
        if server.poll() is not None or time.monotonic() > deadline:
            sys.exit(f"the d1 router did not come up, see {log.name}")
        time.sleep(0.5)


@dataclass(frozen=True)
class Decision:
    tier: str
    raw: dict
    ms: float
    show: OnAir | None = None  # slide to put on air
    goal: str | None = None  # something that is not a slide: fetch() turns it into a website, file, control or agent job
    control: str | None = None  # a CONTROLS action heard verbatim


def decide(lib: Library, onair: OnAir | None, history: list[str], said: str, threshold: float) -> Decision:
    t = time.perf_counter()
    heard = next((action for action, pattern in SAID_TO_SCREEN if pattern.search(said)), None)
    if heard:
        return Decision("keyword", {"intent": "control", "control": heard, "confidence": 1.0}, (time.perf_counter() - t) * 1e3, control=heard)
    if onair and (NEXT.search(said) or PREV.search(said)):
        tier, raw = "keyword", {"intent": "show", "confidence": 1.0}
        s = lib.slide(onair.doc, min(max(onair.page + (1 if NEXT.search(said) else -1), 1), lib.count(onair.doc)))
    else:
        tier, raw = "router", (d1_route if ROUTER == "d1" else llm_route)(lib, onair, history, said)
        s = lib.by_id.get(raw.get("slide", ""))
    sure = raw.get("confidence", 0) >= threshold
    show = OnAir(s.doc, s.page) if sure and raw["intent"] == "show" and s else None
    goal = raw.get("goal") if sure and raw["intent"] == "other" else None
    return Decision(tier, raw, (time.perf_counter() - t) * 1e3, show if show != onair else None, goal or None)


def phrases(said: str) -> list[Action]:
    """Controls and next/previous slide said in so many words: acted on with no model, even mid-sentence."""
    out = [Action("control", action) for action, pattern in SAID_TO_SCREEN if pattern.search(said)][:1]
    if NEXT.search(said) or PREV.search(said):
        out.append(Action("step", "1" if NEXT.search(said) else "-1"))
    return out


class Screen:
    """The shared screen: the Chrome stage (stage.py) for what the audience sees, and agent-desktop for the screen agent,
    in the sandbox VM or on this Mac when sandbox is None. With dry=True it only prints what it would do."""

    def __init__(self, sandbox: str | None, library: Path, dry: bool):
        self.sandbox, self.dry = sandbox, dry
        self.stage = Stage(sandbox, dry)
        self.decks = {pdf.stem: render(pdf) for pdf in sorted(library.glob("*.pdf"))}
        self.apps: list[str] = []

    def up(self) -> None:
        self.stage.up()
        self.stage.preload([png for pages in self.decks.values() for png in pages] + [wallpaper()])
        folders = "/Applications /System/Applications /System/Applications/Utilities"
        listing = vm.guest(self.sandbox, f"ls {folders}", check=False) if self.sandbox and not self.dry else \
            subprocess.run(f"ls {folders}", shell=True, capture_output=True, text=True).stdout
        self.apps = sorted({l[:-4] for l in listing.splitlines() if l.endswith(".app")})

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

    def show(self, target: OnAir) -> None:
        pages, i = self.decks[target.doc], target.page - 1
        self.stage.show(pages[i], pages[max(i - 1, 0):i] + pages[i + 1:i + 3])

    def screenshot_b64(self, width: int = 1024) -> str | None:
        shot = self.ad("screenshot")
        data = shot.get("data", {}).get("data")
        if not data:
            return None
        with tempfile.NamedTemporaryFile(suffix=".png") as f:
            f.write(base64.b64decode(data))
            f.flush()
            subprocess.run(["sips", "-Z", str(width), f.name], capture_output=True)
            return base64.b64encode(Path(f.name).read_bytes()).decode()


def walk(node: dict):
    yield node
    for child in node.get("children", []):
        yield from walk(child)


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


def agent(screen: Screen, goal: str, max_steps: int = 8, vision: bool = True) -> bool:
    """Observe (AX tree + screenshot) -> one JSON action -> act, until done."""
    log: list[str] = []
    for step in range(max_steps):
        t = time.perf_counter()
        snap = screen.ad("snapshot", "-i", "--compact").get("data", {})
        elements = "\n".join(f"{n['ref_id']} {n.get('role', '')} {n.get('name', '')!r}"
                             for n in walk(snap.get("tree", {})) if n.get("ref_id"))[:6000]
        images = [img] if vision and (img := screen.screenshot_b64()) else None
        user = f"GOAL: {goal}\nDONE SO FAR: {'; '.join(log) or 'nothing'}\nWINDOW: {snap.get('app')} / {snap.get('window', {}).get('title')}\nUI ELEMENTS:\n{elements}"
        a = chat_json(VISION, AGENT_SYSTEM, user, AGENT_SCHEMA, images)
        print(f"   step {step + 1}: {a['action']} {a.get('ref') or ''} {a.get('text') or ''}  ({a['reason']}) {time.perf_counter() - t:.1f}s")
        if a["action"] in ("done", "fail"):
            return a["action"] == "done"
        args = {"click": ["click", a["ref"] or ""], "type": ["set-value", a["ref"] or "", a["text"] or ""],
                "press": ["press", a["text"] or ""], "launch": ["launch", a["text"] or "", "--activate"]}[a["action"]]
        screen.ad(*args)
        screen.stage.covered = True
        log.append(f"{a['action']} {a.get('ref') or ''} {a.get('text') or ''}".strip())
    return False


FETCH_SYSTEM = """You put things on the shared screen for a presenter in a live video call. From what the presenter just asked for, pick ONE thing to do right away.
- kind "url": a website. Give the real URL when you know it; otherwise a Google search URL (https://www.google.com/search?q=...) for what they want to see.
- kind "file": a document, image or video on the presenter's Mac. Give 1 to 3 distinctive words likely to be in its file name, separated by spaces.
- kind "control": act on what is on screen now. value is one of: """ + ", ".join(CONTROLS) + """, or "click:" followed by the words of the link or button to click.
- kind "agent": anything that needs several steps inside an app."""
FETCH_SCHEMA = {"type": "object", "properties": {"kind": {"enum": ["url", "file", "control", "agent"]}, "value": {"type": "string"}},
                "required": ["kind", "value"]}


class Files:
    """Showable files in the folders vm.py mounts, so their paths are the same in the VM. Spotlight lists them once in
    the background (about 13 s for 150k files); after that a lookup is a scan of names in memory. Until then, and for
    files newer than the list, Spotlight answers directly in about 0.8 s."""
    KINDS = ["public.image", "public.movie", "com.adobe.pdf", "public.presentation", "public.spreadsheet", "public.composite-content", "public.html"]
    JUNK = re.compile(r"/(?:\.|node_modules/|site-packages/|Library/)|\.(?:app|framework|bundle)/")

    def __init__(self):
        self.places = [a for f in vm.FOLDERS for a in ("-onlyin", str(Path.home() / f))]
        self.names: list[tuple[str, str]] = []
        threading.Thread(target=self.load, daemon=True).start()

    def load(self) -> None:
        query = " || ".join(f'kMDItemContentTypeTree == "{k}"' for k in self.KINDS)
        found = subprocess.run(["mdfind", *self.places, query], capture_output=True, text=True).stdout.splitlines()
        self.names = [(p.rsplit("/", 1)[-1].lower(), p) for p in found if not self.JUNK.search(p)]

    def find(self, words: str) -> str | None:
        """Newest file whose name holds every word."""
        words = [w.lower() for w in re.findall(r"[\w-]+", words)][:3]
        if not words:
            return None
        hits = [p for name, p in self.names if all(w in name for w in words)]
        if not hits:
            query = " && ".join(f'kMDItemDisplayName == "*{w}*"cd' for w in words)
            hits = [h for h in subprocess.run(["mdfind", *self.places, query], capture_output=True, text=True).stdout.splitlines()
                    if h and not self.JUNK.search(h)]
        hits = [h for h in hits if os.path.exists(h)]
        return max(hits, key=os.path.getmtime) if hits else None


def fetch(screen: Screen, files: Files, goal: str, history: list[str]) -> tuple[str, str]:
    """A request for something that is not a slide: open a website or file, or act on the tab on screen, in one step;
    anything else is left to the screen agent."""
    a = chat_json(VISION, FETCH_SYSTEM, f"EARLIER: {' '.join(history[-3:])}\nREQUEST: {goal}", FETCH_SCHEMA)
    kind, value = a["kind"], a["value"].strip()
    if kind == "file":
        value = files.find(value) or ""
        kind = "file" if value else "agent"
    if kind == "control" and value.partition(":")[0] not in {*CONTROLS, "click"}:
        kind = "agent"
    if kind == "url" and not value.startswith(("http://", "https://")):
        value = "https://www.google.com/search?q=" + urllib.parse.quote_plus(goal)
    if kind == "control":
        screen.stage.control(value)
    elif kind != "agent":
        kind = screen.stage.open(value)
    return kind, value


ENGLISH = set("""the a an and or but so to of in on at for with is are was were be been it it's this that these those i i'm
you we they he she my our your their me us them not no yes can will would should could do does did have has had let's
lets what how why where when which there here just like okay well now then if as from by about up out go going get want
think see look know mean really very more one two all some""".split())


def english(text: str) -> bool:
    """Whether a transcript reads as English: a fifth of its words are common English ones. Side talk in other languages
    and far-off voices that come out as garbled words fail it. Too short to tell passes."""
    ws = re.findall(r"[a-z']+", text.lower())
    return len(ws) < 4 or sum(w in ENGLISH for w in ws) / len(ws) >= 0.2


class Ear:
    """Speech to text with Parakeet on MLX: streamed from the microphone, or one file at a time for evals."""

    def __init__(self):
        from parakeet_mlx import from_pretrained
        self.model = from_pretrained(STT)
        self.sr = self.model.preprocessor_config.sample_rate
        self.text(np.zeros(self.sr, np.float32))  # warm-up: the first pass compiles kernels
        import mlx.core as mx
        with self.model.transcribe_stream(context_size=(256, 16)) as warm:
            warm.add_audio(mx.zeros(self.sr // 2))

    def text(self, audio) -> str:
        import mlx.core as mx
        from parakeet_mlx.audio import get_logmel
        return self.model.generate(get_logmel(mx.array(audio), self.model.preprocessor_config))[0].text.strip()

    def file(self, path: str) -> str:
        from parakeet_mlx.audio import load_audio
        return self.text(load_audio(Path(path), self.sr))

    def microphone(self, focus: float = 0.3, early_s: float = 0.3, pause_s: float = 0.6, max_s: float = 15.0):
        """Yields (text, when you stopped, kind): "live" each time the words so far change, "pause" at the first 0.3 s
        pause, "final" after 0.6 s. Audio is transcribed as it arrives (Parakeet's streaming mode), so the text is ready
        the moment you stop. A sound counts as speech when it is at least `focus` times as loud as your recent speech,
        so voices across the room neither open the gate nor hold it open."""
        import mlx.core as mx
        import sounddevice as sd
        blocks: queue.Queue = queue.Queue()
        block = self.sr // 10
        floor, voice, before = 0.003, 0.0, collections.deque(maxlen=3)
        stream, fed, levels, n, quiet, early, text, last_loud = None, [], [], 0, 0, False, "", 0.0
        with sd.InputStream(samplerate=self.sr, channels=1, dtype="float32", blocksize=block,
                            callback=lambda d, *_: blocks.put(d[:, 0].copy())):
            print("listening... (ctrl-c to stop)")
            while True:
                b = blocks.get()
                rms = float(np.sqrt(np.mean(b ** 2)))
                loud = rms > max(3 * floor, focus * voice, 0.01)
                if stream is None:
                    # ponytail: energy gate with a slow noise floor; swap for Silero VAD if rooms get noisy
                    floor = 0.995 * floor + 0.005 * rms
                    before.append(b)
                    if not loud:
                        continue
                    stream = self.model.transcribe_stream(context_size=(256, 16)).__enter__()
                    fed, levels, n, quiet, early, text = list(before), [], 0, 0, False, ""  # with the word's quiet start
                else:
                    fed.append(b)
                n += 1
                if loud:
                    levels.append(rms)
                    last_loud, quiet, early = time.perf_counter(), 0, False
                else:
                    quiet += 1
                pause = not early and quiet * 0.1 >= early_s
                end = quiet * 0.1 >= pause_s or n * 0.1 >= max_s
                if fed and (len(fed) >= 2 or pause or end):
                    stream.add_audio(mx.array(np.concatenate(fed)))
                    fed = []
                    words = stream.result.text.strip()
                    if words != text and len(levels) > 2:
                        text = words
                        yield text, last_loud, "live"
                if pause and len(levels) > 3:
                    early = True
                    yield text, last_loud, "pause"
                if end:
                    if text:  # every utterance that yielded words ends with a final, which resets the brain's memory of it
                        yield text, last_loud, "final"
                        level = float(np.percentile(levels, 80))
                        if level >= 0.6 * voice:  # learn your level from your own speech, not from quieter voices
                            voice = level if voice == 0 else 0.8 * voice + 0.2 * level
                    stream.__exit__(None, None, None)
                    stream = None
                    before.clear()


def keys(args):
    print("type what you would say, one sentence per line (ctrl-d to stop)")
    for line in sys.stdin:
        yield line.strip(), time.perf_counter()


def lips(args):
    sys.path.insert(0, str(LIPREAD))
    import lipread
    print("mouth words at the camera (ctrl-c to stop)")
    settings = lipread.Settings() if args.sensitivity is None else lipread.Settings(sensitivity=args.sensitivity)
    yield from lipread.listen(settings, args.camera, args.preview)


# Each input yields (text, perf_counter time the speaker stopped[, kind]), stamped at the last voiced sound so latencies are
# honest. kind is "live" (the words so far), "pause" (a short pause mid-utterance) or "final" (the default).
INPUTS = {"mic": lambda args: Ear().microphone(args.focus), "lips": lips, "keys": keys}


def preflight(args) -> None:
    """Cheap checks before anything heavy loads, one actionable line per problem; then make sure the VM is up."""
    problems = []
    try:
        with urllib.request.urlopen(f"{OLLAMA}/api/tags", timeout=3) as r:
            pulled = {m["name"] for m in json.load(r)["models"]}
        need = [EMBED, VISION] + ([] if ROUTER == "d1" else [ROUTER])
        problems += [f"Ollama model {tag} is missing: ollama pull {tag}" for tag in need if (tag if ":" in tag else f"{tag}:latest") not in pulled]
    except OSError:
        problems.append(f"Ollama is not answering at {OLLAMA}: open the Ollama app or run `ollama serve`")
    if ROUTER == "d1" and not shutil.which("llama-server") and not d1_healthy():
        problems.append("the d1 router needs llama-server: brew install llama.cpp")
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


def brain(lib: Library, screen: Screen, files: Files, commands: Commands, inbox: queue.Queue, threshold: float,
          any_language: bool) -> None:
    """Acts on each transcript off the input thread, so listening never waits. Phrases and commands act on every
    transcript, mid-sentence included; the router runs at a pause or the end, and only if nothing was done yet in this
    utterance. A transcript is skipped when a newer one of the same utterance is already waiting."""
    onair: OnAir | None = None  # the slide the audience sees
    last: OnAir | None = None  # the last slide shown, for "back to the slides"
    history: list[str] = []
    done: set[Action] = set()
    routed = ""

    def show(target: OnAir) -> str:
        nonlocal onair, last
        onair = None  # unknown if the screen action fails half way
        screen.show(target)
        onair = last = target
        commands.onair_deck = target.doc
        return f"ON AIR {label(lib, target)}"

    def act(a: Action) -> str:
        nonlocal onair
        if a.kind == "slide":
            s = lib.by_id.get(a.target)
            target = OnAir(s.doc, s.page) if s else last
            return show(target) if target and target != onair else ""
        if a.kind == "step":
            if not onair:
                return ""
            page = min(max(onair.page + int(a.target), 1), lib.count(onair.doc))
            return show(OnAir(onair.doc, page)) if page != onair.page else ""
        if a.kind == "control":
            screen.stage.control(a.target)
            return a.target
        if a.kind == "close":
            tab = a.target == "front" and screen.stage.front not in ("", screen.stage.home)
            screen.stage.close(a.target)
            onair = last if tab else None
            return "closed the tab" if tab else "slides closed"
        onair = None
        if a.kind == "url":
            screen.stage.open(a.target)
            return a.target
        if a.kind == "app":
            screen.stage.app(a.target)
            return f"app {a.target}"
        path = files.find(a.target) if a.kind == "file" else None
        if path:
            return f"{screen.stage.open(path)} {path}"
        kind, value = fetch(screen, files, a.target if a.kind == "ask" else " ".join(history[-1:] + [a.target]), history)
        if kind == "agent":
            agent(screen, a.target)
        return f"{kind} {value}"

    while True:
        said, stopped, heard, kind = inbox.get()
        final, line = kind == "final", ""
        try:
            if (final or inbox.empty()) and said and (any_language or english(said)):
                line = f'{ {"live": "~", "pause": "…", "final": ">"}[kind]} "{said}"  [text {(heard - stopped) * 1e3:.0f}ms]'
                todo = [a for a in dict.fromkeys(phrases(said) + commands.read(said, final)) if a not in done]
                if not todo and not done and kind != "live" and said != routed:
                    routed = said
                    d = decide(lib, onair, history, said, threshold)
                    line += f' [{d.tier} {d.ms:.0f}ms] {d.raw.get("intent")} {d.raw.get("confidence", "")} {d.raw.get("reason", "")}'
                    todo = ([Action("control", d.control)] if d.control else [Action("slide", f"{d.show.doc} p{d.show.page}")]
                            if d.show else [Action("ask", d.goal)] if d.goal else [])
                for a in todo:
                    done.add(a)
                    did = act(a)
                    if did:
                        line += f"  -> {did} {(time.perf_counter() - stopped) * 1e3:.0f}ms after you stopped"
                if kind == "live" and not todo:
                    line = ""
        except Exception as e:
            line += f"  ! {type(e).__name__}: {e}"
        if line:
            print(line)
        if final:
            if any_language or english(said):
                history.append(said)
            done.clear()
            routed = ""
        inbox.task_done()


def run_live(args) -> None:
    preflight(args)
    serve_d1()
    lib = Library(args.library)
    screen = Screen(args.sandbox, args.library, args.dry)
    screen.up()
    commands = Commands(Catalog.build([(s.doc, s.page, s.title) for s in lib.slides], screen.apps))
    decide(lib, None, [], "Hi everyone, can you all hear me?", args.threshold)  # warm-up: loads the router
    inbox: queue.Queue = queue.Queue()
    threading.Thread(target=brain, args=(lib, screen, Files(), commands, inbox, args.threshold, args.any_language), daemon=True).start()
    for said, stopped, *kind in INPUTS[args.input](args):
        if said:
            inbox.put((said, stopped, time.perf_counter(), kind[0] if kind else "final"))
    inbox.join()


def run_eval(args) -> None:
    serve_d1()
    lib = Library(args.library)
    lines = [json.loads(l) for l in args.script.read_text().splitlines() if l.strip()]
    ear = Ear() if args.tts else None
    onair, history, rows = None, [], []
    for i, line in enumerate(lines, 1):
        said, stt_ms = line["say"], 0.0
        if ear:
            with tempfile.TemporaryDirectory() as tmp:
                wav = f"{tmp}/say.aiff"
                subprocess.run(["say", "-o", wav, said], check=True)
                t = time.perf_counter()
                said = ear.file(wav)
                stt_ms = (time.perf_counter() - t) * 1e3
        before = onair
        d = decide(lib, onair, history, said, args.threshold)
        if d.show or d.goal:
            onair = d.show
        accept = [x if x == "other" else tuple(x) if x else (None, None) for x in line["screen"]]
        acted = bool(d.goal or d.control)
        ok = ("other" if acted else state(onair)) in accept
        changed, should_change = acted or onair != before, state(before) not in accept
        rows.append({"ok": ok, "false_switch": changed and not should_change, "missed": should_change and not changed,
                     "ms": d.ms, "stt_ms": stt_ms, "tier": d.tier})
        mark = "ok " if ok else "BAD"
        print(f"{mark} {i:2} {d.ms:5.0f}ms {d.tier:7} {str(d.raw.get('intent')):5} {d.raw.get('confidence', ''):<4} "
              f"-> {label(lib, onair)[:48]}  | {said[:70]}")
        if not ok:
            print(f"        want {accept}  reason: {d.raw.get('reason')}")
        history.append(said)
    ms = sorted(r["ms"] for r in rows if r["tier"] == "router")
    print(f"\nscreen correct {sum(r['ok'] for r in rows)}/{len(rows)}  false switches {sum(r['false_switch'] for r in rows)}"
          f"  missed switches {sum(r['missed'] for r in rows)}  router p50 {ms[len(ms) // 2]:.0f}ms p95 {ms[int(len(ms) * .95)]:.0f}ms"
          + (f"  stt mean {np.mean([r['stt_ms'] for r in rows]):.0f}ms" if ear else ""))


def main() -> None:
    global ROUTER, VISION
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--library", type=Path, default=Path("library"))
    p.add_argument("--sandbox", default="base")
    p.add_argument("--host", action="store_true", help="drive this Mac instead of the sandbox VM: the stage is a Chrome window here, and the screen agent clicks and types here")
    p.add_argument("--threshold", type=float, default=0.7)
    p.add_argument("--router", default=ROUTER, help="d1 (llama-server is started if D1_HOST does not answer) or an Ollama tag")
    p.add_argument("--vision", default=VISION)
    sub = p.add_subparsers(dest="cmd", required=True)
    live = sub.add_parser("live")
    live.add_argument("--input", choices=INPUTS, default="mic", help="microphone, silent lip reading from the webcam, or typed lines")
    live.add_argument("--dry", action="store_true", help="print VM commands instead of running them")
    live.add_argument("--camera", type=int, default=0, help="webcam index for --input lips")
    live.add_argument("--preview", action="store_true", help="with --input lips, show the mouth crop and the lip-motion level")
    live.add_argument("--sensitivity", type=float, help="with --input lips, how far lip motion must rise above still-lip jitter")
    live.add_argument("--focus", type=float, default=0.3, help="with the mic, how loud a sound must be next to your recent speech "
                      "to count (0.3 ignores voices about 10 dB quieter than yours; lower it if your own words get cut)")
    live.add_argument("--any-language", action="store_true", help="act on every language, not only English")
    ev = sub.add_parser("eval")
    ev.add_argument("script", type=Path)
    ev.add_argument("--tts", action="store_true", help="speak each line with `say` and transcribe it back")
    ag = sub.add_parser("agent")
    ag.add_argument("goal")
    ag.add_argument("--no-vision", action="store_true")
    args = p.parse_args()
    ROUTER, VISION = args.router, args.vision
    args.sandbox = None if args.host else args.sandbox
    try:
        if args.cmd == "live":
            run_live(args)
        elif args.cmd == "eval":
            run_eval(args)
        else:
            print("done" if agent(Screen(args.sandbox, args.library, False), args.goal, vision=not args.no_vision) else "not done")
    except KeyboardInterrupt:
        print()


if __name__ == "__main__":
    main()

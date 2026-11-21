"""What the presenter wants on the shared screen, read from the sentence being spoken, what came before, and what is on
screen.

One decision model (Liquid d1, served by llama-server) chooses among options built for the moment: keep the screen as it
is, the slides worth considering, the websites and apps the words bring to mind, the controls of the page on screen,
closing it, or something else, which the vision model then works out (copilot.py). It answers with calibrated
probabilities from one forward pass: no generated text is parsed, and the rules live in the option descriptions. The same
Brain runs live and in evals, so an eval measures what ships.
"""
import atexit
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from pypdf import PdfReader

from stage import CONTROLS, ON_STAGE

OLLAMA = os.environ.get("OLLAMA_HOST", "http://127.0.0.1:11434")
EMBED = "embeddinggemma"
D1 = os.environ.get("D1_HOST", "http://127.0.0.1:8090")
ROUTER = "LiquidAI/d1-2B-GGUF:Q8_0"  # any decision model llama-server answers /v1/systemone for

# Bookmarks, so a website said by name opens without the vision model guessing its URL.
SITES = {
    "YouTube": "https://www.youtube.com", "GitHub": "https://github.com", "Google": "https://www.google.com",
    "Gmail": "https://mail.google.com", "Google Maps": "https://www.google.com/maps", "Google Drive": "https://drive.google.com",
    "Google Docs": "https://docs.google.com", "Google Sheets": "https://sheets.google.com", "LinkedIn": "https://www.linkedin.com",
    "X (Twitter)": "https://x.com", "Facebook": "https://www.facebook.com", "Instagram": "https://www.instagram.com",
    "Reddit": "https://www.reddit.com", "Wikipedia": "https://en.wikipedia.org", "Amazon": "https://www.amazon.com",
    "Netflix": "https://www.netflix.com", "Spotify": "https://open.spotify.com", "Notion": "https://www.notion.so",
    "Figma": "https://www.figma.com", "Slack": "https://app.slack.com", "Discord": "https://discord.com/app",
    "ChatGPT": "https://chatgpt.com", "Claude": "https://claude.ai", "Hacker News": "https://news.ycombinator.com",
    "Product Hunt": "https://www.producthunt.com", "Stack Overflow": "https://stackoverflow.com", "Devpost": "https://devpost.com",
    "Hugging Face": "https://huggingface.co", "arXiv": "https://arxiv.org", "TikTok": "https://www.tiktok.com",
    "Twitch": "https://www.twitch.tv", "Medium": "https://medium.com", "Apple": "https://www.apple.com", "Vercel": "https://vercel.com",
    "LauzHack": "https://lauzhack.com", "EPFL": "https://www.epfl.ch", "OpenAI": "https://openai.com", "Anthropic": "https://www.anthropic.com",
}
QUESTION = "The presenter is sharing a screen in a live call. What should the shared screen do as the presenter says the NEW SENTENCE?"
KEEP = ("Keep the screen as it is: the new sentence fits what is on screen, is small talk, or names something without asking "
        "to see it now (a plan for later, something said or meant earlier, a passing mention, a request still unfinished)")
OTHER = ("Something not listed: a search on the web or on a site, a website, file, picture or video that is not listed, or a "
         "link or button to click on the page on screen")
# A transcript reads as another language when more of its words are common words of French, German, Spanish or Italian
# than of English, so side talk in those languages does nothing while short English commands with few common words pass.
ENGLISH = set("""the a an and or but so to of in on at for with is are was were be been it it's this that these those i i'm
you we they he she my our your their me us them not no yes can will would should could do does did have has had let's
lets what how why where when which there here just like okay well now then if as from by about up out go going get want
think see look know mean really very more one two all some""".split())
FOREIGN = set("""le la les un une des du et est mais pas que qui je tu te il elle nous vous ils ce cette ça avec dans sur au aux
très bien oui der das und ist nicht ich du er sie wir ein eine auf für zu den dem aber auch noch ja nein y es pero una
por para muy también lo gli e è che sono""".split())


def ollama(path: str, body: dict) -> dict:
    req = urllib.request.Request(f"{OLLAMA}{path}", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.load(r)


def embed(texts: list[str]) -> np.ndarray:
    v = np.array(ollama("/api/embed", {"model": EMBED, "input": texts, "keep_alive": "1h"})["embeddings"], np.float32)
    return v / np.linalg.norm(v, axis=1, keepdims=True)


def english(text: str) -> bool:
    words = [w.strip(".,;:!?\"()") for w in text.lower().replace("-", " ").split()]
    return sum(w in ENGLISH for w in words) >= sum(w in FOREIGN for w in words)


@dataclass(frozen=True)
class Action:
    kind: str  # slide | url | app | file | control | close | ask
    target: str = ""  # slide id | URL | app name | file path | a CONTROLS name | nothing | the request, for the vision model

    def __str__(self) -> str:
        return f"{self.kind} {self.target}".rstrip()


@dataclass(frozen=True)
class Slide:
    doc: str
    page: int
    title: str
    text: str

    @property
    def id(self) -> str:
        return f"{self.doc} p{self.page}"


class Catalog:
    """Everything the presenter can name: each PDF page in a folder, the bookmarked websites and the apps on the shared
    screen. All are embedded once, so each sentence finds the few worth offering to the decision model."""

    def __init__(self, folder: Path, apps: list[str]):
        self.slides: list[Slide] = []
        for pdf in sorted(folder.glob("*.pdf")):
            for n, page in enumerate(PdfReader(pdf).pages, 1):
                lines = [l.strip() for l in (page.extract_text() or "").splitlines() if l.strip()]
                self.slides.append(Slide(pdf.stem, n, lines[0] if lines else f"page {n}", " ".join(lines)))
        self.by_id = {s.id: s for s in self.slides}
        self.places = {f"{name} website": Action("url", url) for name, url in SITES.items()} | {f"{app} app": Action("app", app) for app in apps}
        vecs = embed([f"title: {s.doc.replace('_', ' ')} | text: {s.text}" for s in self.slides]
                     + [f"title: {name} | text: {name}, {a.target}" for name, a in self.places.items()])
        self.slide_vecs, self.place_vecs = vecs[:len(self.slides)], vecs[len(self.slides):]

    def near(self, query: str, around: list[Slide], slides: int = 6, places: int = 3) -> tuple[list[Slide], list[str]]:
        """The slides and places closest in meaning to the query, with every deck's cover and the slides `around`."""
        q = embed([f"task: search result | query: {query}"])[0]
        keep = {self.slides[i].id for i in np.argsort(-(self.slide_vecs @ q))[:slides]}
        keep |= {s.id for s in self.slides if s.page == 1} | {s.id for s in around}
        names = list(self.places)
        return [s for s in self.slides if s.id in keep], [names[i] for i in np.argsort(-(self.place_vecs @ q))[:places]]


class Brain:
    """What the audience sees and what the presenter has said. decide() turns the words of the sentence being spoken
    into at most one new action; moved() records what the screen shows after it; heard() ends the sentence."""

    def __init__(self, catalog: Catalog, threshold: float = 0.7, any_language: bool = False):
        self.catalog, self.threshold, self.any_language = catalog, threshold, any_language
        self.stage: Action | None = None  # what the stage tab shows, a slide or a picture; None is the desktop picture
        self.front: Action | None = None  # a tab or an app in front of the stage
        self.last: Slide | None = None  # the last slide shown, where the talk is in its deck
        self.history: list[str] = []  # earlier sentences
        self.done: list[Action] = []  # decided during the sentence being spoken

    @property
    def onair(self) -> Action | None:
        return self.front or self.stage

    def describe(self, a: Action | None) -> str:
        if a is None:
            return "nothing, the desktop picture"
        if a.kind == "slide":
            return f'slide [{a.target}] "{self.catalog.by_id[a.target].title}"'
        what = {"url": "the website", "app": "the app", "file": "the file", "control": "the control",
                "close": "closed what was on screen", "ask": "what the presenter asked for:"}[a.kind]
        return f"{what} {a.target}".rstrip()

    def decide(self, said: str, end: bool) -> tuple[Action | None, str]:
        """The action the sentence so far calls for, if any, and why. `end` means the presenter paused or finished:
        requests for the vision model wait for it, since it needs the whole request."""
        if not (self.any_language or english(said)):
            return None, "not English"
        said = said[-300:]  # the decision model's time grows with the prompt; the end of a long sentence is what matters
        cat, onair, last = self.catalog, self.onair, self.last
        around = [s for d in (-1, 0, 1, 2) if last and (s := cat.by_id.get(f"{last.doc} p{last.page + d}"))]
        slides, places = cat.near(" ".join([*self.history[-1:], said]), around)

        options: dict[str, tuple[str, Action | None]] = {"keep": (KEEP, None)}
        options |= {s.id: (s.title, Action("slide", s.id)) for s in slides}
        if last and onair == Action("slide", last.id):
            for label, step in (("next slide", 1), ("previous slide", -1)):
                if s := cat.by_id.get(f"{last.doc} p{last.page + step}"):
                    options[label] = (f"[{s.id}] {s.title}", Action("slide", s.id))
        options |= {name: (cat.places[name].target, cat.places[name]) for name in places}
        if onair and onair.kind not in ("slide", "app"):
            options |= {c: ("on the page or video on screen", Action("control", c)) for c in CONTROLS}
        if onair and onair.kind != "app":
            options["close"] = (f"Close {self.describe(onair)}", Action("close"))
        options["other"] = (OTHER, Action("ask", said))

        lines = ["SLIDES:", *(f"[{s.id}] {s.text[:200]}" for s in slides), "", f"ON SCREEN: {self.describe(onair)}"]
        if last and onair != Action("slide", last.id):
            lines.append(f"LAST SLIDE SHOWN: {self.describe(Action('slide', last.id))}")
        lines += ["", "EARLIER SPEECH:", *([h[-160:] for h in self.history[-2:]] or ["(start of talk)"]), "", f"NEW SENTENCE: {said}"]
        if self.done:
            lines.append("ALREADY DONE DURING THIS SENTENCE: " + "; ".join(map(self.describe, self.done)))
        answer = ask_d1({"state": "\n".join(lines), "questions": {"screen": {
            "type": "choice", "instructions": QUESTION, "criteria": {label: d for label, (d, _) in options.items()}}}})["screen"]

        # Options that lead to the same action share its probability; showing what is on screen already is keeping it.
        keep, p = 0.0, defaultdict(float)
        for label, prob in answer["probabilities"].items():
            a = options[label][1]
            if a is None or a == onair:
                keep += prob
            else:
                p[a] += prob
        best = max(p, key=lambda a: p[a])
        why = f"P(keep) {keep:.2f}, {best} {p[best]:.2f}"
        if 1 - keep < self.threshold or best in self.done or best.kind == "ask" and (not end or any(a.kind == "ask" for a in self.done)):
            return None, why
        self.done.append(best)
        return best, why

    def moved(self, a: Action) -> None:
        """Record what the screen shows after doing `a`, as the screen reports it."""
        if a.kind == "slide" or a.kind == "file" and Path(a.target).suffix.lower() in ON_STAGE:
            self.stage, self.front = a, None
            self.last = self.catalog.by_id[a.target] if a.kind == "slide" else self.last
        elif a.kind == "close" and self.front:
            self.front = None
        elif a.kind == "close":
            self.stage = None
        elif a.kind != "control":
            self.front = a

    def heard(self, said: str) -> None:
        """The sentence is over."""
        if self.any_language or english(said):
            self.history.append(said)
        self.done.clear()


def ask_d1(body: dict) -> dict:
    req = urllib.request.Request(f"{D1}/v1/systemone", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)["answers"]


def healthy() -> bool:
    try:
        with urllib.request.urlopen(f"{D1}/health", timeout=2) as r:
            return r.status == 200
    except OSError:
        return False


def serve(model: str = ROUTER) -> None:
    """Start llama-server with the decision model unless one already answers at D1_HOST. It is stopped when we exit."""
    if healthy():
        return
    url = urllib.parse.urlsplit(D1)
    # /v1/systemone reads the whole prompt in one batch, so the batch must hold the full context (the default 512 fails).
    cmd = ["llama-server", "-hf", model, "--host", url.hostname, "--port", str(url.port), "-c", "8192", "-b", "8192", "-ub", "8192", "-np", "1"]
    usage = subprocess.run(["llama-server", "--help"], capture_output=True, text=True)
    if "--offline" in usage.stdout + usage.stderr and not os.environ.get("D1_ONLINE"):
        cmd.append("--offline")
    log = tempfile.NamedTemporaryFile("w", prefix="d1-server-", suffix=".log", delete=False)
    print(f"starting the decision model {model}, log in {log.name}")
    server = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
    atexit.register(server.terminate)
    deadline = time.monotonic() + 600
    while not healthy():
        if server.poll() is not None or time.monotonic() > deadline:
            sys.exit(f"the decision model did not come up, see {log.name}")
        time.sleep(0.5)


APPS = ["Notes", "Calculator", "Safari", "System Settings", "Maps", "Photos", "Preview", "Finder", "Keynote", "Mail",
        "Terminal", "Google Chrome"]  # stand-in for the VM's /Applications in evals


def evaluate(scripts: list[Path], library: Path, threshold: float, step: int = 2, hear=lambda said: said) -> None:
    """Replay scripted talks through a Brain, the sentence so far every `step` words as the microphone streams it, and
    score the screen after each line against its "want": any of null (nothing shown), a slide id (that slide shown),
    "keep" (nothing done), or "kind" / "kind target" (an action of that kind, or exactly that action, done)."""
    catalog = Catalog(library, APPS)
    totals, ms = defaultdict(int), []
    for script in scripts:
        brain, counts = Brain(catalog, threshold), defaultdict(int)
        print(f"\n{script}")
        for i, line in enumerate((json.loads(l) for l in script.read_text().splitlines() if l.strip()), 1):
            said, want = hear(line["say"]), line["want"]
            before, taken, why, words = brain.onair, [], "", said.split()
            for n in [*(range(step, len(words), step) if step else []), len(words)]:
                t = time.perf_counter()
                a, why = brain.decide(" ".join(words[:n]), end=n == len(words))
                ms.append((time.perf_counter() - t) * 1e3)
                if a:
                    brain.moved(a)
                    taken.append(a)
            brain.heard(said)
            shows = lambda onair: any(onair is None if w is None else w in catalog.by_id and onair == Action("slide", w) for w in want)
            did = any(w and w not in catalog.by_id and w != "keep" and w in (a.kind, str(a)) for w in want for a in taken)
            should = not shows(before) and "keep" not in want
            if taken and not should:
                verdict = "false switch"
            elif shows(brain.onair) or did or not taken and not should:
                verdict = "ok"
            else:
                verdict = "wrong" if taken else "missed"
            counts[verdict] += 1
            print(f"{'ok ' if verdict == 'ok' else 'BAD'} {i:2} {'; '.join(map(str, taken)) or '-':48.48} | {said[:72]}")
            if verdict != "ok":
                print(f"       {verdict}: want {want}, on screen {brain.describe(brain.onair)}; last decision {why}")
        print(summary(script.stem, counts))
        for k, v in counts.items():
            totals[k] += v
    ms.sort()
    print(f"\n{summary('total', totals)}  decision p50 {ms[len(ms) // 2]:.0f}ms p95 {ms[int(len(ms) * .95)]:.0f}ms ({len(ms)} decisions)")


def summary(name: str, c: dict) -> str:
    return (f"{name:16} screen correct {c['ok']}/{sum(c.values())}  false switches {c['false switch']}  "
            f"missed {c['missed']}  wrong {c['wrong']}")

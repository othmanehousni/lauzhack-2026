"""What the presenter wants on the shared screen, read from the sentence being spoken, what came before, and what is on
screen.

An intent classifier, trained at start-up on the examples in intents.py, reads the sentence's embedding and words and
says what kind of request it is (a remark, talk about the slides, a slide, next, back to the slides, a website to open, a
control of the page, ...). The screen as the sentence began rules out kinds that cannot apply. The target is then looked
up, not guessed: a slide by number or meaning, a website or app by name, a spoken address. Talk about the slides moves
through the deck with it. Only when the classifier is unsure does a decision model (Liquid d1) pick among its likeliest
readings; requests with no target here go to the vision model (copilot.py). The same Brain runs live and in evals.
"""
import atexit
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zlib
from collections import defaultdict
from dataclasses import dataclass, replace
from difflib import SequenceMatcher
from pathlib import Path

import numpy as np
from pypdf import PdfReader

from stage import CACHE, CONTROLS, ON_STAGE

OLLAMA = os.environ.get("OLLAMA_HOST", "http://127.0.0.1:11434")
EMBED = "embeddinggemma-2:270m"
CLASSIFY, QUERY = "task: classification | query: {}", "task: search result | query: {}"
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
# The classifier's labels. A label's group, its first word, is the kind of request.
LABELS = ["aside", "talk", "unfinished", "pick", "slide", "next", "previous", "return", "open", "search", "click", "close",
          *(f"control {c}" for c in CONTROLS)]
QUIET = {"aside", "unfinished", "pick"}  # never change the screen; pick has nothing to choose from until the brain offers options
NGRAMS, BUCKETS = 0.5, 2 ** 12  # the hashed words' weight next to the embedding, and how many buckets they hash into
STEPS, LR, L2 = 200, 0.05, 1e-4  # training: full-batch Adam steps, step size, weight penalty
PARTIAL = 0.9  # how likely a kind of request must be to act before the sentence ends
FALLBACK_LOW = 0.25  # an unsure sentence at least this likely to be a request goes to the decision model
JUMP, MARGIN = 0.02, 0.05  # following the talk: each slide skipped costs JUMP, and the new slide must beat the one on air by MARGIN
RETURN_BONUS = 0.1  # "back to the slides" lands on the last one shown unless the sentence is clearly about another
FUZZY = 0.85  # how close a run of words must be to a website or app name
WEB = {"website", "site", "page", "dot"}  # these make a name both a website and an app mean the website
TLDS = set("com org net edu gov io ai co ch fr de uk eu us app dev me tv so gg xyz info".split())  # so report.pdf is no address
NUMBERS = {w: n for n, w in enumerate("""one two three four five six seven eight nine ten eleven twelve thirteen fourteen
fifteen sixteen seventeen eighteen nineteen twenty""".split(), 1)}
NUMBERS |= {f"twenty {w}": 20 + n for w, n in list(NUMBERS.items())[:9]} | {"thirty": 30}
QUESTION = ("The presenter is sharing a screen in a live call. What should the shared screen show right after the NEW SENTENCE? "
            "Change it only for what the presenter wants the audience to see now, not for what they will show later, showed "
            "before, or only mention.")
KEEP = ("Keep the screen as it is: the new sentence fits what is on screen, is small talk, or names something without asking "
        "to see it now (a plan for later, something said or meant earlier, a passing mention)")
HINTS = {"talk": "the talk has moved on to it", "slide": "show this slide now", "next": "the next slide",
         "previous": "the previous slide", "return": "back to the slides", "open": "open it now", "search": "search for it now",
         "click": "click it on the page on screen", "control": "do it now on the page on screen", "close": "stop showing it"}
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


def tokens(text: str) -> list[str]:
    """Lowercase alphanumeric words."""
    return "".join(c if c.isalnum() else " " for c in text.lower()).split()


def grams(text: str) -> np.ndarray:
    """The sentence's words and word pairs, hashed, so the classifier also reads the exact words the embedding blurs."""
    w = tokens(text)
    v = np.zeros(BUCKETS, np.float32)
    v[[zlib.crc32(g.encode()) % BUCKETS for g in w + [f"{a} {b}" for a, b in zip(w, w[1:])]]] = 1
    n = np.linalg.norm(v)
    return v * (NGRAMS / n) if n else v


def softmax(z: np.ndarray) -> np.ndarray:
    e = np.exp(z - z.max(-1, keepdims=True))
    return e / e.sum(-1, keepdims=True)


def domain(said: str) -> str | None:
    """A web address in the sentence, spoken ("lauzhack dot com") or written ("lauzhack.com"), as a URL."""
    for x in said.lower().replace("w w w ", "www ").replace(" dot ", ".").split():
        host = x.strip(".,;:!?\"'()").split("//")[-1].split("/")[0]
        if (parts := host.rpartition("."))[0] and parts[2] in TLDS:
            return "https://" + host
    return None


def slide_number(said: str) -> int | None:
    """The slide number the sentence names ("slide 3", "page twenty one"), 1 for "first slide", -1 for "last slide"."""
    w = [x for x in tokens(said) if x != "number"]
    for i, x in enumerate(w[:-1]):
        nxt = w[i + 1]
        if x in ("first", "last") and nxt in ("slide", "page"):
            return 1 if x == "first" else -1
        if x in ("slide", "page"):
            n = NUMBERS.get(" ".join(w[i + 1:i + 3])) or NUMBERS.get(nxt) or (int(nxt) if nxt.isdigit() else None)
            if n:
                return n
    return None


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


class Intents:
    """What kind of request a sentence is: softmax regression over its embedding and its hashed words, trained on the
    example sentences of each label. The examples' embeddings are cached, so a restart trains in a fraction of a second."""

    def __init__(self, examples: dict[str, list[str]]):
        if set(examples) != set(LABELS):
            raise ValueError(f"intent labels differ from brain.LABELS: {sorted(set(examples) ^ set(LABELS))}")
        self.labels = list(examples)
        texts = [t for label in self.labels for t in examples[label]]
        prompts = [CLASSIFY.format(t) for t in texts]
        cache = CACHE / f"intents-{hashlib.sha256('\n'.join([EMBED, *prompts]).encode()).hexdigest()[:16]}.npy"
        if not cache.exists():
            CACHE.mkdir(exist_ok=True)
            np.save(cache, embed(prompts))
        x = np.hstack([np.load(cache), np.stack([grams(t) for t in texts])])
        y = np.eye(len(self.labels), dtype=np.float32)[[i for i, label in enumerate(self.labels) for _ in examples[label]]]
        self.w, self.b = np.zeros((x.shape[1], len(self.labels)), np.float32), np.zeros(len(self.labels), np.float32)
        moments = [(np.zeros_like(p), np.zeros_like(p)) for p in (self.w, self.b)]
        for t in range(1, STEPS + 1):
            g = (softmax(x @ self.w + self.b) - y) / len(x)
            for p, grad, (m, s) in zip((self.w, self.b), (x.T @ g + L2 * self.w, g.sum(0)), moments):
                m += 0.1 * (grad - m)
                s += 0.001 * (grad * grad - s)
                p -= LR * m / (1 - 0.9 ** t) / (np.sqrt(s / (1 - 0.999 ** t)) + 1e-8)

    def __call__(self, vec: np.ndarray, text: str) -> dict[str, float]:
        p = softmax(np.concatenate([vec, grams(text)]) @ self.w + self.b)
        return dict(zip(self.labels, p.tolist()))


class Catalog:
    """Everything the presenter can name: each PDF page in a folder, embedded once, and the bookmarked websites and the
    apps on the shared screen, by name."""

    def __init__(self, folder: Path, apps: list[str]):
        self.slides: list[Slide] = []
        for pdf in sorted(folder.glob("*.pdf")):
            for n, page in enumerate(PdfReader(pdf).pages, 1):
                lines = [l.strip() for l in (page.extract_text() or "").splitlines() if l.strip()]
                self.slides.append(Slide(pdf.stem, n, lines[0] if lines else f"page {n}", " ".join(lines)))
        self.by_id = {s.id: s for s in self.slides}
        self.slide_vecs = embed([f"title: {s.doc.replace('_', ' ')} | text: {s.text}" for s in self.slides])
        # "X (Twitter)" is named X and Twitter; names under three letters match too much
        self.sites = {n: Action("url", url) for name, url in SITES.items() for n in name.removesuffix(")").split(" (") if len(n) >= 3}
        self.places = self.sites | {app: Action("app", app) for app in apps if len(app) >= 3}  # an app takes a shared name

    def place(self, said: str) -> Action | None:
        """The website or app the sentence names. Runs of up to three words are compared without spaces, so "git hub"
        finds GitHub; the closest name wins, and the longest of equally close ones ("Google Docs" over "Google")."""
        w = tokens(said)
        runs = {(n, "".join(w[i:i + n])) for n in (1, 2, 3) for i in range(len(w) - n + 1)}
        close: dict[str, float] = {}
        for name in self.places:
            parts = tokens(name)
            m = SequenceMatcher(None, "", squashed := "".join(parts))
            for n, run in runs:
                m.set_seq1(run)
                # a run of more or fewer words than the name must spell it exactly, so "open a" is not OpenAI
                fuzzy = n == len(parts) and m.real_quick_ratio() >= FUZZY and m.quick_ratio() >= FUZZY
                if (r := m.ratio() if fuzzy else float(run == squashed)) >= FUZZY:
                    close[name] = max(r, close.get(name, 0.0))
        if not close:
            return None
        name = max(close, key=lambda n: (close[n], len(n)))
        return self.sites[name] if name in self.sites and WEB & set(w) else self.places[name]


@dataclass(frozen=True)
class View:
    """What the audience sees."""
    stage: Action | None = None  # what the stage tab shows, a slide or a picture; None is the desktop picture
    front: Action | None = None  # a tab or an app in front of the stage
    last: Action | None = None  # the last slide shown, where the talk is in its deck

    @property
    def onair(self) -> Action | None:
        return self.front or self.stage

    def after(self, a: Action) -> "View":
        if a.kind == "slide":
            return View(a, None, a)
        if a.kind == "file" and Path(a.target).suffix.lower() in ON_STAGE:
            return View(a, None, self.last)
        if a.kind == "close":
            return replace(self, front=None) if self.front else replace(self, stage=None)
        return self if a.kind == "control" else replace(self, front=a)


class Brain:
    """What the audience sees and what the presenter has said. decide() turns the words of the sentence being spoken
    into at most one new action; moved() records what the screen shows after it; heard() ends the sentence.

    Every decision during a sentence is made from the screen as it was when the sentence began, so a later transcript
    refines an earlier decision instead of repeating it ("next slide" then "next slide please" is one step). A partial
    transcript acts only when it is very likely and the previous one chose the same."""

    def __init__(self, catalog: Catalog, intents: Intents, threshold: float = 0.5, any_language: bool = False, fallback: bool = True):
        self.catalog, self.intents, self.threshold, self.any_language, self.fallback = catalog, intents, threshold, any_language, fallback
        self.view = self.start = View()  # the screen now, and when the sentence began
        self.guess: Action | None = None  # the previous partial transcript's choice
        self.done: list[Action] = []  # acted on during this sentence
        self.history: list[str] = []  # earlier sentences

    @property
    def onair(self) -> Action | None:
        return self.view.onair

    def describe(self, a: Action | None) -> str:
        if a is None:
            return "nothing, the desktop picture"
        if a.kind == "slide":
            return f'slide [{a.target}] "{self.catalog.by_id[a.target].title}"'
        kinds = {"url": "the website", "app": "the app", "file": "the file", "control": "on the page on screen:",
                 "close": "close what is on screen", "ask": "what the presenter asked for:"}
        return f"{kinds[a.kind]} {a.target}".rstrip()

    def decide(self, said: str, end: bool) -> tuple[Action | None, str]:
        """The action the sentence so far calls for, if any, and why. `end` means the presenter paused or finished;
        requests for the vision model wait for it, since it needs the whole request."""
        if not (self.any_language or english(said)):
            return None, "not English"
        said = said[-300:]  # the end of a long sentence is what matters
        cls, query = embed([CLASSIFY.format(said), QUERY.format(said)])
        probs, v = self.intents(cls, said), self.start
        on, shown = v.onair, v.last is not None
        possible = {"next": shown, "previous": shown, "return": shown and on != v.last,
                    "control": on is not None and on.kind in ("url", "file", "ask"), "close": on is not None and on.kind != "app"}
        groups: dict[str, float] = defaultdict(float)
        for label, prob in probs.items():
            if possible.get(g := label.split()[0], True):
                groups[g] += prob
        total = sum(groups.values())
        groups = {g: p / total for g, p in groups.items()}
        best = max(groups, key=groups.__getitem__)
        p, gate = groups[best], self.threshold if end else max(self.threshold, PARTIAL)
        control = max((l for l in probs if l.startswith("control ")), key=probs.__getitem__).removeprefix("control ")
        sims = self.catalog.slide_vecs @ query
        waits = best == "talk" or best == "slide" and tokens(said)[-1:] in (["slide"], ["page"], ["number"])  # a number may follow
        choice, how = None, "unsure"
        if p >= gate:
            choice, how = self.resolve(best, said, sims, control) if end or not waits else (None, "waits for the end")
        elif end and self.fallback and p >= FALLBACK_LOW and best not in QUIET | {"talk"}:
            choice, how = self.ask(groups, said, sims, control)
        why = ", ".join(f"{label} {probs[label]:.2f}" for label in sorted(probs, key=probs.__getitem__, reverse=True)[:3])
        why += f"; {best} {p:.2f}: {how}"
        if not end:
            choice, self.guess = choice if choice == self.guess else None, choice
        if not choice or choice in self.done or choice == self.view.onair or choice.kind == "ask" and (not end or any(a.kind == "ask" for a in self.done)):
            return None, why
        self.done.append(choice)
        return choice, why

    def resolve(self, group: str, said: str, sims: np.ndarray, control: str) -> tuple[Action | None, str]:
        """The action a kind of request calls for, from the screen as the sentence began, and how it was found."""
        cat, v = self.catalog, self.start
        here = cat.by_id[v.last.target] if v.last else cat.slides[0]
        match group:
            case "talk":
                return self.follow(sims)
            case "slide":
                n = slide_number(said)
                if n is None:
                    return Action("slide", cat.slides[int(np.argmax(sims))].id), "closest slide"
                deck = [s for s in cat.slides if s.doc == here.doc]
                s = deck[n - 1] if 0 < n <= len(deck) else deck[-1] if n == -1 else None
                return (Action("slide", s.id) if s else None), f"slide number {n}"
            case "next" | "previous":
                if group == "previous" and v.onair != v.last:
                    return v.last, "back to the slides"
                s = cat.by_id.get(f"{here.doc} p{here.page + (1 if group == 'next' else -1)}")
                return (Action("slide", s.id) if s else None), group
            case "return":
                bonus = np.array([RETURN_BONUS if s == here else 0.0 for s in cat.slides])
                return Action("slide", cat.slides[int(np.argmax(sims + bonus))].id), "back to the slides"
            case "open" if (url := domain(said)):
                return Action("url", url), "address"
            case "open" if (a := cat.place(said)):
                return a, f"place {a.target}"
            case "open" | "search" | "click":
                return Action("ask", said), "for the vision model"
            case "control":
                return Action("control", control), "control"
            case "close":
                return Action("close"), "close"
        return None, group

    def follow(self, sims: np.ndarray) -> tuple[Action | None, str]:
        """While the slides are on air, the slide up to four pages ahead that the talk has moved on to, if any."""
        v, slides = self.start, self.catalog.slides
        if not v.last or v.onair != v.last:
            return None, "talk, slides not on air"
        i = slides.index(self.catalog.by_id[v.last.target])
        ahead = [(sims[i + k] - JUMP * (k - 1), k) for k in range(1, 5) if i + k < len(slides) and slides[i + k].doc == slides[i].doc]
        score, k = max(ahead, default=(-1.0, 0))
        if score > sims[i] + MARGIN:
            return Action("slide", slides[i + k].id), f"follow +{k}"
        return None, "talk, stay"

    def ask(self, groups: dict[str, float], said: str, sims: np.ndarray, control: str) -> tuple[Action | None, str]:
        """The decision model's pick among the classifier's three likeliest readings of the sentence, or keeping the screen."""
        v = self.start
        options: dict[str, tuple[str, Action | None]] = {"keep": (KEEP, None)}
        for g in sorted((g for g in groups if g not in QUIET), key=groups.__getitem__, reverse=True)[:3]:
            a, _ = self.resolve(g, said, sims, control)
            if a and a != v.onair and str(a) not in options:
                options[str(a)] = (f"{self.describe(a)}: {HINTS[g]}", a)
        if len(options) == 1:
            return None, "fallback, nothing to offer"
        lines = [f"ON SCREEN: {self.describe(v.onair)}"]
        if v.last and v.onair != v.last:
            lines.append(f"LAST SLIDE SHOWN: {self.describe(v.last)}")
        lines += ["EARLIER SPEECH:", *([h[-160:] for h in self.history[-2:]] or ["(start of talk)"]), f"NEW SENTENCE: {said}"]
        r = ask_d1({"state": "\n".join(lines), "questions": {"screen": {
            "type": "choice", "instructions": QUESTION, "criteria": {label: d for label, (d, _) in options.items()}}}})
        probs = r["answers"]["screen"]["probabilities"]
        label = max((l for l in probs if l != "keep"), key=probs.__getitem__)
        return (options[label][1] if probs[label] >= 0.5 else None), f"fallback d1 {label} {probs[label]:.2f}"

    def moved(self, a: Action) -> None:
        """Record what the screen shows after doing `a`, as the screen reports it."""
        self.view = self.view.after(a)

    def heard(self, said: str) -> None:
        """The sentence is over."""
        if self.any_language or english(said):
            self.history.append(said)
        self.start, self.guess, self.done = self.view, None, []


def ask_d1(body: dict) -> dict:
    req = urllib.request.Request(f"{D1}/v1/systemone", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)


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


def evaluate(scripts: list[Path], library: Path, intents: Intents, threshold: float, step: int = 2, hear=lambda said: said,
             fallback: bool = True) -> None:
    """Replay scripted talks through a Brain, the sentence so far every `step` words as the microphone streams it, and
    score the screen after each line against its "want": any of null (nothing shown), a slide id (that slide shown),
    "keep" (nothing done), or "kind" / "kind target" (an action of that kind, or exactly that action, done)."""
    catalog = Catalog(library, APPS)
    totals, ms = defaultdict(int), []
    for script in scripts:
        brain, counts = Brain(catalog, intents, threshold, fallback=fallback), defaultdict(int)
        print(f"\n{script}")
        for i, line in enumerate((json.loads(l) for l in script.read_text().splitlines() if l.strip()), 1):
            said, want = hear(line["say"]), line["want"]
            before, taken, trace, words, why = brain.onair, [], [], said.split(), ""
            for n in [*(range(step, len(words), step) if step else []), len(words)]:
                t = time.perf_counter()
                a, why = brain.decide(" ".join(words[:n]), end=n == len(words))
                ms.append((time.perf_counter() - t) * 1e3)
                if a:
                    brain.moved(a)
                    taken.append(a)
                    trace.append(f'"{" ".join(words[:n])}" -> {a} ({why})')
            trace.append(f"end: {why}")
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
                print(f"       {verdict}: want {want}, on screen {brain.describe(brain.onair)}" + "".join(f"\n         {t}" for t in trace))
        print(summary(script.stem, counts))
        for k, v in counts.items():
            totals[k] += v
    ms.sort()
    print(f"\n{summary('total', totals)}  decision p50 {ms[len(ms) // 2]:.0f}ms p95 {ms[int(len(ms) * .95)]:.0f}ms ({len(ms)} decisions)")


def summary(name: str, c: dict) -> str:
    return (f"{name:16} screen correct {c['ok']}/{sum(c.values())}  false switches {c['false switch']}  "
            f"missed {c['missed']}  wrong {c['wrong']}")

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
import urllib.error
import urllib.parse
import urllib.request
import zlib
from collections import Counter, defaultdict
from collections.abc import Sequence
from dataclasses import dataclass, replace
from difflib import SequenceMatcher
from pathlib import Path

import numpy as np
from pypdf import PdfReader

from intents import INTENTS
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
LABELS = ["aside", "talk", "unfinished", "later", "pick", "slide", "next", "previous", "return", "open", "search", "click", "close",
          *(f"control {c}" for c in CONTROLS)]
QUIET = {"aside", "unfinished", "later"}  # never change the screen; "later" also cancels a request before it
SHOWN = {"slide", "url", "app", "file"}  # kinds of action worth suggesting: they put something in front of the audience
NGRAMS, BUCKETS = 0.5, 2 ** 12  # the hashed words' weight next to the embedding, and how many buckets they hash into
STEPS, LR, L2 = 400, 0.1, 1e-5  # training: full-batch Adam steps, step size, weight penalty (5-fold CV on intents.py)
PARTIAL = 0.9  # how likely a kind of request must be to act before the sentence ends
ASK = 0.95  # the vision model and the screen agent take seconds and act on their own reading, so only this sure a request
FALLBACK_LOW = 0.25  # an unsure sentence at least this likely to be a request goes to the decision model
JUMP, MARGIN = 0.02, 0.1  # following the talk, each slide skipped costs JUMP and the new slide must beat the one on air by
# MARGIN; going back to the slides, a slide the sentence names must beat the last one shown by MARGIN
NAMES = 0.04  # how much saying a slide's title or its deck's name counts, next to the meaning of the sentence
HERE_BONUS = 0.03  # a slide named by its topic is looked for in the deck on screen first
SUGGEST = 0.02  # following the talk: a slide ahead that beats the one on air by this much, but not by MARGIN, is suggested
# How much a request must fit a slide or a file better than a request naming nothing does. The best slide of 50, or file
# of 1500, reaches 0.08 by chance for a request that names none; one asked for fits by 0.09 to 0.3. CLOSE: how close to
# the best file another must be to be offered with it.
NAMED_MIN, CLOSE = 0.09, 0.02
LEAN = 0.05  # how much the classifier's slide-or-file reading weighs against how well a slide or a file fits the words
FUZZY = 0.85  # how close a run of words must be to a website or app name
WEB = {"website", "site", "page", "dot"}  # these make a name both a website and an app mean the website
NAMED = WEB | {"app", "application"}  # next to these, a name that is also a plain word (Notes, Photos) is a name
GOVERNS = {"to", "open", "launch", "start", "into"}  # right after these, with no article, a plain word is a name: "switch to notes"
TLDS = set("com org net edu gov io ai co ch fr de uk eu us app dev me tv so gg xyz info".split())  # so report.pdf is no address
NUMBERS = {w: n for n, w in enumerate("""one two three four five six seven eight nine ten eleven twelve thirteen fourteen
fifteen sixteen seventeen eighteen nineteen twenty""".split(), 1)}
NUMBERS |= {f"twenty {w}": 20 + n for w, n in list(NUMBERS.items())[:9]} | {"thirty": 30}
ORDINALS = {"first": 0, "top": 0, "former": 0, "second": 1, "third": 2, "fourth": 3, "last": -1, "bottom": -1, "latter": -1}
# What a file is, in the words a presenter uses for it ("the spreadsheet", "the photo of the house").
KINDS = {".pdf": "PDF document", ".xlsx": "spreadsheet", ".xls": "spreadsheet", ".csv": "spreadsheet", ".numbers": "spreadsheet",
         ".pptx": "presentation slides", ".key": "presentation slides", ".docx": "document", ".doc": "document",
         ".pages": "document", ".txt": "notes", ".md": "notes", ".jpg": "photo", ".jpeg": "photo", ".heic": "photo",
         ".png": "picture", ".gif": "picture", ".mp4": "video", ".mov": "video", ".m4v": "video", ".webm": "video"}
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
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return json.load(r)
    except urllib.error.URLError as e:
        raise RuntimeError(f"Ollama stopped answering at {OLLAMA} ({e.reason}): open the Ollama app or run `ollama serve`") from e


def embed(texts: list[str]) -> np.ndarray:
    v = np.array(ollama("/api/embed", {"model": EMBED, "input": texts, "keep_alive": "1h"})["embeddings"], np.float32)
    return v / np.linalg.norm(v, axis=1, keepdims=True)


def remember(texts: list[str]) -> np.ndarray:
    """embed(), keeping each text's vector on disk, so a restart does not ask the model again for the training sentences,
    slides and files it has seen."""
    path = CACHE / f"vectors-{EMBED.replace(':', '-')}.npz"
    known = dict(np.load(path)) if path.exists() else {}
    keys = [hashlib.sha256(t.encode()).hexdigest()[:24] for t in texts]
    new, text = list(dict.fromkeys(k for k in keys if k not in known)), dict(zip(keys, texts))
    for i in range(0, len(new), 128):
        known.update(zip(new[i:i + 128], embed([text[k] for k in new[i:i + 128]])))
    if new:
        CACHE.mkdir(exist_ok=True)
        np.savez(path, **known)
    return np.stack([known[k] for k in keys])


def file_doc(path: str) -> str:
    """A file as a document to search: its name, what kind of file it is, and the folders it is in."""
    p = Path(path)
    folders = p.parts[3:-1] if p.parts[1:2] == ("Users",) else p.parts[1:-1]
    return f"title: {p.stem} | text: {KINDS.get(p.suffix.lower(), 'file')} in {' / '.join(folders)}"


def english(text: str) -> bool:
    words = [w.strip(".,;:!?\"()") for w in text.lower().replace("-", " ").split()]
    return sum(w in ENGLISH for w in words) >= sum(w in FOREIGN for w in words)


def tokens(text: str) -> list[str]:
    """Lowercase alphanumeric words."""
    return "".join(c if c.isalnum() else " " for c in text.lower()).split()


def sound(name: str) -> str:
    """How a word sounds, roughly: letters that sound alike made one, every run of vowels one, h and w dropped, so a
    name the transcriber does not know and spells by ear ("lozac", "loshack") sounds like the name meant ("LauzHack")."""
    s = name.lower()
    for a, b in (("ck", "k"), ("ph", "f"), ("x", "ks"), ("c", "k"), ("q", "k"), ("z", "s"), ("h", ""), ("w", "")):
        s = s.replace(a, b)
    out = ""
    for c in s:
        c = "a" if c in "aeiouy" else c
        if not out.endswith(c):
            out += c
    return out


def stems(text: str) -> set[str]:
    """The words cut to five letters, so "financials" matches "finance" and "margins" "margin"."""
    return {w[:5] for w in tokens(text) if len(w) > 2}


def grams(text: str) -> np.ndarray:
    """The sentence's words and word pairs, and its first and last words as such, hashed, so the classifier also reads
    the exact words the embedding blurs and where they stand ("can you pull up, um" ends on a filler)."""
    w = tokens(text)
    v = np.zeros(BUCKETS, np.float32)
    ends = [f"^{w[0]}", f"{w[-1]}$", f"{' '.join(w[-2:])}$"] if w else []
    v[[zlib.crc32(g.encode()) % BUCKETS for g in w + [f"{a} {b}" for a, b in zip(w, w[1:])] + ends]] = 1
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


def clauses(said: str) -> list[str]:
    """The sentence split where the transcriber wrote a stop, a question mark or a comma; a clause of one or two words
    ("Okay,", "please.") stays with the next one, or with the one before at the end."""
    parts: list[list[str]] = [[]]
    for w in said.split():
        parts[-1].append(w)
        if w[-1] in ".?!,;:" and len(parts[-1]) > 2:
            parts.append([])
    if len(parts) > 1 and len(parts[-1]) <= 2:
        last = parts.pop()
        parts[-1] += last
    return [" ".join(p) for p in parts if p]


def readings(text: str) -> list[str]:
    """The texts a sentence is read by: itself, then each of its clauses if it has more than one."""
    parts = clauses(text)
    return [text, *parts] if len(parts) > 1 else [text]


def prompts(text: str) -> list[str]:
    """What Brain.read embeds for a sentence: each reading to classify, and the whole sentence to look things up with,
    since what a request names may sit in another clause ("it's called YouTube, maybe you can go on it")."""
    return [CLASSIFY.format(t) for t in readings(text)] + [QUERY.format(text)]


def slide_number(said: str) -> int | None:
    """The slide number the sentence names ("slide 3", "page twenty one"), 1 for "first slide", -1 for "last slide"."""
    w = [x for x in tokens(said) if x != "number"]
    for i, x in enumerate(w[:-1]):
        nxt = w[i + 1]
        if x in ("first", "last") and nxt in ("slide", "page"):
            return 1 if x == "first" else -1
        if x[:-2].isdigit() and x[-2:] in ("st", "nd", "rd", "th") and nxt in ("slide", "page"):  # "7th slide"
            return int(x[:-2])
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
    example sentences of each label. The examples' vectors are kept on disk, so a restart trains in a fraction of a second."""

    def __init__(self, examples: dict[str, list[str]]):
        if set(examples) != set(LABELS):
            raise ValueError(f"intent labels differ from brain.LABELS: {sorted(set(examples) ^ set(LABELS))}")
        self.labels = list(examples)
        texts = [t for label in self.labels for t in examples[label]]
        x = np.hstack([remember([CLASSIFY.format(t) for t in texts]), np.stack([grams(t) for t in texts])])
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
    """Everything the presenter can name: each PDF page in a folder and the files they may show, embedded once, and the
    bookmarked websites and the apps on the shared screen, by name."""

    def __init__(self, folder: Path, apps: list[str], files: Sequence[str] = ()):
        self.slides: list[Slide] = []
        for pdf in sorted(folder.glob("*.pdf")):
            for n, page in enumerate(PdfReader(pdf).pages, 1):
                lines = [l.strip() for l in (page.extract_text() or "").splitlines() if l.strip()]
                self.slides.append(Slide(pdf.stem, n, lines[0] if lines else f"page {n}", " ".join(lines)))
        self.by_id = {s.id: s for s in self.slides}
        # Each document says what it is: a slide by its own title, so its content and not its deck's name decides how close
        # it is to a request, and a deck as a whole by its name, standing for its first slide ("the company slides").
        self.slide_vecs = remember([f"title: {s.title} | text: {s.text}" for s in self.slides])
        self.covers = [i for i, s in enumerate(self.slides) if s.page == 1]
        # Each slide is also named by its title and its deck's name, word by word, rare words counting most
        names = [stems(f"{s.title} {s.doc.replace('_', ' ')}") for s in self.slides]
        idf = {t: np.log(len(names) / n) for t, n in Counter(t for name in names for t in name).items()}
        self.names = [{t: idf[t] for t in name} for name in names]
        self.deck_vecs = remember([f"title: {self.slides[i].doc.replace('_', ' ')} | text: "
                                   + "; ".join(s.title for s in self.slides if s.doc == self.slides[i].doc) for i in self.covers])
        self.files = list(files)
        self.file_vecs = remember([file_doc(f) for f in self.files]) if self.files else self.slide_vecs[:0]
        # A document that says little ("Screenshot.png") is close to every request, so what counts is how much closer a
        # request is to it than a request naming nothing is: the unfinished examples, "can you pull up the, um"
        empty = remember([QUERY.format(t) for t in INTENTS["unfinished"]]).mean(0)
        self.slide_empty, self.file_empty = self.slide_vecs @ empty, self.file_vecs @ empty
        # "X (Twitter)" is named X and Twitter; names under three letters match too much
        self.sites = {n: Action("url", url) for name, url in SITES.items() for n in name.removesuffix(")").split(" (") if len(n) >= 3}
        self.places = self.sites | {app: Action("app", app) for app in apps if len(app) >= 3}  # an app takes a shared name

    @property
    def vocabulary(self) -> list[str]:
        """The names a presenter may say, for the speech recognizer to expect: websites, apps, decks and slide titles."""
        return list(dict.fromkeys([*self.places, *(s.doc.replace("_", " ") for s in self.slides), *(s.title for s in self.slides)]))

    def sims(self, query: np.ndarray, said: str = "", here: str | None = None) -> np.ndarray:
        """How close each slide is to a request naming it: by meaning, and by how much of its title and its deck's name
        the sentence says. A deck's first slide also stands for the deck as a whole, and slides of the deck `here` (the
        one on screen) are preferred."""
        w = stems(said)
        s = self.slide_vecs @ query + NAMES * np.array([sum(v for t, v in n.items() if t in w) / (sum(n.values()) or 1) for n in self.names])
        s[self.covers] = np.maximum(s[self.covers], self.deck_vecs @ query)
        return s + np.array([HERE_BONUS if x.doc == here else 0.0 for x in self.slides])

    def fits(self, query: np.ndarray) -> np.ndarray:
        """How much closer each file is to a request than a request naming nothing is."""
        return self.file_vecs @ query - self.file_empty

    def vec(self, a: Action) -> np.ndarray | None:
        """The vector of a slide or file, to compare it with what the presenter says."""
        if a.kind == "slide":
            return self.slide_vecs[self.slides.index(self.by_id[a.target])]
        return self.file_vecs[self.files.index(a.target)] if a.kind == "file" and a.target in self.files else None

    def place(self, said: str) -> Action | None:
        """The website or app the sentence names. Runs of up to three words are compared without spaces, so "git hub"
        finds GitHub; the closest name wins, and the longest of equally close ones ("Google Docs" over "Google"). A
        one-word name that is written like a plain word (Notes, Photos) counts only where it is used as a name:
        capitalized after the first word, right after "to" or "open" with no article, or next to "app" or "website".
        "The warehouse photo" is no app."""
        raw = "".join(c if c.isalnum() else " " for c in said).split()
        w = [x.lower() for x in raw]
        named = {i for i, x in enumerate(raw)
                 if i and (x[:1].isupper() or w[i - 1] in GOVERNS) or NAMED & set(w[max(i - 1, 0):i + 2])}
        # by ear, "laws hack" is LauzHack, but not when a short word ("a", "to") is part of it: "open a" is no OpenAI
        runs = {(n, run := "".join(w[i:i + n]), n > 1 or i in named, sound(run) if min(map(len, w[i:i + n])) >= 3 else "")
                for n in (1, 2, 3) for i in range(len(w) - n + 1)}
        close: dict[str, float] = {}
        for name in self.places:
            parts = tokens(name)
            plain = len(parts) == 1 and name[1:].islower()  # "Notes", not "GitHub"
            m = SequenceMatcher(None, "", squashed := "".join(parts))
            heard = sound(squashed)
            for n, run, proper, said_as in runs:
                if plain and not proper:
                    continue
                m.set_seq1(run)
                # a run of more or fewer words than the name must spell it exactly, so "open a" is not OpenAI
                same = n == len(parts)
                r = m.ratio() if same and m.real_quick_ratio() >= FUZZY and m.quick_ratio() >= FUZZY else float(run == squashed)
                if not plain and r < FUZZY and len(heard) >= 5 and said_as == heard:  # spelled by ear (a name spelled like a word is spelled right)
                    r = FUZZY
                if r >= FUZZY:
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


@dataclass(frozen=True)
class Reading:
    """One way to read the sentence: as a whole, or by one of its clauses ("okay, back to the slides" in "Okay, back to
    the slides. The problem we solve is...")."""
    probs: dict[str, float]  # the classifier's labels
    groups: dict[str, float]  # the kinds of request that can apply to the screen as the sentence began, renormalized

    @property
    def best(self) -> str:
        return max(self.groups, key=self.groups.__getitem__)

    @property
    def p(self) -> float:
        return self.groups[self.best]


@dataclass(frozen=True)
class Decision:
    """What a sentence calls for: an action on the shared screen when the brain is sure, otherwise what to offer the
    presenter in their own overlay (one action is a suggestion, several ask which one), or neither."""
    do: Action | None = None
    offer: tuple[Action, ...] = ()
    why: str = ""


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
        self.offer: tuple[Action, ...] = ()  # offered at the end of the last sentence, for this one to pick from
        self.offered: tuple[Action, ...] = ()  # offered at the end of this sentence
        self.pending = ""  # the last sentence, when it was a request cut off before its object
        self.unfinished = ""  # this sentence, when it is one

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

    def read(self, text: str) -> tuple[list[Reading], np.ndarray]:
        """The sentence read as a whole, then by each of its clauses, and its vector to look things up with, in one call
        to the embedding model."""
        texts = readings(text)
        *cls, query = embed(prompts(text))
        v = self.start
        on, shown = v.onair, v.last is not None
        possible = {"next": shown, "previous": shown, "return": shown and on != v.last, "pick": bool(self.offer),
                    "control": on is not None and on.kind in ("url", "file", "ask"), "close": on is not None}
        out = []
        for t, c in zip(texts, cls):
            probs, groups = self.intents(c, t), defaultdict(float)
            for label, prob in probs.items():
                if possible.get(g := label.split()[0], True):
                    groups[g] += prob
            total = sum(groups.values())
            out.append(Reading(probs, {g: p / total for g, p in groups.items()}))
        return out, query

    def choose(self, readings: list[Reading], gate: float) -> Reading:
        """The reading to act on: the last clause that is surely a request, unless a clause after it puts it off ("...,
        but we're short on time"). A remark after it does not ("let me open your contract, it's in there"), though a
        sentence that is surely a remark as a whole outweighs a less sure request inside it that is not its last
        clause. With no clause surely a request, the sentence is read as a whole."""
        whole, parts = readings[0], readings[1:]
        sure = [(i, r) for i, r in enumerate(parts) if r.best != "talk" and r.p >= gate]
        asks = [(i, r) for i, r in sure if r.best not in QUIET]
        if not asks:
            return whole
        i, ask = asks[-1]
        if later := next((r for j, r in sure if j > i and r.best == "later"), None):
            return later
        return ask if i == len(parts) - 1 or whole.best not in QUIET or ask.p >= whole.p else whole

    def decide(self, said: str, end: bool) -> Decision:
        """What the sentence so far calls for, and why. `end` means the presenter paused or finished; requests for the
        vision model and offers to the presenter wait for it, since they need the whole request."""
        if not (self.any_language or english(said)):
            return Decision(why="not English")
        said = said[-300:]  # the end of a long sentence is what matters
        gate = self.threshold if end else max(self.threshold, PARTIAL)
        readings, query = self.read(said)
        r = self.choose(readings, gate)
        if self.pending and (r.best in QUIET | {"talk"} or r.p < self.threshold):
            # a request cut off before its object ("can you pull up, um") and the object said next are one request
            more, more_query = self.read(f"{self.pending} {said}")
            if (joined := self.choose(more, gate)).best not in QUIET | {"talk"}:
                said, readings, query, r = f"{self.pending} {said}", more, more_query, joined
        best, p, probs = r.best, r.p, r.probs
        control = max((l for l in probs if l.startswith("control ")), key=probs.__getitem__).removeprefix("control ")
        waits = best == "talk" or best == "slide" and tokens(said)[-1:] in (["slide"], ["page"], ["number"])  # a number may follow
        if end:
            self.unfinished = said if best == "unfinished" else ""
        v, g = self.start, r.groups
        look = (self.catalog.sims(query, said, self.catalog.by_id[v.last.target].doc if v.last else None), control, query,
                (g.get("open", 0) - g.get("slide", 0)) / (g.get("open", 0) + g.get("slide", 0) + 1e-9))
        if p >= gate:
            d = self.resolve(best, said, *look) if end or not waits else Decision(why="waits for the end")
        elif end and p >= FALLBACK_LOW and best not in QUIET | {"talk"}:
            d = self.unsure(r.groups, said, *look)
        else:
            d = Decision(why="unsure")
        why = ", ".join(f"{label} {probs[label]:.2f}" for label in sorted(probs, key=probs.__getitem__, reverse=True)[:3])
        why += f"; {best} {p:.2f}{' (clause)' if r is not readings[0] else ''}: {d.why}"
        do = d.do
        if not end:  # the transcriber still revises live words, most of all an address it spelled and no place known
            sure = do == self.guess and (do is None or do.kind != "url" or do in self.catalog.places.values())
            do, self.guess = do if sure else None, do
        if not do or do in self.done or do == self.view.onair or do.kind == "ask" and (not end or p < ASK or any(a.kind == "ask" for a in self.done)):
            do = None
        else:
            self.done.append(do)
        if end:
            self.offered = d.offer
        return Decision(do, d.offer if end else (), why)

    def resolve(self, group: str, said: str, sims: np.ndarray, control: str, query: np.ndarray, lean: float = 0.0) -> Decision:
        """What a kind of request calls for, from the screen as the sentence began. `query` is the sentence's vector,
        `sims` how close each slide is to it (Catalog.sims), and `lean` Brain.show's."""
        cat, v = self.catalog, self.start
        here = cat.by_id[v.last.target] if v.last else cat.slides[0]
        match group:
            case "talk":
                return self.follow(sims)
            case "pick":
                return self.pick(said, query)
            case "slide" | "open" if (n := slide_number(said)) is not None:
                deck = [s for s in cat.slides if s.doc == here.doc]
                s = deck[n - 1] if 0 < n <= len(deck) else deck[-1] if n == -1 else None
                return Decision(Action("slide", s.id) if s else None, why=f"slide number {n}")
            case "next" | "previous":
                if group == "previous" and v.onair != v.last:
                    return Decision(v.last, why="back to the slides")
                s = cat.by_id.get(f"{here.doc} p{here.page + (1 if group == 'next' else -1)}")
                return Decision(Action("slide", s.id) if s else None, why=group)
            case "return":  # the last slide shown, unless the sentence names another clearly better
                named = sims - cat.slide_empty
                i = int(np.argmax(named))
                s = cat.slides[i] if named[i] > named[cat.slides.index(here)] + MARGIN else here
                return Decision(Action("slide", s.id), why="back to the slides")
            case "open" if (url := domain(said)):  # "lozac dot com" is how the transcriber spells LauzHack's address
                known = cat.place(urllib.parse.urlsplit(url).hostname.removeprefix("www.").split(".")[0])
                return Decision(known if known and known.kind == "url" else Action("url", url), why="address")
            case "open" if (a := cat.place(said)):
                return Decision(a, why=f"place {a.target}")
            case "slide" | "open":
                return self.show(group, said, sims, query, lean)
            case "search" | "click":
                return Decision(Action("ask", said), why="for the vision model")
            case "control":
                return Decision(Action("control", control), why="control")
            case "close":
                return Decision(Action("close"), why="close")
        return Decision(why=group)

    def show(self, group: str, said: str, sims: np.ndarray, query: np.ndarray, lean: float) -> Decision:
        """A slide or a file, whichever fits the words better once the classifier's reading is counted: `lean` runs
        from -1 (surely a slide) to 1 (surely something outside the deck). A request outside the deck that fits no file
        goes to the vision model."""
        i = int(np.argmax(sims))
        named = float(sims[i] - self.catalog.slide_empty[i]) - LEAN * lean  # how much the slide is named, as Catalog.fits
        d = self.find(query)
        if (d.do or d.offer) and float(self.catalog.fits(query).max()) + LEAN * lean > named:
            return d
        if named < NAMED_MIN and group == "open":
            return Decision(Action("ask", said), why="for the vision model")
        if named < NAMED_MIN:  # "show the slides": the last one shown, or the first deck
            v = self.start
            return Decision(Action("slide", v.last.target if v.last else self.catalog.slides[0].id), why="the slides, none named")
        return Decision(Action("slide", self.catalog.slides[i].id), why="closest slide")

    def find(self, query: np.ndarray) -> Decision:
        """The file the request describes, or the few that fit it about as well as the best, to choose from."""
        if not self.catalog.files:
            return Decision()
        s = self.catalog.fits(query)
        order = np.argsort(-s)[:3]
        if s[order[0]] < NAMED_MIN:
            return Decision(why=f"no file, closest {s[order[0]]:.2f}")
        near = tuple(Action("file", self.catalog.files[i]) for i in order if s[i] >= s[order[0]] - CLOSE)
        return Decision(near[0], why=f"file {s[order[0]]:.2f}") if len(near) == 1 else Decision(offer=near, why="which file?")

    def pick(self, said: str, query: np.ndarray) -> Decision:
        """One of the actions offered after the last sentence: the only one, the one named by position ("the second
        one", "number two"), or the one the words describe ("the revised one")."""
        offer, w = self.offer, tokens(said)
        if len(offer) == 1:
            return Decision(offer[0], why="accepted")
        n = next((ORDINALS[x] for x in w if x in ORDINALS), None)
        if n is None:
            n = next((NUMBERS[b] - 1 for a, b in zip(w, w[1:]) if a in ("number", "option") and b in NUMBERS), None)
        if n is not None and -len(offer) <= n < len(offer):
            return Decision(offer[n], why=f"picked by position {n}")
        scores = sorted(((float(x @ query) if (x := self.catalog.vec(a)) is not None else -1.0, k) for k, a in enumerate(offer)), reverse=True)
        if scores[0][0] - scores[1][0] < CLOSE:  # the words describe none of them better than another
            return Decision(why="no pick")
        return Decision(offer[scores[0][1]], why="picked by description")

    def follow(self, sims: np.ndarray) -> Decision:
        """While the slides are on air, the slide ahead in the deck that the talk has moved on to: shown when it clearly
        fits better than the one on air, each slide skipped counting against it, and suggested when it fits a little
        better."""
        v, slides = self.start, self.catalog.slides
        if not v.last or v.onair != v.last:
            return Decision(why="talk, slides not on air")
        i = slides.index(self.catalog.by_id[v.last.target])
        ahead = [(sims[i + k] - JUMP * (k - 1), k) for k in range(1, len(slides) - i) if slides[i + k].doc == slides[i].doc]
        score, k = max(ahead, default=(-1.0, 0))
        if score > sims[i] + MARGIN:
            return Decision(Action("slide", slides[i + k].id), why=f"follow +{k}")
        if score > sims[i] + SUGGEST:
            return Decision(offer=(Action("slide", slides[i + k].id),), why=f"suggest +{k}")
        return Decision(why="talk, stay")

    def unsure(self, groups: dict[str, float], said: str, sims: np.ndarray, control: str, query: np.ndarray, lean: float) -> Decision:
        """A sentence the classifier is unsure about. The decision model, when on, picks among the actions its three
        likeliest readings call for, or keeping the screen; otherwise, or when it keeps the screen, the likeliest one
        that shows something is suggested to the presenter."""
        v = self.start
        options: dict[str, tuple[str, Action]] = {}
        for g in sorted((g for g in groups if g not in QUIET), key=groups.__getitem__, reverse=True)[:3]:
            d = self.resolve(g, said, sims, control, query, lean)
            if (a := d.do or next(iter(d.offer), None)) and a != v.onair and str(a) not in options:
                options[str(a)] = (f"{self.describe(a)}: {HINTS.get(g, g)}", a)
        if not options:
            return Decision(why="unsure, nothing to offer")
        if self.fallback:
            lines = [f"ON SCREEN: {self.describe(v.onair)}"]
            if v.last and v.onair != v.last:
                lines.append(f"LAST SLIDE SHOWN: {self.describe(v.last)}")
            lines += ["EARLIER SPEECH:", *([h[-160:] for h in self.history[-2:]] or ["(start of talk)"]), f"NEW SENTENCE: {said}"]
            criteria = {"keep": KEEP} | {label: text for label, (text, _) in options.items()}
            r = ask_d1({"state": "\n".join(lines), "questions": {"screen": {"type": "choice", "instructions": QUESTION, "criteria": criteria}}})
            probs = r["answers"]["screen"]["probabilities"]
            label = max((l for l in probs if l != "keep"), key=probs.__getitem__)
            if probs[label] >= 0.5:
                return Decision(options[label][1], why=f"fallback d1 {label} {probs[label]:.2f}")
        shown = [a for _, a in options.values() if a.kind in SHOWN]
        return Decision(offer=tuple(shown[:1]), why="suggested" if shown else "unsure")

    def moved(self, a: Action) -> None:
        """Record what the screen shows after doing `a`, as the screen reports it."""
        self.view = self.view.after(a)

    def heard(self, said: str) -> None:
        """The sentence is over. What it offered can be picked during the next one, and a request it left unfinished is
        read together with it."""
        if self.any_language or english(said):
            self.history.append(said)
        self.start, self.guess, self.done = self.view, None, []
        self.offer, self.offered, self.pending, self.unfinished = self.offered, (), self.unfinished, ""


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
FILES = Path(__file__).parent / "fixtures/files.txt"  # stand-in for the presenter's files in evals


def evaluate(scripts: list[Path], library: Path, intents: Intents, threshold: float, step: int = 2, hear=lambda said: said,
             fallback: bool = True) -> None:
    """Replay scripted talks through a Brain, the sentence so far every `step` words as the microphone streams it, and
    score the screen after each line against its "want": any of null (nothing shown), a slide id (that slide shown),
    "keep" (nothing done), "choose" (several actions offered to the presenter), or "kind" / "kind target" (an action of
    that kind, or exactly that action, done). A line whose change was missed but offered counts as suggested."""
    catalog = Catalog(library, APPS, FILES.read_text().splitlines())
    totals, ms = defaultdict(int), []
    for script in scripts:
        brain, counts = Brain(catalog, intents, threshold, fallback=fallback), defaultdict(int)
        print(f"\n{script}")
        for i, line in enumerate((json.loads(l) for l in script.read_text().splitlines() if l.strip()), 1):
            said, want = hear(line["say"]), line["want"]
            before, taken, offered, trace, words, why = brain.onair, [], (), [], said.split(), ""
            for n in [*(range(step, len(words), step) if step else []), len(words)]:
                t = time.perf_counter()
                d = brain.decide(" ".join(words[:n]), end=n == len(words))
                ms.append((time.perf_counter() - t) * 1e3)
                why = d.why
                if d.do:
                    brain.moved(d.do)
                    taken.append(d.do)
                    trace.append(f'"{" ".join(words[:n])}" -> {d.do} ({why})')
                offered = d.offer or offered
            trace.append(f"end: {why}" + (f"; offered {'; '.join(map(str, offered))}" if offered else ""))
            brain.heard(said)
            shows = lambda onair: any(onair is None if w is None else w in catalog.by_id and onair == Action("slide", w) for w in want)
            did = any(w and w not in catalog.by_id and w != "keep" and w in (a.kind, str(a)) for w in want for a in taken)
            did |= "choose" in want and len(offered) > 1
            should = not shows(before) and "keep" not in want
            if taken and not should:
                verdict = "false switch"
            elif shows(brain.onair) or did or not taken and not should:
                verdict = "ok"
            elif not taken and any(w and (a.target == w if w in catalog.by_id else w in (a.kind, str(a))) for w in want for a in offered):
                verdict = "suggested"
            else:
                verdict = "wrong" if taken else "missed"
            counts[verdict] += 1
            shown = "; ".join(map(str, taken)) or ("? " + " | ".join(map(str, offered)) if offered else "-")
            print(f"{'ok ' if verdict == 'ok' else 'BAD'} {i:2} {shown:48.48} | {said[:72]}")
            if verdict != "ok":
                print(f"       {verdict}: want {want}, on screen {brain.describe(brain.onair)}" + "".join(f"\n         {t}" for t in trace))
        print(summary(script.stem, counts))
        for k, v in counts.items():
            totals[k] += v
    ms.sort()
    print(f"\n{summary('total', totals)}  decision p50 {ms[len(ms) // 2]:.0f}ms p95 {ms[int(len(ms) * .95)]:.0f}ms ({len(ms)} decisions)")


def summary(name: str, c: dict) -> str:
    return (f"{name:16} screen correct {c['ok']}/{sum(c.values())}  or suggested {c['ok'] + c['suggested']}  "
            f"false switches {c['false switch']}  missed {c['missed']}  wrong {c['wrong']}")

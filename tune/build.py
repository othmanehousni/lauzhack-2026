"""Training and dev examples for the decider, composed from hand-written families, the labelled sentences of intents.py
(with what each request refers to in refs.py) and synthetic catalogs (decks.py, world.py). Deterministic for a seed; no
model. Never copies an eval/ sentence: any composed sentence too close to one is dropped (leak()).

  python3 tune/build.py [--seed 0] [--train 60000] [--dev 3000]   tune/data/{train,dev}.jsonl, counts per family
  python3 tune/build.py golden                                    tune/golden.jsonl, the Kotlin prompt builder's test cases
"""
import argparse
import json
import random
import re
import sys
import zlib
from collections import Counter
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE.parent))
from intents import INTENTS  # noqa: E402

sys.path.insert(0, str(HERE))
from prompt import CONTROLS, grammar, prompt, spoken  # noqa: E402


from decks import DECKS  # noqa: E402
from refs import REFS  # noqa: E402
from world import APPS, FILES, HOMOPHONES, SITES, SOUNDS  # noqa: E402

HOMOPHONES = {k: [v for v in vs if v not in ("look up", "we can't", "can't you")] for k, vs in HOMOPHONES.items()}
COMMON = {"the", "a", "and", "that", "this", "now", "up", "down", "back", "to", "can you", "okay", "please"}

ONES = ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen "
        "eighteen nineteen").split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()
ORDS = ("first second third fourth fifth sixth seventh eighth ninth tenth eleventh twelfth thirteenth fourteenth fifteenth "
        "sixteenth seventeenth eighteenth nineteenth twentieth").split()
PEOPLE = ["Sarah", "Tom", "Priya", "Marco", "Lena", "Ben", "Aisha", "Jonas", "Chloe", "David", "Mia", "Karim", "Elena", "Yuki",
          "Omar", "Julia", "Noah", "Ines", "Paul", "Fatima", "Leo", "Nadia", "Sven", "Rosa"]
MONTHS = "January February March April May June July August September October November December".split()
KINDS = {"pdf": ["pdf", "document"], "docx": ["document", "doc", "word doc"], "pages": ["document"], "xlsx": ["spreadsheet", "sheet", "excel"],
         "csv": ["csv", "spreadsheet"], "numbers": ["spreadsheet"], "pptx": ["presentation"], "key": ["keynote"],
         "png": ["picture", "image", "screenshot"], "jpg": ["photo", "picture"], "heic": ["photo"], "mp4": ["video", "recording", "clip"],
         "mov": ["video", "clip"], "m4a": ["recording", "audio", "voice memo"], "txt": ["notes", "text file"], "md": ["notes", "readme"]}
VIDEO_SITES = {"YouTube", "Twitch", "Netflix", "Loom", "Vimeo", "TikTok"}
VIDEO_FILES = {"mp4", "mov"}


def words(n: int) -> str:
    return ONES[n] if n < 20 else TENS[n // 10] + ("" if n % 10 == 0 else " " + ONES[n % 10])


def dev(key: str) -> bool:
    return zlib.crc32(key.encode()) % 8 == 0


def norm(s: str) -> list[str]:
    return re.findall(r"[a-z0-9']+", s.lower())


EVAL_SAYS = [set(norm(json.loads(l)["say"])) for p in sorted((HERE.parent / "eval").glob("*.jsonl")) for l in p.read_text().splitlines() if l.strip()]


def leak(s: str) -> bool:
    """A composed sentence that is an eval sentence, or of five words or more shares 70% of its words with one."""
    w = set(norm(s))
    return any(w == e or len(w) >= 5 and len(w & e) / len(w | e) >= 0.7 for e in EVAL_SAYS)


# ---------------------------------------------------------------- catalog items

def deck_name(d: dict) -> str:
    return d["name"].replace("_", " ")


def slide(d: dict, i: int) -> dict:
    s = d["slides"][i]
    return {"kind": "slide", "deck": deck_name(d), "page": i + 1, "pages": len(d["slides"]), "title": s["title"], "text": "; ".join(s["bullets"])}


def file_item(path: str) -> dict:
    parts = path.split("/")
    return {"kind": "file", "name": parts[-1], "folder": " / ".join(parts[:-1][-2:])}


def site_item(name: str, url: str) -> dict:
    return {"kind": "site", "name": name, "url": url}


def app_item(name: str) -> dict:
    return {"kind": "app", "name": name}


def ext(f: dict) -> str:
    return f["name"].rsplit(".", 1)[-1].lower()


def stem(f: dict) -> str:
    return f["name"].rsplit(".", 1)[0]


def label(c: dict) -> str:
    return stem(c) if c["kind"] == "file" else c.get("title") or c["name"]


def key(c: dict) -> str:
    return json.dumps(c, sort_keys=True)


class World:
    def __init__(self, split: str):
        pick = (lambda k: dev(k)) if split == "dev" else (lambda k: not dev(k))
        self.decks = [d for d in DECKS if pick(d["name"])]
        self.files = [file_item(f) for f in FILES if pick(f)]
        self.sites = [site_item(n, u) for n, u in SITES]
        self.apps = [app_item(a) for a in APPS]


class Session:
    """The presenter's world for one example: a few decks, a folder of files, bookmarks, apps."""

    def __init__(self, rng: random.Random, w: World):
        self.rng = rng
        self.decks = rng.sample(w.decks, rng.choice([1, 1, 2, 2, 3, 4]))
        self.files = rng.sample(w.files, rng.randint(12, 50))
        self.sites = rng.sample(w.sites, rng.randint(8, 40))
        self.apps = rng.sample(w.apps, rng.randint(5, 15))
        self.slides = [slide(d, i) for d in self.decks for i in range(len(d["slides"]))]

    def any(self, kinds=("slide", "file", "site", "app")) -> dict:
        pools = {"slide": self.slides, "file": self.files, "site": self.sites, "app": self.apps}
        return self.rng.choice(pools[self.rng.choice([k for k in kinds if pools[k]])])

    def screen(self, kind: str):
        """(screen, behind) for a screen of this kind: desktop, slide, front (a site, file or app, maybe over slides)."""
        r = self.rng
        if kind == "desktop":
            return None, None
        if kind == "slide":
            return r.choice(self.slides), None
        front = r.choice(self.sites + self.files + self.apps) if kind == "front" else r.choice({"site": self.sites, "file": self.files, "app": self.apps}[kind])
        return front, (r.choice(self.slides) if r.random() < 0.7 else None)


def candidates(rng: random.Random, s: Session, targets: list[dict], near: list[dict] = (), pinned: list[dict] = (), k: int | None = None,
               avoid: list[dict] = ()) -> list[dict]:
    """A retrieval result: the targets somewhere near the top, near-misses around them, then other items of the session."""
    k = k or rng.choice([8] * 8 + [3, 4, 5, 6, 7])
    taken = {key(c) for c in [*pinned, *avoid]}
    out: list[dict] = []
    for c in [*targets, *rng.sample(list(near), len(near))]:
        if key(c) not in taken and len(out) < k - len(pinned):
            out.append(c)
            taken.add(key(c))
    for _ in range(60):
        if len(out) >= k - len(pinned):
            break
        c = s.any()
        if key(c) not in taken:
            out.append(c)
            taken.add(key(c))
    tset = {key(t) for t in targets}
    head = [c for c in out if key(c) in tset]
    rest = [c for c in out if key(c) not in tset]
    rng.shuffle(rest)
    for c in head:
        rest.insert(min(len(rest), rng.choice([0] * 10 + [1] * 4 + [2] * 3 + [3] * 2 + [4, 5])), c)
    return [*pinned, *rest][:max(k, len(pinned))]


def index(cands: list[dict], c: dict) -> int:
    return [key(x) for x in cands].index(key(c)) + 1


def near_slides(s: Session, sl: dict) -> list[dict]:
    same = [x for x in s.slides if x["deck"] == sl["deck"] and abs(x["page"] - sl["page"]) <= 2 and x != sl]
    alike = [x for x in s.slides if x["title"].lower() == sl["title"].lower() and x != sl]
    return same + alike


def near_files(s: Session, f: dict) -> list[dict]:
    w = set(norm(stem(f)))
    return sorted((x for x in s.files if x != f and set(norm(stem(x))) & w), key=lambda x: -len(set(norm(stem(x))) & w))[:4]


def near_sites(s: Session, c: dict) -> list[dict]:
    w = set(norm(c["name"]))
    return [x for x in s.sites + s.apps if x != c and set(norm(x["name"])) & w][:3]


# ---------------------------------------------------------------- noise

def noise(rng: random.Random, text: str, p: float = 1.0) -> str:
    """How a live recognizer may write it: no capitals or punctuation, sound-alikes, stutters, a dropped letter."""
    if rng.random() < 0.45 * p:
        text = re.sub(r"[^\w\s'-]", "", text.lower())
    elif rng.random() < 0.3 * p:
        text = text.lower()
    if rng.random() < 0.5 * p:
        hits = [k for k in HOMOPHONES if (k not in COMMON or rng.random() < 0.25) and re.search(rf"\b{re.escape(k)}\b", text, re.I)]
        for k in rng.sample(hits, min(len(hits), rng.choice([1, 1, 1, 2]))):
            text = re.sub(rf"\b{re.escape(k)}\b", lambda m: rng.choice(HOMOPHONES[k]), text, count=1, flags=re.I)
    w = text.split()
    if w and rng.random() < 0.12 * p:
        i = rng.randrange(len(w))
        n = rng.choice([1, 1, 2])
        w[i:i] = w[i:i + n]
    if w and rng.random() < 0.06 * p:
        i = rng.randrange(len(w))
        if len(w[i]) > 5:
            j = rng.randrange(1, len(w[i]) - 1)
            w[i] = w[i][:j] + w[i][j + 1:]
    return " ".join(w)


def heard_name(rng: random.Random, name: str, p: float = 0.4) -> str:
    if name in SOUNDS and rng.random() < p:
        return rng.choice(SOUNDS[name])
    w = name.split()
    if len(w) > 1 and rng.random() < 0.2:
        name = w[-1] if w[0] in ("Google", "Microsoft", "Apple", "Adobe", "Amazon", "Visual") or rng.random() < 0.5 else w[0]
    return name if rng.random() < 0.6 else name.lower()


def spoken_number(rng: random.Random, n: int) -> str:
    return str(n) if rng.random() < 0.35 else words(n)


def spoken_address(rng: random.Random, host: str) -> str:
    out = []
    for part in re.split(r"([./-])", host):
        if part == ".":
            out.append("dot")
        elif part == "/":
            out.append("slash")
        elif part == "-":
            out.append(rng.choice(["dash", "hyphen"]))
        elif part == "www":
            out.append(rng.choice(["w w w", "www"]))
        elif len(part) == 2 and part.isalpha() and rng.random() < 0.4:
            out.append(" ".join(part))
        elif part:
            out.append(part)
    return " ".join(out)


# ---------------------------------------------------------------- sentence banks

OPENERS = ["can you pull up", "could you pull up", "pull up", "let's pull up", "let me pull up", "can you bring up", "could you bring up",
           "bring up", "let me bring up", "can you show", "could you show", "show", "show me", "show us", "can we see",
           "could we see", "can we look at", "let's look at", "let's have a look at", "can we have", "can I have", "can you put up",
           "put up", "let's get", "can you get", "can we get", "I'd like to show you", "I want to show you", "now let's see",
           "can you take us to", "take us to", "go to", "let's go to", "can you go to", "jump to", "let's jump to", "can you jump to",
           "can you open", "open", "let me open", "let's open", "could you open", "would you mind opening", "can you throw up",
           "can you pop up", "I'll pull up", "I'm going to pull up", "let me just pull up", "can you quickly pull up", "could you put up",
           "let's switch to", "switch to", "can you switch to", "flip to", "can you flip to", "let me grab", "can you grab",
           "can you get me", "let me get", "we need", "I need", "give me", "pull", "can you find", "find me", "load"]
OPENER_FORMS = ["{v} {o}", "{v} {o}", "{v} {o}", "{v} {o}", "put {o} on the screen", "get {o} up", "can you get {o} up",
                "bring {o} up", "can we have {o} on screen", "{o}, please", "{o} please", "can you put {o} up", "let's have {o} up",
                "I want {o} on the screen", "could we get {o} on screen"]
HEDGES = ["", "", "", "", "okay ", "so ", "alright ", "um ", "and ", "right, ", "now ", "maybe we can ",
          "I think we should ", "if you could ", "let's quickly ", "actually ", "sorry, ", "okay so ", "ah, ", "uh ", "and then ",
          "so yeah, ", "anyway, ", "okay, so now ", "and now ", "just ", "let's see, ", "hmm, ", "yeah so "]
TAILS = ["", "", "", "", "", ", please", " please", " for a second", " real quick", " so everyone can see", ", thanks", " now",
         " again", "?", ".", " for a sec", " quickly", " if you can", ", that'd be great", " for everyone"]
CANCELS = [", hmm, on second thought, not now", ". no wait, forget it", ", but let's do that at the end", "... actually no, later", ", or no, let's skip it", ". nah, forget it",
           ", actually let's not", ", well, maybe after the break", ", no, scratch that", " — never mind, it's not important",
           ", but I'll do it after the call", ", but there's no time today", ". actually, no, we'll come back to it"]
def cancel(rng) -> str:
    """A request taken back at its end, composed: ", hmm, no, skip it"."""
    while True:
        c = (pick(rng, [", actually", ", hmm", ". no wait", ", oh", ", or", ", wait", ", uh", "... well"]) + pick(rng, [" no", " nah", "", " no no"]) +
             pick(rng, [", never mind", ", forget it", ", scratch that", ", not now", ", skip it", ", let's not", ", leave it", ", doesn't matter",
                        ", not important", ", another time"]))
        if c != ", actually no, never mind":
            return c


PUT_OFF = ["we'll look at {o} after the break", "I'll pull up {o} later", "{o} comes later, hold that thought",
           "I'm not going to open {o} today", "let's leave {o} for the Q&A", "I'll send {o} around after the call instead", "remind me to show {o} later",
           "we won't have time for {o}, sorry", "I'd show you {o} but it's not ready yet"]
MENTIONS = ["I sent {o} to {p} this morning", "can you forward {o} to {p}", "I'll share {o} in the chat later", "{p} made {o} last week", "we finished {o} on Friday",
            "I spent all night on {o}", "{o} is already in everyone's inbox", "remember {o}? {p} still has questions about it",
            "I printed {o} for the board", "{p} asked me about {o} yesterday", "we argued about {o} for an hour",
            "I think {p} has the latest version of {o}", "don't worry about {o}, it's handled", "{o} was a pain to put together",
            "we'll need to update {o} before Monday", "the numbers in {o} are from last quarter", "I uploaded {o} to the drive",
            "{p}, did you get {o}?", "so {p} and I worked on {o} together",
            "I might have broken {o}, to be honest", "{o} has been haunting me all week"]
TOOL_MENTIONS = ["we use {o} for basically everything", "we built the first version in {o}", "I was on {o} all evening",
                 "the link to {o} is in the invite", "{p} found this on {o}", "our team lives in {o}", "I drafted it in {o} first",
                 "we launched on {o} last spring", "{o} has been slow all day", "I'll post it on {o} afterwards",
                 "if you've used {o}, it's a bit like that", "I saw a thread about this on {o}", "{o} went down this morning",
                 "we moved off {o} last year", "everything is synced to {o}", "you can find me on {o}", "{o} keeps crashing on my laptop",
                 "we found most of our users through {o}", "honestly {o} is the best tool we have", "I had {o} open earlier"]
PRONOUN_REQ = ["can we see it", "let's look at it", "let's look at that", "open it", "put that up",
               "show them", "can you open that", "bring that up", "let's have it on screen", "can you pull that up", "show it",
               "let's see it", "put it on the screen", "open that for us", "let's go there",
               "can you show that", "show everyone", "let me bring it up"]
DESCRIBE_FILE = ["{p} sent over {o} yesterday", "there's {o} with all the details", "{o} has the full breakdown",
                 "we updated {o} after the call with {p}", "I put everything in {o}", "the answer is in {o}", "{p} signed {o} in {m}",
                 "we redid {o} last {m}", "I have {o} right here"]
DESCRIBE_SITE = ["there's a great thread on {o} about exactly this", "{o} has a nice page on that", "we put all of it on {o}",
                 "{p} posted it on {o}", "the best explanation I found is on {o}", "you can see it live on {o}"]
DESCRIBE_SLIDE = ["I made a slide on {o}", "there's a chart for {o} in the deck", "we have the numbers on {o}", "I've got a slide about {o}",
                  "{o} is a few slides ahead", "we covered {o} in the deck too"]
REJECT = ["no, not that", "that's the wrong one", "wrong one", "not that one", "no, go back", "hmm, that's not it", "no no, back", "that's not what I meant", "no, wrong file", "ugh, no, not that", "no, put back what we had", "that's not it, go back", "no, undo that", "oops, not that",
          "no, that's wrong", "nah, not that one, go back", "no, I didn't mean that"]
YES = ["yes", "yeah", "yep", "sure", "yes please", "go ahead", "yeah, that one", "sure, show it", "okay, do it", "yeah go for it",
       "uh huh, yes", "that's the one", "perfect, yes", "mm-hmm, put it up", "the first one is fine"]
NO = ["no", "no thanks", "neither", "none of those", "not those", "no, neither of them", "nah", "nope, none of them", "no, leave it",
      "neither of those, never mind", "no, forget it"]
FILLERS = ["um", "uh", "so", "okay", "yeah", "mm-hmm", "right", "uh huh", "hmm", "okay okay", "so um", "and uh", "well", "yeah yeah",
           "right right", "so yeah", "um so", "okay so", "alright", "and", "the", "uh the", "so the", "I mean", "you know", "like",
           "um, yeah", "okay, cool", "good", "great", "sure", "yes", "no", "oh", "ah", "huh", "hm hm", "up", "and the", "so we"]
BACKGROUND = ["honey can you close the door", "do you want a coffee", "the kids are still at school", "I'll call you back in five minutes",
              "is the delivery here yet", "yeah no I'm in a meeting", "can you turn the music down", "did you feed the cat",
              "what time is it", "the train is late again", "sorry, my neighbour is drilling", "I'm on a call, give me ten minutes", "put it on the table please", "where did I put my glasses", "is it raining outside",
              "the dog wants to go out", "we're out of milk", "tell him I'll be there at six", "can you grab my charger",
              "who left the window open", "the printer is jammed again", "is that your phone ringing", "mom, where's my jacket", "the parking ticket is on the dashboard", "oh no the pasta is boiling over",
              "do we have any more of those cookies", "I'll be down in a minute", "you're breaking up, can you say that again",
              "did anyone order pizza", "I think the heating is off", "can you sign for the package", "the bus leaves at five",
              "okay I'll text you later", "turn left at the roundabout", "I forgot my badge again",
              "yes, two sugars please", "can you take the bins out tonight"]
FOREIGN = ["Est-ce que quelqu'un a vu mes clés?", "On mange où ce midi?",
           "Tu peux fermer la fenêtre s'il te plaît?", "Il fait vraiment froid dans cette salle.",
           "Kannst du bitte die Tür zumachen?", "Wo ist denn mein Ladekabel?",
           "Das Meeting dauert noch zehn Minuten.", "Hast du den Bericht schon gelesen?", "Un momento, por favor, ahora vuelvo.",
           "¿Alguien quiere un café?", "No encuentro mis gafas.", "Scusa, arrivo subito.", "Hai visto il mio telefono?",
           "Mi passi l'acqua per favore?", "Espera, que me están llamando.", "Bon, on fait une pause café?",
           "Je t'envoie le fichier ce soir.", "Kannst du mir das PDF später schicken?", "Tu peux ouvrir GitHub sur ton ordi après?",
           "Moment, ich muss das Fenster schliessen.", "Grüezi mitenand, wie gaht's?", "Ça marche, à tout à l'heure.",
           "Non, pas maintenant, je suis en réunion.", "Vamos a ver las diapositivas luego.", "Dopo ti mando le slide.",
           "Je crois que le micro ne marche pas.", "Wir sehen uns morgen im Büro.", "Il treno è in ritardo di nuovo.",
           "Olha, depois a gente conversa.", "Peux-tu baisser la musique?", "Ich habe die Folien noch nicht fertig.",
           "On regardera le tableau Excel demain.", "Il faut que je parte à cinq heures."]
PREAMBLES = ["that was a good question.", "okay so that's the context.", "sorry, I lost my train of thought.",
             "anyway, moving on.", "this one took us ages.", "okay I think everyone's here now.", "sorry about the noise.", "we can come back to that.",
             "so that's basically the idea.", "I hope that makes sense.", "good point, thanks.", "the wifi here is terrible.", "let me just check the time.", "so, um, yeah.", "that's interesting.", "I see a hand up but let's keep going.", "bear with me.", "okay, cool, cool.",
             "uh where was I.", "that went better than expected.", "and that's the gist of it.", "thanks for waiting.",
             "it's mostly the same as last time."]
INTRO = ["okay, let's get started, this session is all about {t}, so",
         "hi all, the plan for today is to talk about {t}, so", "so this morning I'll present {t}, and",
         "welcome everyone, we're here to review {t}, so", "alright, today's topic is {t}, which is why",
         "thanks for joining, today we will be presenting {t} and", "okay so the goal of this session is {t}, so",
         "good afternoon, I'm going to talk about {t} today, so"]
SLIDES_WORDS = ["the slides", "my slides", "the deck", "the presentation", "my presentation", "our slides", "the slideshow", "my deck"]
SLIDE_OBJ = ["the {t} slide", "the slide on {t}", "the slide about {t}", "the {t}", "{t}", "our {t} slide", "the {t} part",
             "the {t} section", "the {t} page", "the one about {t}", "the {t} chart", "the {t} slide again", "that {t} slide"]
CONTENT_OBJ = ["the slide with {b}", "the one where we show {b}", "the numbers on {b}", "the part about {b}", "the slide that says {b}",
               "where it says {b}"]
DECK_OBJ = ["the {d} deck", "the {d} slides", "the {d} presentation", "my {d} deck", "the {d}", "{d}", "our {d} slides", "the deck for {d}",
            "the {d} one"]
SITE_OBJ = ["{n}", "{n}", "{n}", "the {n} website", "the {n} site", "{n}'s website", "the {n} page", "our {n} page", "{n} for a second"]
APP_OBJ = ["{n}", "{n}", "the {n} app", "{n} for a sec", "the {n} application"]
FILE_OBJ = ["the {s}", "the {s} {k}", "the {k} with the {s}", "the {s} file", "{s}", "our {s}", "the {s} one", "that {s} {k}"]
NUMBER_REQ = ["go to slide {n}", "slide {n} please", "can you go to slide {n}", "jump to slide {n}", "let's go to page {n}",
              "take us to slide {n}", "back to slide {n}", "slide number {n}", "can we see slide {n}", "skip to slide {n}",
              "the {o} slide", "go to the {o} slide", "let's look at the {o} slide", "jump to {n}", "page {n}", "can you go to {n}",
              "let's jump to slide {n} for a second", "flip to slide {n}"]
SEARCHES = ["search for {q}", "google {q}", "look up {q}", "can you search {q}", "can you google {q}", "find {q} on google",
            "do a quick search for {q}", "search the web for {q}", "can you look up {q}", "let's search for {q}", "quickly google {q}"]
QUERIES = ["the euro to franc exchange rate", "weather in zurich tomorrow", "best pizza near the office", "population of switzerland",
           "train times to bern", "how solar panels work", "average rent in geneva", "the latest iphone price", "who won the match yesterday",
           "how to make sourdough bread", "largest lakes in europe", "electric car sales 2026", "what is a heat pump", "opening hours of the museum",
           "flights to lisbon in may", "how many people live in tokyo", "carbon footprint of beef", "best laptop for students",
           "symptoms of the flu", "history of the internet", "how a vaccine works", "cheap hotels in milan", "yoga classes nearby",
           "nobel prize winners 2025", "how tall is the matterhorn", "remote work statistics", "inflation rate in europe",
           "the bus timetable", "coffee shops open now", "how to tie a tie", "time in new york"]
LINKS = ["pricing", "sign up", "documentation", "about us", "careers", "log in", "the first result", "issues", "pull requests",
         "read more", "download", "contact", "blog", "features", "settings", "the second link", "get started", "faq", "the video",
         "next page", "show more", "comments", "the top answer", "subscribe", "accept", "the menu", "home"]
CLICKS = ["click on {w}", "click {w}", "hit {w}", "can you click {w}", "tap on {w}", "press {w}", "click the {w} link", "click the {w} button",
          "open the {w} tab", "go to {w} on the page", "can you click on {w}"]
UNKNOWN = ["our website", "the company homepage", "their homepage", "the customer list", "last year's report", "the video of the launch", "the org chart",
           "the contract with the supplier", "the recording from Tuesday", "the survey results", "the wiki page", "the shared calendar",
           "the floor plan", "the old logo", "the press kit", "the onboarding video", "the bug report", "the sales dashboard"]
BARE_CONTROL = ["up", "down", "scroll", "zoom", "bigger", "smaller", "back", "pause", "stop", "up a bit", "a bit lower",
                "down down", "zoom zoom", "full", "the top", "more", "go up", "go down"]


def pick(rng, xs):
    return rng.choice(xs)


def deck_spoken(rng, d: dict) -> str:
    w = re.sub(r"[_\-]+", " ", d["name"]).split()
    if len(w) > 2 and rng.random() < 0.5:
        i = rng.randrange(len(w) - 1)
        w = w[i:i + rng.randint(2, len(w) - i)]
    return " ".join(w).lower() if rng.random() < 0.7 else " ".join(w)


def file_spoken(rng, f: dict, words_kept: int | None = None) -> str:
    w = [x for x in re.split(r"[\s_\-—–()]+", stem(f)) if x]
    if len(w) > 3 and rng.random() < 0.4:
        w = w[:rng.randint(2, len(w))]
    return " ".join(w).lower() if rng.random() < 0.7 else " ".join(w)


def kind_word(rng, f: dict) -> str:
    return pick(rng, KINDS.get(ext(f), ["file"]))


# ---------------------------------------------------------------- examples

def ex(family: str, now: str, answer: str, *, screen=None, behind=None, offered=(), shown=None, heard=(), done=True, cands=()) -> dict:
    return {"family": family, "answer": answer,
            "state": {"screen": screen, "behind": behind, "offered": list(offered), "shown": shown, "heard": list(heard),
                      "now": now, "done": done, "candidates": list(cands)}}


def context(rng, s: Session, n: int | None = None) -> list[str]:
    n = rng.choice([0, 0, 1, 1, 1, 2, 2]) if n is None else n
    pool = INTENTS["aside"] + INTENTS["talk"] + [x for d in s.decks for sl in d["slides"] for x in sl["talk"]] + BACKGROUND
    return [noise(rng, pick(rng, pool), 0.6) for _ in range(n)]


def extras(rng, s: Session, screen) -> dict:
    """Unrelated offered or shown state, so neither is acted on by itself."""
    out = {}
    if screen and rng.random() < 0.15:
        out["shown"] = screen
    return out


def request(rng, s: Session, family: str, obj: str, answer: str, target: dict | None, near: list[dict], screen=None, behind=None,
            opener: str | None = None, noisy: bool = True, heard: list[str] | None = None, cands: list[dict] | None = None,
            preamble: bool = True) -> list[dict]:
    """A request, maybe after an unrelated clause or a long preamble, with recognizer noise, as finished and as growing prefixes."""
    v = opener or pick(rng, OPENERS)
    before, after = pick(rng, OPENER_FORMS).split("{o}")
    pre = ""
    if preamble and rng.random() < 0.3:
        pre = pick(rng, PREAMBLES + INTENTS["aside"][:60] + INTENTS["talk"][:60] + BACKGROUND[:10]) + " "
    hedge, verb = pick(rng, HEDGES), before.format(v=v)
    if noisy:
        pre, hedge, verb, obj = noise(rng, pre), noise(rng, hedge), noise(rng, verb), noise(rng, obj, 0.5)
    segs = [(pre, "nothing"), (hedge, "nothing"), (verb, "wait" if verb.strip() else "nothing"), (obj, answer),
            (after.format(v=v) + pick(rng, TAILS), answer)]
    c = cands if cands is not None else candidates(rng, s, [target] if target else [], near)
    if target and cands is None and key(target) not in {key(x) for x in c}:
        raise ValueError("target lost")
    if "{n}" in answer:
        answer = answer.format(n=index(c, target))
        segs = [(t, answer if label == "show {n}" else label) for t, label in segs]
    h = heard if heard is not None else context(rng, s)
    return utter(rng, family, segs, h, c, screen=screen, behind=behind)


def utter(rng, family: str, segs: list[tuple[str, str]], heard: list[str], cands: list[dict], **state) -> list[dict]:
    """The utterance whole (done), and one or two prefixes while it is spoken, each labelled by what has been said so far."""
    text_words, labels, spans = [], [], []
    for t, label in segs:
        w = t.split()
        if w:
            spans.append((len(text_words), len(text_words) + len(w), label))
            text_words += w
    labels = [None] * len(text_words)
    for _, end, label in spans:
        labels[end - 1] = label
    if not text_words:
        return []
    final = labels[-1]
    whole = " ".join(text_words)
    if leak(whole):
        return []
    out = [ex(family, whole, final, heard=heard, cands=cands, done=True, **state)]
    if rng.random() < 0.15:
        out.append(ex(family, whole, final, heard=heard, cands=cands, done=False, **state))
    if sum(1 for t, _ in segs if t.split()) < 2 and final not in ("nothing", "wait"):
        return out  # where a one-piece request becomes clear is unknown
    for _ in range(rng.choice([0, 1, 1, 2])):
        if len(text_words) < 3:
            break
        n = rng.randrange(1, len(text_words))
        k = next(i for i, (a, end, _) in enumerate(spans) if a < n <= end)
        prev = spans[k - 1][2] if k else "nothing"
        label = spans[k][2] if n == spans[k][1] else prev if prev == spans[k][2] else "wait"
        out.append(ex(family + "/prefix", " ".join(text_words[:n]), label, heard=heard, cands=cands, done=rng.random() < 0.25, **state))
    return out


# ------------------------------------------------ families: each returns a few examples, given a fresh session

def namesakes(rng, s: Session, words: str) -> list[dict]:
    """Files named like a slide or deck, so a request for the slides is told from one for a file."""
    folder = pick(rng, s.files)["folder"]
    w = " ".join(x.capitalize() if i == 0 else x for i, x in enumerate(words.split()))
    return [{"kind": "file", "name": f"{w}{pick(rng, ['', ' notes', ' draft', ' v2', ' export', ' data', ' summary', ' 2026'])}.{pick(rng, ['pdf', 'xlsx', 'docx', 'pptx'])}",
             "folder": folder} for _ in range(rng.choice([1, 1, 2]))]


NOT_OPEN = [v for v in OPENERS if "open" not in v and "load" not in v]


def fam_slide_title(rng, s: Session):
    sl = pick(rng, s.slides)
    if sl["page"] == 1:
        return []
    screen, behind = s.screen(pick(rng, ["slide", "slide", "desktop", "front"]))
    if screen and key(screen) == key(sl):
        return []
    near = near_slides(s, sl)
    if screen and screen["kind"] == "slide" and rng.random() < 0.4:  # the deck on screen has it, another deck has one like it
        same = [x for x in s.slides if x["deck"] == screen["deck"] and x["page"] > 1 and x != screen]
        others = [x for x in s.slides if x["deck"] != screen["deck"]]
        if same and others:
            sl = pick(rng, same)
            near = [{**pick(rng, others), "title": sl["title"]}] + near_slides(s, sl)
    t = sl["title"].lower() if rng.random() < 0.6 else sl["title"]
    if rng.random() < 0.25 and sl["text"]:
        b = pick(rng, sl["text"].split("; "))
        obj = pick(rng, CONTENT_OBJ).format(b=b.lower() if rng.random() < 0.6 else b)
    else:
        obj = pick(rng, SLIDE_OBJ).format(t=t)
    if rng.random() < 0.3:
        return request(rng, s, "slide over file", obj, "show {n}", sl, near + namesakes(rng, s, sl["title"]), screen, behind,
                       opener=pick(rng, NOT_OPEN))
    return request(rng, s, "slide title", obj, "show {n}", sl, near, screen, behind)


def fam_intents_slide(rng, s: Session):
    text = pick(rng, [x for x in INTENTS["slide"] if not REFS[x].startswith(("slides", "slide context", "slide after", "deck:", "slide number"))])
    ref = REFS[text]
    t = ref.split(":", 1)[1].strip()
    d = pick(rng, s.decks)
    i = rng.randrange(1, len(d["slides"]))
    target = {**slide(d, i), "title": t}
    s.slides = [target if (x["deck"], x["page"]) == (target["deck"], target["page"]) else x for x in s.slides]
    screen, behind = s.screen(pick(rng, ["slide", "desktop", "front"]))
    if screen and screen["kind"] == "slide" and screen["page"] == target["page"] and screen["deck"] == target["deck"]:
        screen = target
        return utter(rng, "already on screen", [(noise(rng, text), "nothing")], context(rng, s), candidates(rng, s, [target], near_slides(s, target)), screen=screen)
    c = candidates(rng, s, [target], near_slides(s, target))
    return utter(rng, "intents slide", [(noise(rng, text), f"show {index(c, target)}")], context(rng, s), c, screen=screen, behind=behind)


def fam_slide_number(rng, s: Session):
    d = pick(rng, s.decks)
    n = len(d["slides"])
    if n < 3:
        return []
    k = rng.randint(1, n)
    cur = rng.randrange(n)
    screen = slide(d, cur)
    behind = None
    if rng.random() < 0.2:
        behind, screen = screen, pick(rng, s.sites + s.files)
    form = pick(rng, NUMBER_REQ)
    if k == n and rng.random() < 0.3:
        phrase = pick(rng, ["the last slide", "go to the last slide", "last slide please", "jump to the end of the deck", "the final slide"])
    elif k == 1 and rng.random() < 0.3:
        phrase = pick(rng, ["the first slide", "back to the first slide", "go to the title slide", "first slide please"])
    else:
        phrase = form.format(n=spoken_number(rng, k), o=ORDS[k - 1])
    if behind is None and k == cur + 1:
        answer = "nothing"
    else:
        answer = f"slide {k}"
    c = candidates(rng, s, [], [x for x in s.slides if x["deck"] == screen.get("deck")][:3])
    return utter(rng, "slide number", [(noise(rng, pick(rng, HEDGES)), "nothing"), (noise(rng, phrase) + pick(rng, TAILS), answer)],
                 context(rng, s), c, screen=screen, behind=behind)


def fam_deck(rng, s: Session):
    d = pick(rng, s.decks)
    cover = slide(d, 0)
    screen, behind = s.screen(pick(rng, ["desktop", "desktop", "slide", "front"]))
    if screen and screen.get("deck") == cover["deck"]:
        return []
    near = [slide(x, 0) for x in s.decks if x is not d] + [slide(d, i) for i in range(1, min(3, len(d["slides"])))]
    if rng.random() < 0.35:
        near += namesakes(rng, s, deck_spoken(rng, d))
    if rng.random() < 0.35:  # the deck named in a preamble, the slides asked for generically
        intro = pick(rng, INTRO).format(t=deck_spoken(rng, d))
        obj = pick(rng, SLIDES_WORDS)
        c = candidates(rng, s, [cover], near)
        tail = pick(rng, [" right now", " now", "", " for you", " first"])
        segs = [(noise(rng, intro), "nothing"), (noise(rng, pick(rng, ["I'll pull up", "let me pull up", "let me put up", "can you pull up",
                                                                        "I'll put up", "let's get", "let me share"])), "wait"),
                (noise(rng, obj + tail), f"show {index(c, cover)}")]
        return utter(rng, "deck in preamble", segs, context(rng, s, 0), c, screen=screen, behind=behind)
    obj = pick(rng, DECK_OBJ).format(d=deck_spoken(rng, d))
    return request(rng, s, "deck", obj, "show {n}", cover, near, screen, behind)


def fam_intents_deck(rng, s: Session):
    text = pick(rng, [x for x in INTENTS["slide"] if REFS[x].startswith("deck:")])
    name = REFS[text].split(":", 1)[1].strip()
    d = {**pick(rng, s.decks), "name": name}
    s.decks = [d if x["name"] == d["name"] else x for x in s.decks] + [d]
    s.slides += [slide(d, i) for i in range(len(d["slides"]))]
    cover = slide(d, 0)
    screen, behind = s.screen(pick(rng, ["desktop", "slide", "front"]))
    if screen and screen.get("deck") == cover["deck"]:
        return []
    c = candidates(rng, s, [cover], [slide(d, 1)])
    return utter(rng, "intents deck", [(noise(rng, text), f"show {index(c, cover)}")], context(rng, s), c, screen=screen, behind=behind)


def fam_slides_generic(rng, s: Session):
    kind = pick(rng, ["front", "front", "front", "desktop", "slide"])
    screen, behind = s.screen(kind)
    if kind == "front" and behind is None:
        behind = pick(rng, s.slides)
    text = pick(rng, INTENTS["return"] + [x for x in INTENTS["slide"] if REFS[x] == "slides"] +
                [pick(rng, ["slides please", "okay, slides", "the slides again", "presentation please",
                            "let's go back to the presentation", "can you put the slides back", "slides", "back to my slides"])])
    c = candidates(rng, s, [], [x for x in s.slides if behind and x["deck"] == behind["deck"]][:3])
    answer = "nothing" if kind == "slide" else "slides"
    return utter(rng, "slides" if answer == "slides" else "already on screen", [(noise(rng, text), answer)], context(rng, s), c,
                 screen=screen, behind=behind, **extras(rng, s, screen))


def fam_next_prev(rng, s: Session):
    label = pick(rng, ["next", "previous"])
    text = pick(rng, INTENTS[label] + (["next please", "okay next", "the next one", "and the next slide", "move on",
                                         "keep going", "next slide please", "can we go to the next one"] if label == "next" else
                                        ["the one before", "previous one", "back a slide",
                                         "can you go back one slide", "the slide before", "one back", "go back a slide please"]))
    kind = pick(rng, ["slide"] * 6 + ["desktop", "front"])
    screen, behind = s.screen(kind)
    if kind == "slide":
        d = next(x for x in s.decks if deck_name(x) == screen["deck"])
        if label == "next" and screen["page"] == screen["pages"] or label == "previous" and screen["page"] == 1:
            answer = "nothing"
        else:
            answer = label
    elif kind == "front" and behind and "slide" in text.lower():
        answer = "previous" if label == "previous" else "next"
    else:
        answer = "nothing" if kind == "desktop" else None
        if answer is None:
            return []
    c = candidates(rng, s, [], [])
    return utter(rng, "next previous" if answer != "nothing" else "next previous impossible", [(noise(rng, text), answer)], context(rng, s), c,
                 screen=screen, behind=behind, **extras(rng, s, screen))


def fam_site(rng, s: Session):
    target = pick(rng, s.sites if rng.random() < 0.65 else s.apps)
    screen, behind = s.screen(pick(rng, ["desktop", "slide", "slide", "front"]))
    if screen and key(screen) == key(target):
        return []
    name = heard_name(rng, target["name"])
    obj = pick(rng, SITE_OBJ if target["kind"] == "site" else APP_OBJ).format(n=name)
    absent = rng.random() < 0.12
    if absent:
        c = candidates(rng, s, [], [], avoid=[target])
        return request(rng, s, "open by name", obj, f"open {spoken(target['name'])}", None, [], screen, behind, cands=c)
    return request(rng, s, "site" if target["kind"] == "site" else "app", obj, "show {n}", target, near_sites(s, target), screen, behind)


def fam_intents_open(rng, s: Session):
    text = pick(rng, INTENTS["open"])
    ref = REFS[text]
    form, _, arg = ref.partition(":")
    arg = arg.strip()
    screen, behind = s.screen(pick(rng, ["desktop", "slide", "slide", "front"]))
    if form == "site":
        name, _, url = arg.partition("|")
        target = site_item(name.strip(), url.strip())
    elif form == "app":
        target = app_item(arg)
    elif form == "file":
        folder = pick(rng, s.files)["folder"]
        target = {"kind": "file", "name": arg.split("/")[-1], "folder": folder}
    elif form == "content":
        target = {**pick(rng, s.slides), "title": arg} if rng.random() < 0.5 else {"kind": "file", "name": f"{arg}.xlsx", "folder": pick(rng, s.files)["folder"]}
    elif form == "address":
        host = arg.lower()
        known = next((x for x in s.sites if x["url"] == host.removeprefix("www.")), None)
        c = candidates(rng, s, [known] if known else [], [])
        answer = f"show {index(c, known)}" if known else f"open {host}"
        return utter(rng, "address", [(noise(rng, text, 0.5), answer)], context(rng, s), c, screen=screen, behind=behind)
    elif form == "unknown":
        return utter(rng, "unsure", [(noise(rng, text), "unsure")], context(rng, s), candidates(rng, s, [], []), screen=screen, behind=behind)
    else:
        return []
    if screen and key(screen) == key(target):
        return []
    if rng.random() < 0.1:  # what was asked for is not among the candidates
        c = candidates(rng, s, [], [], avoid=[target])
        answer = f"open {spoken(target['name'])}" if form in ("site", "app") else "unsure"
        return utter(rng, "absent", [(noise(rng, text), answer)], context(rng, s), c, screen=screen, behind=behind)
    near = near_sites(s, target) if form in ("site", "app") else near_files(s, target) if target["kind"] == "file" else []
    c = candidates(rng, s, [target], near)
    return utter(rng, f"intents {form}", [(noise(rng, text), f"show {index(c, target)}")], context(rng, s), c, screen=screen, behind=behind)


def fam_address(rng, s: Session):
    known = rng.random() < 0.5
    site = pick(rng, s.sites)
    host = site["url"] if known else pick(rng, ["lauzhackers.org", "mybakery.ch", "openclimate.eu", "bloomtech.io", "the-garden-club.org",
                                               "zurich-runners.ch", "pixelforge.dev", "notes4school.com", "acmewidgets.co.uk", "data-hub.ai",
                                               "studio-nova.fr", "greenmiles.org", "civic-tools.gov", "kitchenlab.co", "parkrun.ch",
                                               "fieldnotes.app", "tinylabs.xyz", "museum-bern.ch"]) + pick(rng, ["", "", "", "/pricing", "/blog", "/about"])
    if not known and rng.random() < 0.3:
        host = "www." + host
    spoken = spoken_address(rng, host)
    screen, behind = s.screen(pick(rng, ["desktop", "slide", "front"]))
    if known:
        c = candidates(rng, s, [site], near_sites(s, site))
        answer = f"show {index(c, site)}" if screen is None or key(screen) != key(site) else "nothing"
    else:
        c = candidates(rng, s, [], [])
        answer = f"open {host}"
    v = pick(rng, ["go to", "can you go to", "open", "let's go to", "pull up", "can you open", "head to", "take us to", "type in", "visit"])
    return utter(rng, "address", [(noise(rng, pick(rng, HEDGES), 0.6), "nothing"), (noise(rng, v, 0.6), "wait"), (spoken + pick(rng, TAILS), answer)], context(rng, s), c,
                 screen=screen, behind=behind)


def matches(desc: str, kind: str | None, files: list[dict]) -> list[dict]:
    w = [x for x in norm(desc) if x not in {"the", "a", "our", "my", "that", "with", "one", "file", "of", "for", "and", "in"}]
    out = []
    for f in files:
        fw = set(norm(stem(f)))
        if all(x in fw for x in w) and (kind is None or kind in KINDS.get(ext(f), [])):
            out.append(f)
    return out


def fam_file(rng, s: Session):
    f = pick(rng, s.files)
    near = near_files(s, f)
    screen, behind = s.screen(pick(rng, ["desktop", "slide", "slide", "front"]))
    if screen and key(screen) == key(f):
        return []
    desc = file_spoken(rng, f)
    k = kind_word(rng, f) if rng.random() < 0.5 else None
    form = pick(rng, FILE_OBJ)
    if k is None:
        form = form.replace("the {k} with ", "").replace(" {k}", "")
    obj = form.format(s=desc, k=k or "")
    twin = {**pick(rng, s.slides), "title": stem(f)} if rng.random() < 0.25 else None
    c = candidates(rng, s, [f] + ([twin] if twin else []), near)
    fits = matches(desc, k, [x for x in c if x["kind"] == "file"])
    if key(f) not in {key(x) for x in fits}:
        return []
    if twin and k is None:
        return request(rng, s, "slide over file", obj, f"show {index(c, twin)}", twin, near, screen, behind, cands=c, opener=pick(rng, NOT_OPEN))
    if len(fits) == 1:
        return request(rng, s, "file", obj, f"show {index(c, f)}", f, near, screen, behind, cands=c)
    if len(fits) <= 3:
        answer = "ask " + " ".join(str(index(c, x)) for x in sorted(fits, key=lambda x: index(c, x)))
        return request(rng, s, "ask", obj, answer, f, near, screen, behind, cands=c)
    return []


def fam_ask_siblings(rng, s: Session):
    """Two or three versions of one file: a request naming only what they share is asked about, one naming a difference is shown."""
    base = pick(rng, ["Budget", "Contract", "Report", "Proposal", "Floor plan", "Invoice", "Lease", "Pitch deck", "Grant application",
                      "Timetable", "Menu", "Itinerary", "Press release", "Quote", "Survey results", "Seating plan", "Price list"])
    who = pick(rng, ["", "", f" {pick(rng, ['Meyer', 'Novak', 'Rossi', 'Keller', 'Laurent', 'Haddad', 'Berg', 'Ito'])}"])
    tags = rng.sample(["March", "April", "revised", "final", "draft", "v2", "v3", "signed", "old", "2025", "2026", "short", "long", "summary"], 3)
    e = pick(rng, ["pdf", "docx", "xlsx", "pdf"])
    folder = pick(rng, s.files)["folder"]
    sibs = [{"kind": "file", "name": f"{base}{who} — {t}.{e}" if rng.random() < 0.5 else f"{base}{who} {t}.{e}", "folder": folder}
            for t in tags[:rng.choice([2, 2, 3])]]
    s.files += sibs
    screen, behind = s.screen(pick(rng, ["desktop", "slide", "slide", "front"]))
    c = candidates(rng, s, sibs, [])
    spoken = (base + who).lower() if rng.random() < 0.7 else base + who
    if rng.random() < 0.5:
        obj = pick(rng, ["the {b}", "our {b}", "the {b} file", "the {b} for them", "the {k} with the {b}"]).format(b=spoken, k=pick(rng, KINDS[e]))
        answer = "ask " + " ".join(str(index(c, x)) for x in sorted(sibs, key=lambda x: index(c, x)))
        return request(rng, s, "ask", obj, answer, None, [], screen, behind, cands=c)
    i = rng.randrange(len(sibs))
    t = tags[i]
    obj = pick(rng, ["the {t} {b}", "the {b}, the {t} one", "the {t} version of the {b}", "the {b} {t}"]).format(t=t.lower(), b=spoken)
    return request(rng, s, "file", obj, f"show {index(c, sibs[i])}", None, [], screen, behind, cands=c)


def fam_unsure(rng, s: Session):
    thing = pick(rng, UNKNOWN)
    screen, behind = s.screen(pick(rng, ["desktop", "slide", "front"]))
    c = candidates(rng, s, [], [])
    return request(rng, s, "unsure", thing, "unsure", None, [], screen, behind, cands=c)


def fam_absent_slide(rng, s: Session):
    """A slide asked for by a title no slide has: unsure, not the nearest slide."""
    t = pick(rng, ["the churn breakdown", "the hiring plan", "the org chart", "the customer quotes", "the security section", "the latency benchmarks",
                   "the survey results", "the pricing tiers", "the competitor map", "the funnel", "the cohort chart", "the risks",
                   "the methodology", "the launch plan", "the KPIs", "the photosynthesis diagram", "the sales pipeline", "the roadmap for Q3"])
    if any(x for x in s.slides if set(norm(t)) - {"the", "for"} <= set(norm(x["title"]))):
        return []
    screen, behind = s.screen(pick(rng, ["slide", "slide", "desktop"]))
    c = candidates(rng, s, [], [x for x in s.slides if screen and x["deck"] == screen.get("deck")][:4])
    return request(rng, s, "unsure", pick(rng, ["{t} slide", "{t}", "the slide with {t}"]).format(t=t), "unsure", None, [], screen, behind, cands=c)


def fam_search(rng, s: Session):
    if rng.random() < 0.5:
        text = pick(rng, [x for x in INTENTS["search"] if REFS[x] != "search: context"])
        q = REFS[text].split(":", 1)[1].strip()
        segs = [(noise(rng, text, 0.3), f"search {q}")]
    else:
        q = pick(rng, QUERIES)
        site = pick(rng, ["", "", "", " on youtube", " on wikipedia", " on google maps", " on reddit"])
        segs = [(noise(rng, pick(rng, HEDGES), 0.5), "nothing"), (pick(rng, SEARCHES).format(q=q + site) + pick(rng, ["", "", "?", " please", " for me"]),
                                                                   f"search {q + site}")]
    screen, behind = s.screen(pick(rng, ["desktop", "slide", "front"]))
    return utter(rng, "search", segs, context(rng, s), candidates(rng, s, [], []), screen=screen, behind=behind)


def fam_click(rng, s: Session):
    site = pick(rng, s.sites)
    behind = pick(rng, s.slides) if rng.random() < 0.6 else None
    if rng.random() < 0.5:
        text = pick(rng, INTENTS["click"])
        w = REFS[text].split(":", 1)[1].strip()
    else:
        w = pick(rng, LINKS)
        text = pick(rng, CLICKS).format(w=w)
    on_site = rng.random() < 0.8
    screen = site if on_site else pick(rng, s.slides)
    return utter(rng, "click" if on_site else "click impossible", [(noise(rng, text, 0.3), f"click {w}" if on_site else "unsure")],
                 context(rng, s), candidates(rng, s, [], []), screen=screen, behind=behind if on_site else None)


def can(screen: dict | None, control: str) -> bool:
    if not screen or screen["kind"] not in ("site", "file"):
        return False
    video = screen["kind"] == "site" and screen["name"] in VIDEO_SITES or screen["kind"] == "file" and ext(screen) in VIDEO_FILES
    if control in ("play the video", "pause the video"):
        return video
    if control == "full screen":
        return True
    return not (screen["kind"] == "file" and ext(screen) in VIDEO_FILES) or "zoom" in control


def fam_control(rng, s: Session):
    control = pick(rng, CONTROLS)
    text = pick(rng, INTENTS[f"control {control}"])
    kind = pick(rng, ["site", "site", "file", "file", "slide", "desktop", "app"])
    screen, behind = s.screen(kind)
    if kind == "site" and control in ("play the video", "pause the video") and rng.random() < 0.7:
        screen = site_item("YouTube", "youtube.com") if rng.random() < 0.6 else next((x for x in s.sites if x["name"] in VIDEO_SITES), screen)
    if kind == "file" and control in ("play the video", "pause the video", "full screen") and rng.random() < 0.7:
        screen = {"kind": "file", "name": pick(rng, ["Launch recording.mp4", "Interview — part 2.mov", "Drone footage.mp4", "Demo day.mp4"]),
                  "folder": pick(rng, s.files)["folder"]}
    answer = f"control {control}" if can(screen, control) else "nothing"
    return utter(rng, "control" if answer != "nothing" else "control impossible", [(noise(rng, text), answer)], context(rng, s),
                 candidates(rng, s, [], []), screen=screen, behind=behind, **extras(rng, s, screen))


def fam_bare_control(rng, s: Session):
    screen, behind = s.screen(pick(rng, ["slide", "slide", "desktop", "app"]))
    return utter(rng, "control impossible", [(noise(rng, pick(rng, BARE_CONTROL)), "nothing")], context(rng, s), candidates(rng, s, [], []),
                 screen=screen, behind=behind)


def fam_close(rng, s: Session):
    text = pick(rng, INTENTS["close"] + ["close that", "okay you can close that", "close this", "get rid of that"])
    kind = pick(rng, ["front", "front", "front", "slide", "desktop"])
    screen, behind = s.screen(kind)
    answer = "nothing" if kind == "desktop" else "close"
    return utter(rng, "close" if answer == "close" else "close impossible", [(noise(rng, text), answer)], context(rng, s),
                 candidates(rng, s, [], []), screen=screen, behind=behind, **extras(rng, s, screen))


def fam_undo(rng, s: Session):
    shown = pick(rng, s.files + s.sites + s.slides)
    if shown["kind"] == "slide":
        screen, behind = shown, None
    else:
        screen, behind = shown, pick(rng, s.slides) if rng.random() < 0.7 else None
    near = near_files(s, shown) if shown["kind"] == "file" else near_slides(s, shown) if shown["kind"] == "slide" else near_sites(s, shown)
    c = candidates(rng, s, [shown], near)
    if near and rng.random() < 0.3 and shown["kind"] == "file":  # a correction names the right one
        other = pick(rng, [x for x in near if key(x) in {key(y) for y in c}] or [None])
        if other is None:
            return []
        obj = file_spoken(rng, other)
        text = pick(rng, ["no, the {o}", "no no, I meant the {o}", "not that one, the {o}", "wrong one, the {o} please", "no, open the {o} instead"]).format(o=obj)
        fits = matches(obj, None, [x for x in c if x["kind"] == "file"])
        if [key(x) for x in fits] != [key(other)]:
            return []
        return utter(rng, "correction", [(noise(rng, text, 0.5), f"show {index(c, other)}")], context(rng, s), c, screen=screen, behind=behind, shown=shown)
    if rng.random() < 0.15:  # a rejection with nothing just shown
        return utter(rng, "reject nothing shown", [(noise(rng, pick(rng, REJECT)), "nothing")], context(rng, s), c, screen=screen, behind=behind)
    return utter(rng, "undo", [(noise(rng, pick(rng, REJECT)), "undo")], context(rng, s), c, screen=screen, behind=behind, shown=shown)


def offer(rng, s: Session, m: int) -> list[dict]:
    if rng.random() < 0.6:
        f = pick(rng, s.files)
        pool = [f] + near_files(s, f)
        pool += [x for x in s.files if x not in pool]
        return pool[:m]
    return rng.sample(s.files + s.slides + s.sites, m)


def fam_pick(rng, s: Session):
    m = pick(rng, [2, 2, 3])
    offered = offer(rng, s, m)
    screen, behind = s.screen(pick(rng, ["slide", "desktop", "front"]))
    c = candidates(rng, s, [], [], pinned=offered)
    mode = pick(rng, ["position", "position", "intents", "name", "yes", "no", "kind"])
    if mode == "position":
        i = rng.randrange(m)
        text = pick(rng, ["the {o} one", "number {n}", "the {o}", "option {n}", "{o} one please", "the {o} one, please", "let's go with the {o}",
                          "I think the {o}", "go with number {n}", "uh the {o} one"]).format(o=ORDS[i], n=spoken_number(rng, i + 1))
        if i == m - 1 and rng.random() < 0.3:
            text = pick(rng, ["the last one", "the bottom one", "the last one on the list"])
        answer = f"show {i + 1}"
    elif mode == "intents":
        text = pick(rng, [x for x in INTENTS["pick"] if REFS[x].startswith(("pick:", "pick yes"))])
        ref = REFS[text]
        if ref == "pick yes":
            answer = "show 1"
        else:
            n = int(ref.split(":")[1])
            n = m if n == -1 else n
            if n > m:
                return []
            answer = f"show {n}"
    elif mode == "name":
        i = rng.randrange(m)
        own = [w for w in norm(label(offered[i])) if len(w) > 2 and all(w not in norm(label(x)) for j, x in enumerate(offered) if j != i)]
        if not own:
            return []
        text = pick(rng, ["the {w} one", "the one with {w}", "{w}", "the {w}", "I meant the {w} one", "the one that says {w}"]).format(w=pick(rng, own))
        answer = f"show {i + 1}"
    elif mode == "kind":
        kinds = [ext(x) if x["kind"] == "file" else x["kind"] for x in offered]
        i = rng.randrange(m)
        if kinds.count(kinds[i]) > 1 or kinds[i] not in KINDS:
            return []
        text = pick(rng, ["the {k}", "the {k} please", "the {k} one", "uh the {k}"]).format(k=pick(rng, KINDS[kinds[i]]))
        answer = f"show {i + 1}"
    elif mode == "yes":
        text, answer = pick(rng, YES), "show 1"
    else:
        text, answer = pick(rng, NO), "nothing"
    return utter(rng, "pick" if answer != "nothing" else "pick declined", [(noise(rng, text, 0.5), answer)], context(rng, s, rng.choice([0, 1])), c,
                 screen=screen, behind=behind, offered=list(range(1, m + 1)))


def fam_pick_file(rng, s: Session):
    text = pick(rng, [x for x in INTENTS["pick"] if REFS[x].startswith("pick file:")])
    name = REFS[text].split(":", 1)[1].strip().split("/")[-1]
    target = {"kind": "file", "name": name, "folder": pick(rng, s.files)["folder"]}
    decoys = rng.sample([x for x in s.files if ext(x) != name.rsplit(".", 1)[-1] or rng.random() < 0.3], pick(rng, [1, 2]))
    offered = decoys + [target]
    rng.shuffle(offered)
    c = candidates(rng, s, [], [], pinned=offered)
    screen, behind = s.screen(pick(rng, ["slide", "desktop", "front"]))
    return utter(rng, "pick", [(noise(rng, text, 0.5), f"show {index(c, target)}")], [], c, screen=screen, behind=behind,
                 offered=list(range(1, len(offered) + 1)))


def fam_pick_without_offer(rng, s: Session):
    text = pick(rng, [x for x in INTENTS["pick"] if REFS[x].startswith(("pick:", "pick yes"))] + YES + ["the second one", "number two"])
    screen, behind = s.screen(pick(rng, ["slide", "desktop", "front"]))
    return utter(rng, "pick without offer", [(noise(rng, text, 0.5), "nothing")], context(rng, s), candidates(rng, s, [], []), screen=screen, behind=behind)


def mentioned_item(rng, s: Session):
    kind = pick(rng, ["file", "file", "slide", "deck", "site", "app"])
    if kind == "file":
        f = pick(rng, s.files)
        return f, pick(rng, ["the {s}", "the {s} {k}", "that {k}", "the {k} with the {s}"]).format(s=file_spoken(rng, f), k=kind_word(rng, f)), "file"
    if kind == "slide":
        sl = pick(rng, s.slides)
        return sl, pick(rng, ["the {t} slide", "the slide on {t}", "the {t} chart"]).format(t=sl["title"].lower()), "slide"
    if kind == "deck":
        d = pick(rng, s.decks)
        return slide(d, 0), pick(rng, ["the {d} deck", "the {d} slides", "the {d} presentation"]).format(d=deck_spoken(rng, d)), "deck"
    c = pick(rng, s.sites if kind == "site" else s.apps)
    return c, heard_name(rng, c["name"], 0.3), kind


def fam_mention(rng, s: Session):
    item, phrase, kind = mentioned_item(rng, s)
    bank = TOOL_MENTIONS if kind in ("site", "app") else MENTIONS
    text = pick(rng, bank).format(o=phrase, p=pick(rng, PEOPLE))
    screen, behind = s.screen(pick(rng, ["slide", "slide", "desktop", "front"]))
    c = candidates(rng, s, [item], [])
    return utter(rng, "mention", [(noise(rng, text), "nothing")], context(rng, s), c, screen=screen, behind=behind, **extras(rng, s, screen))


def fam_later(rng, s: Session):
    screen, behind = s.screen(pick(rng, ["slide", "slide", "desktop", "front"]))
    r = rng.random()
    if r < 0.35:
        text = pick(rng, INTENTS["later"])
        c = candidates(rng, s, [], [])
        return utter(rng, "later", [(noise(rng, text), "nothing")], context(rng, s), c, screen=screen, behind=behind)
    item, phrase, _ = mentioned_item(rng, s)
    c = candidates(rng, s, [item], [])
    if r < 0.65:
        return utter(rng, "later", [(noise(rng, pick(rng, PUT_OFF).format(o=phrase)), "nothing")], context(rng, s), c, screen=screen, behind=behind)
    if screen and key(screen) == key(item):
        return []
    v = pick(rng, OPENERS)
    back = pick(rng, CANCELS) if rng.random() < 0.4 else cancel(rng)
    segs = [(noise(rng, pick(rng, HEDGES)), "nothing"), (noise(rng, v), "wait"), (phrase, f"show {index(c, item)}"), (back, "nothing")]
    return utter(rng, "cancelled", segs, context(rng, s), c, screen=screen, behind=behind)


def fam_wait(rng, s: Session):
    screen, behind = s.screen(pick(rng, ["slide", "slide", "desktop", "front"]))
    if rng.random() < 0.5:
        segs = [(noise(rng, pick(rng, INTENTS["unfinished"])), "wait")]
    else:
        segs = [(noise(rng, pick(rng, HEDGES)), "nothing"),
                (noise(rng, pick(rng, OPENERS) + pick(rng, ["", " the", " the, um", " our", " the uh", ",", " um", " the... the", " uh,", " that"])), "wait")]
    return utter(rng, "wait", segs, context(rng, s), candidates(rng, s, [], []), screen=screen, behind=behind)


def fam_split(rng, s: Session):
    """A request cut off before its object, and the object said as the next utterance."""
    first = pick(rng, INTENTS["unfinished"]) if rng.random() < 0.4 else pick(rng, HEDGES) + pick(rng, OPENERS) + pick(rng, [" the", " the, um", ",", " uh", " our", ""])
    kind = pick(rng, ["slide", "deck", "file", "site", "app", "never"])
    screen, behind = s.screen(pick(rng, ["slide", "desktop", "front"]))
    if kind == "never":  # the request before is dropped; what an earlier action showed stays
        text = pick(rng, ["actually forget it", "hmm, never mind, it doesn't matter", "no, it's fine, let's move on",
                          "oh, never mind, I'll show it later", cancel(rng).lstrip(",. ")])
        if rng.random() < 0.5:
            first = pick(rng, OPENERS) + " " + pick(rng, UNKNOWN) + pick(rng, ["?", "", " please"])
        shown = screen if screen and screen["kind"] != "slide" and rng.random() < 0.6 else None
        return utter(rng, "split", [(noise(rng, text), "nothing")], [noise(rng, first)], candidates(rng, s, [], []), screen=screen, behind=behind,
                     shown=shown)
    if kind == "slide":
        item = pick(rng, [x for x in s.slides if x["page"] > 1] or s.slides)
        obj = pick(rng, SLIDE_OBJ).format(t=item["title"].lower())
    elif kind == "deck":
        d = pick(rng, s.decks)
        item, obj = slide(d, 0), pick(rng, DECK_OBJ).format(d=deck_spoken(rng, d))
    elif kind == "file":
        item = pick(rng, s.files)
        obj = pick(rng, ["the {s}", "the {s} {k}", "{s}"]).format(s=file_spoken(rng, item), k=kind_word(rng, item))
    else:
        item = pick(rng, s.sites if kind == "site" else s.apps)
        obj = heard_name(rng, item["name"]) + pick(rng, ["", ".", " please", " website" if kind == "site" else " app"])
    if screen and key(screen) == key(item):
        return []
    near = near_files(s, item) if kind == "file" else near_slides(s, item) if item["kind"] == "slide" else near_sites(s, item)
    c = candidates(rng, s, [item], near)
    if kind == "file" and len(matches(obj, None, [x for x in c if x["kind"] == "file"])) != 1:
        return []
    return utter(rng, "split", [(noise(rng, obj, 0.7), f"show {index(c, item)}")], [*context(rng, s, rng.choice([0, 1])), noise(rng, first)], c,
                 screen=screen, behind=behind)


def fam_pronoun(rng, s: Session):
    """A thing described in the utterance before, asked for by a pronoun now."""
    kind = pick(rng, ["file", "file", "slide", "site"])
    screen, behind = s.screen(pick(rng, ["slide", "desktop", "front"]))
    if kind == "file":
        item = pick(rng, s.files)
        desc = pick(rng, DESCRIBE_FILE).format(o=pick(rng, ["the {s}", "the {s} {k}", "a {k} with the {s}"]).format(s=file_spoken(rng, item), k=kind_word(rng, item)),
                                              p=pick(rng, PEOPLE), m=pick(rng, MONTHS))
    elif kind == "slide":
        d = pick(rng, s.decks)
        i = rng.randrange(1, len(d["slides"]))
        item = slide(d, i)
        desc = pick(rng, DESCRIBE_SLIDE + ["{talk}"]).format(o=item["title"].lower(), talk=pick(rng, d["slides"][i]["talk"]))
    else:
        item = pick(rng, s.sites)
        desc = pick(rng, DESCRIBE_SITE).format(o=heard_name(rng, item["name"], 0.3), p=pick(rng, PEOPLE))
    if screen and key(screen) == key(item):
        return []
    c = candidates(rng, s, [item], [])
    if rng.random() < 0.2:
        return utter(rng, "described, not asked", [(noise(rng, pick(rng, ["right", "okay", "so yeah", "anyway", "and that's why it matters", "um"])), "nothing")],
                     [noise(rng, desc)], c, screen=screen, behind=behind)
    return utter(rng, "pronoun", [(noise(rng, pick(rng, PRONOUN_REQ)) + pick(rng, TAILS), f"show {index(c, item)}")], [noise(rng, desc)], c,
                 screen=screen, behind=behind)


def fam_intents_context(rng, s: Session):
    text = pick(rng, [x for x in INTENTS["slide"] + INTENTS["open"] if REFS[x] in ("slide context", "context")])
    d = pick(rng, s.decks)
    i = rng.randrange(1, len(d["slides"]))
    item = slide(d, i)
    if REFS[text] == "context" and rng.random() < 0.6:
        item = pick(rng, s.sites + s.files)
        desc = (pick(rng, DESCRIBE_SITE) if item["kind"] == "site" else pick(rng, DESCRIBE_FILE)).format(
            o=heard_name(rng, item["name"], 0.3) if item["kind"] == "site" else f"the {file_spoken(rng, item)}", p=pick(rng, PEOPLE), m=pick(rng, MONTHS))
    else:
        desc = pick(rng, d["slides"][i]["talk"])
    screen = slide(d, 0 if i > 1 else min(len(d["slides"]) - 1, i + 1)) if rng.random() < 0.7 else None
    if screen and key(screen) == key(item):
        return []
    c = candidates(rng, s, [item], [])
    return utter(rng, "pronoun", [(noise(rng, text), f"show {index(c, item)}")], [noise(rng, desc)], c, screen=screen)


def fam_already(rng, s: Session):
    kind = pick(rng, ["slide", "front"])
    screen, behind = s.screen(kind)
    if screen["kind"] == "slide":
        obj = pick(rng, SLIDE_OBJ).format(t=screen["title"].lower())
    elif screen["kind"] == "file":
        obj = pick(rng, ["the {s}", "the {s} {k}"]).format(s=file_spoken(rng, screen), k=kind_word(rng, screen))
    else:
        obj = heard_name(rng, screen["name"], 0.2)
    c = candidates(rng, s, [screen], [])
    return request(rng, s, "already on screen", obj, "nothing", None, [], screen, behind, cands=c)


def fam_talk_follow(rng, s: Session):
    d = pick(rng, [x for x in s.decks if len(x["slides"]) >= 3])
    n = len(d["slides"])
    cur = rng.randrange(n - 1)
    r = rng.random()
    if r < 0.55:
        j = min(n - 1, cur + pick(rng, [1, 1, 1, 1, 1, 2, 2, 3, 4]))
    elif r < 0.8:
        j = cur
    else:
        j = rng.randrange(0, cur + 1) if cur else cur
    screen, target = slide(d, cur), slide(d, j)
    line = pick(rng, d["slides"][j]["talk"])
    c = candidates(rng, s, [target] + ([screen] if j != cur else []), near_slides(s, target))
    answer = "next" if j == cur + 1 else f"show {index(c, target)}" if j > cur else "nothing"
    family = "talk follows" if j > cur else "talk stays"
    return utter(rng, family, [(noise(rng, line), answer)], context(rng, s, rng.choice([0, 1])), c, screen=screen)


def fam_talk_elsewhere(rng, s: Session):
    """Talk about a slide while no slide is on air, or about another deck: nothing."""
    sl = pick(rng, s.slides)
    d = next(x for x in s.decks if deck_name(x) == sl["deck"])
    line = pick(rng, d["slides"][sl["page"] - 1]["talk"])
    screen, behind = s.screen(pick(rng, ["desktop", "front"]))
    c = candidates(rng, s, [sl], near_slides(s, sl))
    return utter(rng, "talk stays", [(noise(rng, line), "nothing")], context(rng, s), c, screen=screen, behind=behind)


def fam_chatter(rng, s: Session):
    r = rng.random()
    if r < 0.35:
        text, family = pick(rng, INTENTS["aside"] + INTENTS["talk"]), "aside"
    elif r < 0.55:
        text, family = pick(rng, FILLERS), "filler"
    elif r < 0.7:
        text, family = pick(rng, BACKGROUND), "background"
    elif r < 0.82:
        text, family = pick(rng, FOREIGN), "other language"
    else:
        src = pick(rng, INTENTS["aside"] + INTENTS["talk"] + BACKGROUND + [x for d in s.decks for sl in d["slides"] for x in sl["talk"]])
        w = src.split()
        k = rng.randint(1, max(1, min(len(w) - 1, 6)))
        frag = w[:k]
        if frag and len(frag[-1]) > 4 and rng.random() < 0.6:
            frag[-1] = frag[-1][:rng.randint(2, len(frag[-1]) - 2)]
        text, family = " ".join(frag), "fragment"
    screen, behind = s.screen(pick(rng, ["slide", "slide", "desktop", "front"]))
    c = candidates(rng, s, [], [])
    st = extras(rng, s, screen)
    offered = []
    if rng.random() < 0.1:
        offered = offer(rng, s, 2)
        c = candidates(rng, s, [], [], pinned=offered)
    return utter(rng, family, [(noise(rng, text), "nothing")], context(rng, s), c, screen=screen, behind=behind, offered=list(range(1, len(offered) + 1)), **st)


FAMILIES = {fam_slide_title: 5, fam_intents_slide: 3, fam_slide_number: 2.5, fam_deck: 2.5, fam_intents_deck: 0.6, fam_slides_generic: 2.5,
            fam_next_prev: 3, fam_site: 4, fam_intents_open: 4, fam_address: 1.5, fam_file: 4, fam_ask_siblings: 3, fam_unsure: 1.5,
            fam_absent_slide: 1, fam_search: 2, fam_click: 1, fam_control: 3, fam_bare_control: 1.2, fam_close: 1.5, fam_undo: 2,
            fam_pick: 3.5, fam_pick_file: 1, fam_pick_without_offer: 0.6, fam_mention: 4, fam_later: 4, fam_wait: 2.5, fam_split: 3,
            fam_pronoun: 2.5, fam_intents_context: 1, fam_already: 1.5, fam_talk_follow: 9, fam_talk_elsewhere: 1.5, fam_chatter: 7}


def build(seed: int, n: int, split: str) -> list[dict]:
    rng = random.Random(seed)
    w = World(split)
    fams, weights = list(FAMILIES), list(FAMILIES.values())
    out: list[dict] = []
    while len(out) < n:
        f = rng.choices(fams, weights)[0]
        try:
            s = Session(rng, w)
            exs = f(rng, s)
        except (ValueError, IndexError, StopIteration):
            continue
        for e in exs:  # the slide after the one on screen, as the app shows it
            sc = e["state"]["screen"]
            e["state"]["next"] = next((x for x in s.slides if sc and sc["kind"] == "slide" and x["deck"] == sc["deck"] and x["page"] == sc["page"] + 1), None)
        out += exs
    rng.shuffle(out)
    for e in out[:n]:
        assert re.fullmatch(grammar(e["state"]), e["answer"]), (e["family"], e["answer"], grammar(e["state"]))
    return out[:n]


def edges(rng: random.Random) -> list[dict]:
    """States that exercise each rule of the prompt builder: truncation, whitespace, missing parts, every screen kind."""
    s = Session(rng, World("dev"))
    sl, f, site, app = s.slides[0], s.files[0], s.sites[0], s.apps[0]
    long = " ".join(pick(rng, INTENTS["talk"]) for _ in range(12))
    base = {"screen": None, "behind": None, "offered": [], "shown": None, "heard": [], "now": "next slide please", "done": True, "candidates": [sl, f, site, app]}
    return [{**base, **d} for d in (
        {}, {"candidates": []}, {"candidates": [sl]}, {"done": False}, {"now": long}, {"now": "  okay\n\tso   the\r\nslides  "},
        {"heard": [long, long, "the third one"]}, {"heard": ["first", "second", "third"]}, {"heard": ["  spaced \n out  "]},
        {"screen": sl}, {"screen": f, "behind": sl}, {"screen": site, "behind": sl}, {"screen": app}, {"screen": site},
        {"screen": sl, "shown": sl}, {"screen": f, "behind": sl, "shown": f}, {"offered": [1, 2], "screen": sl},
        {"offered": [1, 2, 3], "candidates": [f, s.files[1], s.files[2], sl, site, app]},
        {"candidates": [{**sl, "text": long}]}, {"candidates": [{**sl, "text": ""}, {**f, "folder": ""}]},
        {"candidates": [{**sl, "title": "  Q3 —\n results  ", "deck": "Board_Update  2026"}]},
        {"candidates": [sl, f, site, app, *s.slides[1:5]]}, {"now": "", "done": False},
    )]


def main() -> None:
    a = argparse.ArgumentParser()
    a.add_argument("what", nargs="?", default="data")
    a.add_argument("--seed", type=int, default=0)
    a.add_argument("--train", type=int, default=60000)
    a.add_argument("--dev", type=int, default=3000)
    args = a.parse_args()
    if args.what == "golden":
        cases = [e["state"] for e in build(args.seed + 7, 220, "dev")] + edges(random.Random(args.seed))
        with open(HERE / "golden.jsonl", "w") as f:
            for st in cases:
                f.write(json.dumps({"input": st, "prompt": prompt(st), "grammar": grammar(st)}, ensure_ascii=False) + "\n")
        print(len(cases), "golden cases")
        return
    out = HERE / "data"
    out.mkdir(exist_ok=True)
    for split, n, seed in (("train", args.train, args.seed), ("dev", args.dev, args.seed + 1)):
        rows = build(seed, n, split)
        with open(out / f"{split}.jsonl", "w") as f:
            for e in rows:
                f.write(json.dumps(e, ensure_ascii=False) + "\n")
        c = Counter(e["family"].split("/")[0] for e in rows)
        print(split, len(rows), dict(sorted(c.items(), key=lambda kv: -kv[1])))


if __name__ == "__main__":
    main()

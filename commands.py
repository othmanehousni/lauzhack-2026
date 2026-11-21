"""Requests said to the screen, read with no model in well under a millisecond: "open YouTube", "go back to the problem",
"close these slides", "switch to Notes", "search robot grippers on YouTube", "show me the warehouse photo".

Commands.read() is called with every new transcript of the utterance being spoken, so an action fires as soon as its
words are heard. Anything it cannot read is left to the router.

  uv run commands.py   self-check on sentences from real talks
"""
import re
import time
import urllib.parse
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Action:
    kind: str  # slide | url | app | file | close | ask
    target: str  # "doc p3" | URL | app name | file name words | slides/tab | the request, for the vision model


SITES = {
    "youtube": "https://www.youtube.com", "github": "https://github.com", "google": "https://www.google.com",
    "gmail": "https://mail.google.com", "google maps": "https://www.google.com/maps", "google drive": "https://drive.google.com",
    "google docs": "https://docs.google.com", "google sheets": "https://sheets.google.com", "linkedin": "https://www.linkedin.com",
    "twitter": "https://x.com", "facebook": "https://www.facebook.com", "instagram": "https://www.instagram.com",
    "reddit": "https://www.reddit.com", "wikipedia": "https://en.wikipedia.org", "amazon": "https://www.amazon.com",
    "netflix": "https://www.netflix.com", "spotify": "https://open.spotify.com", "notion": "https://www.notion.so",
    "figma": "https://www.figma.com", "slack": "https://app.slack.com", "discord": "https://discord.com/app",
    "chatgpt": "https://chatgpt.com", "claude": "https://claude.ai", "hacker news": "https://news.ycombinator.com",
    "product hunt": "https://www.producthunt.com", "stack overflow": "https://stackoverflow.com", "devpost": "https://devpost.com",
    "hugging face": "https://huggingface.co", "arxiv": "https://arxiv.org", "tiktok": "https://www.tiktok.com",
    "twitch": "https://www.twitch.tv", "medium": "https://medium.com", "apple": "https://www.apple.com", "vercel": "https://vercel.com",
    "lauzhack": "https://lauzhack.com", "epfl": "https://www.epfl.ch", "openai": "https://openai.com", "anthropic": "https://www.anthropic.com",
}
SEARCHES = {
    "youtube": "https://www.youtube.com/results?search_query={}", "google": "https://www.google.com/search?q={}",
    "github": "https://github.com/search?q={}", "wikipedia": "https://en.wikipedia.org/w/index.php?search={}",
    "amazon": "https://www.amazon.com/s?k={}", "maps": "https://www.google.com/maps/search/{}",
    "google maps": "https://www.google.com/maps/search/{}", "images": "https://www.google.com/search?tbm=isch&q={}",
}
SPOKEN = {"you tube": "youtube", "git hub": "github", "chat gpt": "chatgpt", "linked in": "linkedin", "x.com": "twitter",
          "hugging face": "hugging face", "huggingface": "hugging face", "stackoverflow": "stack overflow"}

_ = r"\s+"
VERB = re.compile(r"\b(?:open(?:\s+up)?|go\s+(?:on|onto|to|back\s+to|over\s+to)|head\s+(?:back\s+)?(?:to|over\s+to)|pull\s+up|"
                  r"bring\s+up|put\s+up|switch\s+(?:back\s+|over\s+)?to|jump\s+(?:back\s+)?to|take\s+(?:me|us)\s+to|navigate\s+to|"
                  r"launch|move\s+on\s+to|show\s+(?:me|us|you|you\s+guys|everyone|them)|let'?s\s+(?:see|look\s+at|check\s+out|"
                  r"watch|open|go\s+to|have\s+a\s+look\s+at|switch\s+to|talk\s+about|(?:start|begin|continue)\s+with)|back\s+to|"
                  r"moving\s+on\s+to|now\s+on\s+to)\b", re.I)
CLOSE = re.compile(r"\b(?:close|hide|exit|quit|get\s+rid\s+of|stop\s+(?:showing|sharing))\s+(?:the\s+|these\s+|this\s+|those\s+|that\s+|our\s+)?"
                   r"(?P<what>slides?|deck|presentation|tab|window|page|website|site|video|it|that|this)\b", re.I)
SEARCH = re.compile(r"\b(?:search|look\s+up|google)\s+(?:(?P<site1>youtube|google|github|wikipedia|amazon|maps|images)\s+)?(?:for\s+)?"
                    r"(?P<q>.+?)(?:\s+(?:on|in)\s+(?P<site2>youtube|google|github|wikipedia|amazon|maps|google\s+maps|images))?\s*(?:[.?!]|$)", re.I)
# The verb follows a promise ("I'll show you later") or a report ("I wanted to show you"), not a request.
NOT_NOW = re.compile(r"\b(?:i'll|we'll|i\s+will|we\s+will|i'm\s+going\s+to|we're\s+going\s+to|gonna|wanted\s+to|meant\s+to|"
                     r"forgot\s+to|didn't|did\s+not|won't|never)\s+(?:\w+\s+){0,2}$", re.I)
LATER = re.compile(r"\b(?:later|tomorrow|next\s+(?:week|time)|after\s+(?:the|this)\s+(?:call|meeting|talk|presentation)|at\s+the\s+end|"
                   r"in\s+a\s+(?:minute|bit|second|moment))\b", re.I)
FILLER = {"the", "a", "an", "our", "my", "your", "their", "this", "that", "these", "those", "it", "um", "uh", "er", "please", "now",
          "first", "again", "quickly", "quick", "real", "just", "so", "and", "then", "up", "on", "to", "of", "for", "with", "at",
          "website", "site", "page", "web", "app", "application", "slide", "slides", "tab", "window", "here", "there", "one",
          "can", "you", "we", "see", "guys", "everyone", "maybe", "okay", "ok", "let's", "lets", "actually", "back", "over", "into",
          "is", "are", "be", "will", "some", "bit", "little", "more", "all", "about", "out", "in", "how", "what", "where"}
PRONOUNS = {"it", "that", "this", "there", "them", "those", "these"}
FILE_WORDS = {"file", "files", "photo", "photos", "picture", "pictures", "image", "images", "video", "videos", "pdf", "document",
              "doc", "spreadsheet", "sheet", "screenshot", "folder", "recording", "clip", "chart", "diagram", "drawing", "movie"}
WEB_WORDS = {"website", "site", "web", "page", "link", "online", "internet", "homepage", "url"}


def words(text: str) -> list[str]:
    text = text.lower()
    for said, name in SPOKEN.items():
        text = text.replace(said, name)
    return re.findall(r"[a-z0-9][a-z0-9'.-]*[a-z0-9]|[a-z0-9]", text)


@dataclass
class Catalog:
    """Names the presenter can ask for: slides and decks (id -> words), apps on the shared screen, known websites."""
    slides: dict[str, set[str]] = field(default_factory=dict)  # "deck p3" -> the words of its title, all to be said
    decks: dict[str, set[str]] = field(default_factory=dict)  # deck -> words of its file name no other deck has, any one said
    apps: dict[str, str] = field(default_factory=dict)  # lowercased name -> app name

    @classmethod
    def build(cls, slides: list[tuple[str, int, str]], apps: list[str]) -> "Catalog":
        """slides: (deck, page, title). A deck name needs all its distinctive words said; a title, all of its own."""
        cat = cls(apps={a.lower(): a for a in apps})
        common = set.intersection(*[set(words(d.replace("_", " "))) for d in {d for d, _, _ in slides}]) if slides else set()
        names = {d: {w for w in words(d.replace("_", " ")) if w not in common and not w.isdigit()} for d in {d for d, _, _ in slides}}
        for deck, ws in names.items():  # a word no other deck's name has is enough: "the roadmap", "the financials"
            others = set().union(*[o for d, o in names.items() if d != deck])
            cat.decks[deck] = ws - others or ws
        for deck, page, title in slides:
            key = {w for w in words(title) if w not in FILLER}
            if key:
                cat.slides[f"{deck} p{page}"] = key
        return cat


def hits(said: set[str], names: dict[str, set[str]]) -> list[str]:
    """Names whose every word was said, most specific first."""
    return sorted((n for n, ws in names.items() if ws and ws <= said), key=lambda n: -len(names[n]))


class Commands:
    """Reads requests from the utterance being spoken. Keeps the previous utterance for "it" and a dangling "open up..."."""

    def __init__(self, catalog: Catalog):
        self.catalog = catalog
        self.previous = ""
        self.pending_until = 0.0  # a verb with no target ("let's open up, um") waits this long for one
        self.onair_deck: str | None = None

    def read(self, said: str, final: bool) -> list[Action]:
        actions: list[Action] = []
        if LATER.search(said):
            return actions
        for m in CLOSE.finditer(said):
            if not NOT_NOW.search(said[:m.start()]):
                what = m["what"].lower()
                actions.append(Action("close", "slides" if what.startswith(("slide", "deck", "present")) else "front"))
        for m in SEARCH.finditer(said):
            if not NOT_NOW.search(said[:m.start()]):
                site = (m["site1"] or m["site2"] or "google").lower()
                actions.append(Action("url", SEARCHES.get(site, SEARCHES["google"]).format(urllib.parse.quote_plus(m["q"].strip()))))
        if not any(a.kind == "url" for a in actions):
            verbs = [m for m in VERB.finditer(said) if not NOT_NOW.search(said[:m.start()])]
            for i, m in enumerate(verbs):
                rest = said[m.end():verbs[i + 1].start() if i + 1 < len(verbs) else len(said)]
                rest = re.split(r"[.?!;]|\band\s+(?:then\s+)?(?=\w+\s)", rest)[0]
                action = self.resolve(rest, said)
                if action:
                    actions.append(action)
                elif final or i + 1 < len(verbs):
                    self.pending_until = time.monotonic() + 8
            if not verbs and time.monotonic() < self.pending_until:
                action = self.resolve(said, said)
                if action:
                    actions.append(action)
                    self.pending_until = 0
        if final:
            self.previous = said
        return actions

    def resolve(self, rest: str, said: str) -> Action | None:
        target = [w for w in words(rest) if w not in FILLER]
        if not target and set(words(rest)) & {"slides", "slide", "deck", "presentation"}:
            return Action("slide", "")  # "back to the slides": the last slide shown
        if not target and not set(words(rest)) & PRONOUNS:
            return None  # "let's open up, um": the target comes in the next words
        if not target:  # "go on it", "open that": the website or app named just before, else a slide named before
            context = said + " " + self.previous
            return self.named(" ".join(words(context))) or self.slide(set(words(context)))
        action = self.slide(set(target)) or self.named(" ".join(words(rest)))
        if action:
            return action
        said_words = set(words(rest))
        if said_words & FILE_WORDS:
            return Action("file", " ".join(w for w in target if w not in FILE_WORDS) or " ".join(target))
        if said_words & WEB_WORDS:
            return Action("ask", said)
        return None

    def slide(self, said: set[str]) -> Action | None:
        cat, onair = self.catalog, self.onair_deck
        decks = sorted((d for d, ws in cat.decks.items() if ws & said), key=lambda d: -len(cat.decks[d] & said))
        slides = hits(said, cat.slides)
        if onair:  # the deck on air wins ties: "go back to the problem" means this deck's problem slide
            slides.sort(key=lambda s: not s.startswith(onair + " "))
        if slides and (not decks or slides[0].startswith(decks[0] + " ")):
            return Action("slide", slides[0])
        return Action("slide", f"{decks[0]} p1") if decks else None

    def named(self, joined: str) -> Action | None:
        """An app, a known website or a spoken domain ("acme dot com") in these words."""
        for name in sorted(self.catalog.apps, key=len, reverse=True):
            if re.search(rf"\b{re.escape(name)}\b", joined):
                return Action("app", self.catalog.apps[name])
        for name in sorted(SITES, key=len, reverse=True):
            if re.search(rf"\b{re.escape(name)}\b", joined):
                return Action("url", SITES[name])
        domain = re.search(r"\b([a-z0-9-]+)\s*(?:dot|\.)\s*(com|io|ai|org|net|dev|co|ch|fr|app)\b", joined)
        return Action("url", f"https://{domain[1]}.{domain[2]}") if domain else None


if __name__ == "__main__":
    cat = Catalog.build(
        [("Acme_Company_Overview_2026", 1, "Acme Robotics"), ("Acme_Company_Overview_2026", 2, "The problem"),
         ("Acme_Company_Overview_2026", 3, "Our solution"), ("Acme_Company_Overview_2026", 5, "Market size"),
         ("Acme_Product_Roadmap", 1, "Product roadmap"), ("Acme_Product_Roadmap", 2, "Gripper v3"),
         ("Acme_Q3_2026_Financials", 1, "Q3 2026 financials")],
        ["Notes", "Calculator", "Safari", "System Settings", "Maps", "Photos"])
    checks = [
        ("Okay, so wait wait, this just reminded me of another company. It's called YouTube. Maybe you can go on it.", [Action("url", SITES["youtube"])]),
        ("So maybe we can go back to the problem?", [Action("slide", "Acme_Company_Overview_2026 p2")]),
        ("Let's look at the market size now.", [Action("slide", "Acme_Company_Overview_2026 p5")]),
        ("Can you open GitHub and show our open-source gripper SDK?", [Action("url", SITES["github"])]),
        ("Let me quickly search Google for the latest warehouse labour statistics.",
         [Action("url", SEARCHES["google"].format("the+latest+warehouse+labour+statistics"))]),
        ("search robot grippers on YouTube", [Action("url", SEARCHES["youtube"].format("robot+grippers"))]),
        ("I'll send you a link to our website after the call.", []),
        ("Someone asked about videos of the robot on YouTube, I'll share those later.", []),
        ("I'll show you the roadmap in a minute", []),
        ("I wanted to show you the roadmap", []),
        ("let's switch to Notes", [Action("app", "Notes")]),
        ("open system settings please", [Action("app", "System Settings")]),
        ("pull up the roadmap", [Action("slide", "Acme_Product_Roadmap p1")]),
        ("let's move on to the Q3 financials", [Action("slide", "Acme_Q3_2026_Financials p1")]),
        ("show me the warehouse photo", [Action("file", "warehouse")]),
        ("open acme robotics dot com", [Action("slide", "Acme_Company_Overview_2026 p1")]),  # the deck beats a guessed domain
        ("go to lauzhack dot com", [Action("url", "https://lauzhack.com")]),
        ("let me show you how it works", []),
        ("okay, let's go back to the slides", [Action("slide", "")]),
        ("Let's start with the problem.", [Action("slide", "Acme_Company_Overview_2026 p2")]),
        ("so now let's talk about the market size", [Action("slide", "Acme_Company_Overview_2026 p5")]),
        ("we'll talk about the market size later", []),
        ("Mais il voulait pas te les acheter genre.", []),
        ("Actually, let me pull up our website first so you can see the live fleet dashboard.", [Action("ask", "Actually, let me pull up our website first so you can see the live fleet dashboard.")]),
    ]
    bad = 0
    for said, want in checks:
        got = Commands(cat).read(said, True)
        if got != want:
            bad += 1
            print(f"FAIL {said!r}\n   got  {got}\n   want {want}")
    c = Commands(cat)
    c.read("It's called YouTube.", True)
    first = c.read("Maybe we can close these slides and open up um.", True)
    second = c.read("uh the YouTube website.", True)
    if first != [Action("close", "slides")] or second != [Action("url", SITES["youtube"])]:
        bad += 1
        print("FAIL split request:", first, second)
    print(f"{len(checks) + 1 - bad}/{len(checks) + 1} ok")
    t = time.perf_counter()
    for _ in range(1000):
        Commands(cat).read(checks[0][0], True)
    print(f"{(time.perf_counter() - t):.3f} ms per sentence")

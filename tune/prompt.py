"""The decider's prompt and answer grammar, as the Kotlin app builds them (spec: tune/CONTRACT.md, cases: tune/golden.jsonl).

A state is a dict: screen, behind, offered, shown, heard, now, done, candidates (see CONTRACT.md)."""
import re

CONTROLS = ["scroll down", "scroll up", "scroll to the top", "scroll to the bottom", "zoom in", "zoom out", "reset the zoom",
            "go back a page", "go forward a page", "play the video", "pause the video", "full screen", "exit full screen"]
TEXT, NOW, HEARD = 90, 300, 200  # characters kept of a slide's text, of the utterance (its end), of each earlier one (its end)


def clean(s: str) -> str:
    return re.sub(r"[ \t\r\n]+", " ", s).strip(" ")


def head(s: str, n: int) -> str:
    return s if len(s) <= n else s[:n].rsplit(" ", 1)[0] + "…"


def tail(s: str, n: int) -> str:
    return s if len(s) <= n else s[-n:].split(" ", 1)[-1]


def item(c: dict, text: bool = True) -> str:
    match c["kind"]:
        case "slide":
            s = f'slide {c["page"]} of {c["pages"]} "{clean(c["title"])}" in {clean(c["deck"])}'
            return s + (f": {head(clean(c['text']), TEXT)}" if text and clean(c.get("text", "")) else "")
        case "file":
            return f'file "{clean(c["name"])}"' + (f" in {clean(c['folder'])}" if clean(c.get("folder", "")) else "")
        case "site":
            return f"site {clean(c['name'])} ({c['url']})"
        case "app":
            return f"app {clean(c['name'])}"
    raise ValueError(c)


def prompt(p: dict) -> str:
    screen = item(p["screen"], text=False) if p["screen"] else "desktop"
    if p.get("behind"):
        screen += f" over {item(p['behind'], text=False)}"
    lines = [f"screen: {screen}",
             f"offered: {' '.join(map(str, p['offered'])) or 'none'}",
             f"shown: {item(p['shown'], text=False) if p.get('shown') else 'none'}",
             *[f"heard: {tail(clean(h), HEARD)}" for h in p["heard"][-2:]],
             f"now ({'done' if p['done'] else 'speaking'}): {tail(clean(p['now']), NOW)}",
             *[f"{i} {item(c)}" for i, c in enumerate(p["candidates"], 1)]]
    return "\n".join(lines)


def grammar(p: dict) -> str:
    """The answers this state allows, as one regular expression."""
    n = len(p["candidates"])
    on = p["screen"]["kind"] if p["screen"] else None
    deck = on == "slide" or bool(p.get("behind"))
    alts = ["nothing", "wait", "unsure", "slide [1-9][0-9]?", "open [a-z0-9][a-z0-9 .\\-/']{0,40}", "search [a-z0-9][a-z0-9 .\\-']{0,60}"]
    if n:
        alts.append(f"show [1-{n}]")
    if n >= 2:
        alts.append(f"ask [1-{n}] [1-{n}]( [1-{n}])?")
    if deck:
        alts += ["next", "previous"]
    if on != "slide":
        alts.append("slides")
    if on in ("site", "file"):
        alts += ["click [a-z0-9][a-z0-9 .\\-']{0,40}", f"control ({'|'.join(CONTROLS)})"]
    if on:
        alts.append("close")
    if p.get("shown"):
        alts.append("undo")
    return "|".join(alts)

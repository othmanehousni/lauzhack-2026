"""Replay eval scripts through the converted decider as the app runs it: the sentence so far every two words, candidates
from the shipped EmbeddingGemma, the grammar at the end of each sentence, a simulated screen. Scored like brain.evaluate,
with false actions, retrieval recall and the cause of each miss besides. Runs on Modal (tune/modal_tune.py::score)."""
import ast
import json
import re
import time
from pathlib import Path

import numpy as np

from prompt import grammar, prompt, spoken

ROOT = Path(__file__).resolve().parent.parent
SPACE = "▁"  # LiteRT-LM's LLGuidance sees SentencePiece's space mark, not a space: a constraint must spell spaces with it
K = 6  # candidates per decision
PREVIEW = {"show", "slide", "next", "previous", "slides", "control", "close"}  # answers a partial sentence may act on


def literal(path: Path, name: str):
    return next(ast.literal_eval(n.value) for n in ast.parse(path.read_text()).body
                if isinstance(n, ast.Assign) and getattr(n.targets[0], "id", None) == name)


def text_of(m) -> str:
    j = m.to_json() if hasattr(m, "to_json") else m
    c = j.get("content", "")
    return c if isinstance(c, str) else "".join(x.get("text", "") for x in c)


class Decider:
    def __init__(self, path: str, kv: int = 1024):
        import litert_lm
        self.lm = litert_lm
        self.engine = litert_lm.Engine(path, backend=litert_lm.Backend.CPU(), max_num_tokens=kv)

    def __call__(self, p: str, regex: str | None = None) -> tuple[str, float, float]:
        """The answer, the seconds to open the conversation, and the seconds to answer."""
        lm = self.lm
        t0 = time.perf_counter()
        conv = self.engine.create_conversation(
            sampler_config=lm.SamplerConfig(top_k=1, seed=0),
            constrained_decoding_config=lm.ConstrainedDecodingConfig(provider=lm.LiteRtLmConstraintProviderType.LL_GUIDANCE) if regex else None)
        t1 = time.perf_counter()
        try:
            fmt = lm.ResponseFormat.regex(regex.replace(" ", SPACE)) if regex else None
            return text_of(conv.send_message(p, max_output_tokens=16, response_format=fmt)).strip(), t1 - t0, time.perf_counter() - t1
        finally:
            conv.close()

    def tokens(self, p: str) -> int:
        return len(self.engine.tokenize(p))


class Catalog:
    """The fixture decks, files, bookmarks and apps the eval scripts talk about, as decider items, embedded once."""

    def __init__(self, embedder_path: str):
        import litert_lm
        self.embedder = litert_lm.EmbeddingEngine(embedder_path)
        self.items: dict[str, dict] = {}
        self.docs: dict[str, str] = {}
        for doc, slides in literal(ROOT / "fixtures/make_decks.py", "DECKS").items():
            for page, (title, bullets) in enumerate(slides, 1):
                i = f"{doc} p{page}"
                self.items[i] = {"kind": "slide", "deck": doc.replace("_", " "), "page": page, "pages": len(slides), "title": title, "text": "; ".join(bullets)}
                self.docs[i] = f"title: {title} | text: " + (f"{doc.replace('_', ' ')}; " if page == 1 else "") + "; ".join(bullets)
        for path in (ROOT / "fixtures/files.txt").read_text().split("\n"):
            if path:
                parts = path.split("/")[3:]
                self.items[path] = {"kind": "file", "name": parts[-1], "folder": " / ".join(parts[:-1][-2:])}
                self.docs[path] = f"title: {parts[-1]} | text: {self.items[path]['folder']}"
        for name, url in literal(ROOT / "brain.py", "SITES").items():
            self.items[url] = {"kind": "site", "name": name, "url": host(url)}
            self.docs[url] = f"title: {name} | text: {host(url)}"
        for name in literal(ROOT / "brain.py", "APPS"):
            self.items[name] = {"kind": "app", "name": name}
            self.docs[name] = f"title: {name} | text: app"
        self.on_stage = literal(ROOT / "stage.py", "ON_STAGE")
        self.ids = list(self.docs)
        self.vecs = self.embed(list(self.docs.values()))

    def embed(self, texts: list[str]) -> np.ndarray:
        return np.concatenate([np.array([r.embedding for r in self.embedder.compute_embedding_batch(texts[i:i + 32])]) for i in range(0, len(texts), 32)])

    def action(self, i: str) -> tuple[str, str]:
        kind = self.items[i]["kind"]
        return {"slide": "slide", "file": "file", "site": "url", "app": "app"}[kind], i

    def retrieve(self, now: str, heard: list[str], pinned: list[str]) -> list[str]:
        q = self.embed([f"task: search result | query: {now}"] + ([f"task: search result | query: {heard[-1]} {now}"] if heard else []))
        out = list(pinned)
        for j in np.argsort(-(self.vecs @ q.T).max(1)):
            if len(out) >= K:
                break
            if self.ids[j] not in out:
                out.append(self.ids[j])
        return out


def host(url: str) -> str:
    return re.sub(r"^https?://(www\.)?", "", url).rstrip("/")


class View:
    """What the audience sees, as brain.View: the stage (a slide or a picture), a tab or app in front, the last slide shown."""

    def __init__(self, stage=None, front=None, last=None):
        self.stage, self.front, self.last = stage, front, last

    @property
    def onair(self):
        return self.front or self.stage

    def after(self, a: tuple[str, str], on_stage: set) -> "View":
        kind, target = a
        if kind == "slide":
            return View(a, None, a)
        if kind == "file" and Path(target).suffix.lower() in on_stage:
            return View(a, None, self.last)
        if kind == "close":
            return View(self.stage, None, self.last) if self.front else View(None, None, self.last)
        if kind == "control" or a[1].startswith(("unsure", "click ")):
            return self
        return View(self.stage, a, self.last)


class Replay:
    def __init__(self, catalog: Catalog, decider: Decider, constrained: bool = True):
        self.cat, self.decider, self.constrained = catalog, decider, constrained
        self.view, self.before_last = View(), View()
        self.heard: list[str] = []
        self.offered: list[str] = []
        self.offered_age = 0
        self.shown: str | None = None
        self.shown_age = 0

    def item(self, a):
        if a is None:
            return None
        kind, target = a
        if target in self.cat.items:
            return self.cat.items[target]
        if kind == "url":
            return {"kind": "site", "name": host(target), "url": host(target)}
        if kind == "ask":
            return {"kind": "site", "name": "Google", "url": "google.com/search"}
        return {"kind": "app", "name": target}

    def to_action(self, answer: str, cands: list[str], start: View):
        """The answer as a brain-style action on the screen as it was when the sentence began, or None."""
        verb, _, rest = answer.partition(" ")
        last = start.last
        if verb == "show" and rest.isdigit() and 1 <= int(rest) <= len(cands):
            return self.cat.action(cands[int(rest) - 1])
        if verb in ("slide", "next", "previous") and last:
            doc, page = last[1].rsplit(" p", 1)
            n = int(rest) if verb == "slide" else int(page) + (1 if verb == "next" else -1)
            return ("slide", f"{doc} p{n}") if f"{doc} p{n}" in self.cat.items else None
        if verb == "slides":
            return last if last and start.onair != last else None
        if verb == "open":
            words = rest.strip()
            for i, it in self.cat.items.items():
                if it["kind"] in ("site", "app") and (spoken(it["name"]) == words or it["kind"] == "site" and it["url"] == host(words)):
                    return self.cat.action(i)
            return ("url", "https://" + words) if "." in words else ("app", words)
        if verb in ("search", "click"):
            return ("ask", answer)
        if verb == "control":
            return ("control", rest)
        if verb == "close":
            return ("close", "")
        if verb == "undo":
            return ("undo", "")
        return None

    def state(self, start: View, now: str, done: bool) -> tuple[dict, list[str]]:
        cands = self.cat.retrieve(now, self.heard, self.offered)
        on = start.onair
        after = f"{on[1].rsplit(' p', 1)[0]} p{int(on[1].rsplit(' p', 1)[1]) + 1}" if on and on[0] == "slide" else None
        st = {"screen": self.item(on), "behind": self.item(start.last) if on is not None and on[0] != "slide" and start.last else None,
              "next": self.cat.items.get(after),
              "offered": list(range(1, len(self.offered) + 1)), "shown": self.cat.items.get(self.shown) if self.shown else None,
              "heard": self.heard[-2:], "now": now, "done": done, "candidates": [self.cat.items[c] for c in cands]}
        return st, cands

    def apply(self, a, taken: list) -> None:
        taken.append(a)
        if a[0] == "undo":
            self.view = self.before_last
            return
        self.before_last, self.view = self.view, self.view.after(a, self.cat.on_stage)

    def line(self, said: str, want: list) -> dict:
        start, words = self.view, said.split()
        taken, trace, prev, asked, final = [], [], None, [], None
        stats = {"partial_s": [], "create_s": [], "send_s": [], "tokens": []}
        for n in [*range(2, len(words), 2), len(words)]:
            done = n == len(words)
            st, cands = self.state(start, " ".join(words[:n]), done)
            p, g = prompt(st), grammar(st)
            if done:
                answer, c, s = self.decider(p, g if self.constrained else None)
                answer = answer if re.fullmatch(g, answer) else "nothing"
                stats["create_s"].append(c)
                stats["send_s"].append(s)
                stats["tokens"].append(self.decider.tokens(p))
                final = (answer, cands)
            else:
                answer, c, s = self.decider(p)
                stats["partial_s"].append(c + s)
                answer = answer if re.fullmatch(g, answer) else "nothing"
            a = self.to_action(answer, cands, start)
            if a and a[0] == "undo" and not st["shown"]:
                a = None
            if done:
                if a and a not in taken:
                    self.apply(a, taken)
                    trace.append(f"end -> {answer} = {a[0]} {a[1]}")
                if answer.startswith("ask "):
                    asked = [cands[int(x) - 1] for x in answer.split()[1:] if x.isdigit() and int(x) <= len(cands)]
            elif a and answer.split()[0] in PREVIEW and answer == prev and a not in taken:
                self.apply(a, taken)
                trace.append(f'"{" ".join(words[:n])}" -> {answer} = {a[0]} {a[1]}')
            prev = answer if not done else prev
            trace.append(f'{"end" if done else n}: {answer}')
        self.heard.append(said)
        if asked:
            self.offered, self.offered_age = asked, 0
        elif taken:
            self.offered = []
        else:
            self.offered_age += 1
            if self.offered_age >= 3:
                self.offered = []
        if [a for a in taken if a[0] != "control"]:
            on = self.view.onair
            self.shown, self.shown_age = (on[1] if on and on[1] in self.cat.items else None), 0
        else:
            self.shown_age += 1
            if self.shown_age >= 2:
                self.shown = None
        return self.verdict(said, want, start, taken, asked, final, trace, stats)

    def verdict(self, said, want, start, taken, asked, final, trace, stats) -> dict:
        ids = self.cat.items
        shows = lambda v: any(v.onair is None if w is None else w in ids and ids[w]["kind"] == "slide" and v.onair == ("slide", w) for w in want)
        strs = [f"{k} {t}".rstrip() for k, t in taken]
        answer, cands = final
        did = any(w and not (w in ids and ids[w]["kind"] == "slide") and w != "keep" and (w == k or w == s) for w in want for (k, _), s in zip(taken, strs))
        did |= "ask" in want and answer == "unsure"
        did |= "choose" in want and len(asked) > 1
        should = not shows(start) and "keep" not in want
        if taken and not should:
            verdict = "false switch"
        elif shows(self.view) or did or not taken and not should:
            verdict = "ok"
        elif not taken and any(w and (w in asked or w in [f"{k} {t}" for k, t in map(self.cat.action, asked)]) for w in want):
            verdict = "suggested"
        else:
            verdict = "wrong" if taken else "missed"
        targets = [self.target(w) for w in want]
        targets = [t for t in targets if t]
        found = any(t in cands for t in targets)
        navigable = any(t in ids and ids[t]["kind"] == "slide" and start.last and t.rsplit(" p", 1)[0] == start.last[1].rsplit(" p", 1)[0] for t in targets)
        return {"said": said, "want": want, "verdict": verdict, "should": should, "answer": answer, "taken": strs, "asked": asked,
                "false_action": not should and (bool(taken) or answer.startswith(("ask ", "unsure"))), "needs_retrieval": should and bool(targets),
                "retrieved": found, "navigable": navigable, "cause": None if verdict == "ok" else
                "decision" if not (should and targets) or found else "retrieval or deck move" if navigable else "retrieval", "trace": trace, **stats}

    @staticmethod
    def target(w):
        if not w or w in ("keep", "choose", "ask", "close") or w.startswith("control "):
            return None
        return w.split(" ", 1)[1] if w.startswith(("file ", "url ", "app ")) else w


def run_script(catalog: Catalog, decider: Decider, path: Path, constrained: bool = True) -> list[dict]:
    replay = Replay(catalog, decider, constrained)
    return [replay.line(json.loads(l)["say"], json.loads(l)["want"]) for l in path.read_text().splitlines() if l.strip()]


def dev_rows(decider: Decider, rows: list[dict], constrained: bool = True) -> list[tuple[str, str, str]]:
    """(family, expected, answer) for dev examples: constrained when the sentence is done, checked against the grammar if not."""
    out = []
    for e in rows:
        st = e["state"]
        g = grammar(st)
        if st["done"] and constrained:
            a = decider(prompt(st), g)[0]
        else:
            a = decider(prompt(st))[0]
            a = a if re.fullmatch(g, a) else "nothing"
        out.append((e["family"], e["answer"], a))
    return out

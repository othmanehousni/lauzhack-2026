"""Calibrate retrieval on the dev scripts with saved vectors (modal run tune/modal_tune.py::vectors), numpy only:
how often each line's target is among the candidates, by queries used, bonus for the deck on screen, and K.

  python3 tune/retrieval.py [/tmp/eval_vectors.npz] [--holdout]
"""
import json
import sys
from itertools import product
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent


def lines(script: Path):
    """(index, targets, deck on screen) for each line whose target is a catalog item not already on screen."""
    deck, onair = None, None
    for i, l in enumerate(x for x in script.read_text().splitlines() if x.strip()):
        want = json.loads(l)["want"]
        targets = [w.split(" ", 1)[1] if w.startswith(("file ", "url ", "app ")) else w for w in want
                   if w and w not in ("keep", "choose", "ask", "close") and not w.startswith("control ")]
        if targets and onair not in targets:
            yield i, targets, deck
        w = want[0]
        if w and " p" in w and not w.startswith(("file ", "url ", "app ", "control ")):
            deck, onair = w.rsplit(" p", 1)[0], w


def main() -> None:
    path = next((a for a in sys.argv[1:] if not a.startswith("--")), "/tmp/eval_vectors.npz")
    z = np.load(path)
    ids, docs = list(z["ids"]), z["docs"]
    q = dict(zip(z["keys"], z["queries"]))
    decks = np.array([i.rsplit(" p", 1)[0] if " p" in i and not i.startswith("/") and not i.startswith("http") else "" for i in ids])
    owner = [ids.index(o.rsplit("|", 1)[0]) for o in z["alt_owner"]] if "alt_owner" in z else []
    scripts = [p for p in sorted((ROOT / "eval").glob("*.jsonl")) if ("holdout" in p.name) == ("--holdout" in sys.argv)]
    cases = [(p.name, i, t, d) for p in scripts for i, t, d in lines(p)]
    print(f"{len(cases)} lines with a target in {len(scripts)} scripts")
    for alts, kinds, bonus, k in product((False, True) if owner else (False,), (("now", "both"), ("now", "both", "tail")), (0, 0.02, 0.04), (6, 8)):
        hit = 0
        for name, i, targets, deck in cases:
            s = np.max([docs @ q[f"{name}|{i}|{kind}"] for kind in kinds], axis=0)
            if alts:
                a = np.max([z["alt_docs"] @ q[f"{name}|{i}|{kind}"] for kind in kinds], axis=0)
                np.maximum.at(s, owner, a)
            s = s + bonus * (decks == deck)
            top = {ids[j] for j in np.argsort(-s)[:k]}
            hit += any(t in top for t in targets)
        print(f"alt docs {alts!s:5} queries {'+'.join(kinds):14} deck bonus {bonus:.2f} K {k}: recall {hit}/{len(cases)} = {hit / len(cases):.3f}")


if __name__ == "__main__":
    main()

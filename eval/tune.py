"""Replay evals on this Mac without running any model. Every text an eval run can embed is embedded once on Modal, and
brain.embed reads those vectors back, so tuning the classifier, the thresholds or the tracker takes seconds.

  modal run eval/modal_eval.py::vectors                  embed them on a Modal GPU into /tmp/vectors.npz
  uv run python eval/tune.py eval eval/talk.jsonl ...    replay scripts with those vectors [--threshold 0.5] [--step 2]

The d1 fallback needs its server, so replays run without it; modal_eval.py measures it.
"""
import argparse
import contextlib
import io
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

REPO = Path(__file__).parent.parent
sys.path.insert(0, str(REPO))
import brain  # noqa: E402
from intents import INTENTS  # noqa: E402


def texts(library: Path, scripts: list[Path]) -> list[str]:
    """Every text an eval of these scripts can embed: a run with constant vectors records the training sentences, slides,
    files and partial transcripts; each partial joined to the line before stands for a request cut off there."""
    seen: list[str] = []

    def record(batch: list[str]) -> np.ndarray:
        seen.extend(batch)
        return np.full((len(batch), 768), 768 ** -0.5, np.float32)

    brain.embed, brain.CACHE = record, Path(tempfile.mkdtemp())
    intents = brain.Intents(INTENTS)
    for step in (0, 2):
        with contextlib.redirect_stdout(io.StringIO()):
            brain.evaluate(scripts, library, intents, 0.5, step, fallback=False)
    for script in scripts:
        lines = [json.loads(l)["say"] for l in script.read_text().splitlines() if l.strip()]
        for before, said in zip(lines, lines[1:]):
            words = said.split()
            for n in {*range(2, len(words), 2), len(words)}:
                seen += brain.prompts(f"{before} {' '.join(words[:n])}")
    return list(dict.fromkeys(seen))


def replay(args) -> None:
    table = np.load(args.vectors)
    index, vecs = {t: i for i, t in enumerate(table["texts"].tolist())}, table["vecs"]
    brain.embed, brain.CACHE = (lambda batch: vecs[[index[t] for t in batch]]), Path(tempfile.mkdtemp())
    library = Path(tempfile.mkdtemp())
    subprocess.run(["uv", "run", "--group", "dev", "python", str(REPO / "fixtures/make_decks.py"), str(library)], check=True, stdout=subprocess.DEVNULL)
    brain.evaluate(args.scripts, library, brain.Intents(INTENTS), args.threshold, args.step, fallback=False)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("texts", help="print the texts as JSON (runs where the decks are, on Modal)")
    t.add_argument("library", type=Path)
    t.add_argument("scripts", type=Path, nargs="+")
    e = sub.add_parser("eval")
    e.add_argument("scripts", type=Path, nargs="+")
    e.add_argument("--vectors", type=Path, default=Path("/tmp/vectors.npz"))
    e.add_argument("--threshold", type=float, default=0.5)
    e.add_argument("--step", type=int, default=2)
    args = p.parse_args()
    if args.cmd == "texts":
        print(json.dumps(texts(args.library, args.scripts)))
    else:
        replay(args)


if __name__ == "__main__":
    main()

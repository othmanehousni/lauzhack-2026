"""Tables from a scorer run (/tmp/score_<name>.json) and the Python system's eval log, side by side. No model runs here.

  python3 tune/report.py r1 /tmp/baseline.log [--misses] [--holdout]
"""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def nothing_lines(script: Path) -> list[bool]:
    """Which lines want nothing done, on the screen the script intends (each earlier line done right)."""
    onair, front, out = None, None, []
    for l in script.read_text().splitlines():
        if not l.strip():
            continue
        want = json.loads(l)["want"]
        shown = front or onair
        shows = any(shown is None if w is None else shown == w for w in want if w is None or " p" in w and not w.startswith(("file ", "url ", "app ")))
        out.append(shows or "keep" in want)
        w = want[0]
        if w and " p" in w and not w.startswith(("file ", "url ", "app ", "control ")):
            onair, front = w, None
        elif w and w.startswith(("file ", "url ", "app ")):
            if w.startswith("file ") and Path(w).suffix.lower() in {".png", ".jpg", ".jpeg", ".mp4", ".mov"}:
                onair, front = w, None
            else:
                front = w
        elif w == "close":
            if front:
                front = None
            else:
                onair = None
    return out


def baseline(log: Path) -> dict[str, list[tuple[bool, str]]]:
    out, cur = {}, None
    for l in log.read_text().splitlines():
        if re.fullmatch(r"eval/\S+\.jsonl", l.strip()):
            cur = Path(l.strip()).name
            out[cur] = []
        elif cur and re.match(r"(ok |BAD) +\d+ ", l):
            out[cur].append((l.startswith("ok "), l[7:55].strip()))
    return out


def pct(a: int, b: int) -> str:
    return f"{a}/{b} {100 * a / b:.0f}%" if b else "-"


def main() -> None:
    name, log = sys.argv[1], Path(sys.argv[2]) if len(sys.argv) > 2 and not sys.argv[2].startswith("--") else None
    run = json.loads(Path(f"/tmp/score_{name}.json").read_text())
    py = baseline(log) if log else {}
    print(f"{'script':22} {'decider':>12} {'false act':>10} {'recall':>10} {'miss cause':>14} | {'python':>12} {'false act':>10}")
    tot = Counter()
    for script, rows in run["scripts"].items():
        empty = nothing_lines(ROOT / "eval" / script)
        ok = sum(r["verdict"] == "ok" for r in rows)
        fa = sum(e and (bool(r["taken"]) or r["answer"].startswith(("ask ", "unsure"))) for e, r in zip(empty, rows))
        need = [r for r in rows if r["needs_retrieval"]]
        causes = Counter(r["cause"] for r in rows if r["cause"])
        line = f"{script:22} {pct(ok, len(rows)):>12} {pct(fa, sum(empty)):>10} {pct(sum(r['retrieved'] for r in need), len(need)):>10} " \
               f"{'R%d N%d D%d' % (causes['retrieval'], causes['retrieval or deck move'], causes['decision']):>14}"
        tot.update(ok=ok, n=len(rows), fa=fa, empty=sum(empty), need=len(need), found=sum(r["retrieved"] for r in need),
                   R=causes["retrieval"], N=causes["retrieval or deck move"], D=causes["decision"])
        if script in py:
            p = py[script]
            pfa = sum(e and s != "-" for e, (_, s) in zip(empty, p))
            line += f" | {pct(sum(o for o, _ in p), len(p)):>12} {pct(pfa, sum(empty)):>10}"
            tot.update(pok=sum(o for o, _ in p), pn=len(p), pfa=pfa)
        print(line)
    print(f"{'total':22} {pct(tot['ok'], tot['n']):>12} {pct(tot['fa'], tot['empty']):>10} {pct(tot['found'], tot['need']):>10} "
          f"{'R%d N%d D%d' % (tot['R'], tot['N'], tot['D']):>14}" + (f" | {pct(tot['pok'], tot['pn']):>12} {pct(tot['pfa'], tot['empty']):>10}" if py else ""))
    rows = [r for rs in run["scripts"].values() for r in rs]
    q = lambda xs, f: sorted(xs)[min(len(xs) - 1, int(f * len(xs)))] if xs else float("nan")
    part = [x for r in rows for x in r["partial_s"]]
    create = [x for r in rows for x in r["create_s"]]
    send = [x for r in rows for x in r["send_s"]]
    toks = [x for r in rows for x in r["tokens"]]
    print(f"\nlatency: partial (unconstrained) p50 {q(part, .5) * 1e3:.0f} ms p95 {q(part, .95) * 1e3:.0f} ms; end: constrained conversation "
          f"p50 {q(create, .5) * 1e3:.0f} ms, answer p50 {q(send, .5) * 1e3:.0f} ms p95 {q(send, .95) * 1e3:.0f} ms; "
          f"prompt tokens p50 {q(toks, .5)} p95 {q(toks, .95)} max {max(toks)}")
    if run["dev"]:
        fam, ok = Counter(), Counter()
        conf = defaultdict(Counter)
        for f, want, got in run["dev"]:
            f = f.split("/")[0] + ("/prefix" if "/" in f else "")
            fam[f] += 1
            ok[f] += want == got
            if want != got:
                conf[want.split()[0]][got.split()[0]] += 1
        print(f"\ndev exact {pct(sum(ok.values()), sum(fam.values()))}")
        for f in sorted(fam, key=lambda f: ok[f] / fam[f]):
            print(f"  {f:30} {pct(ok[f], fam[f])}")
        print("dev confusions (want -> got):", {k: dict(v) for k, v in conf.items()})
    if "--misses" in sys.argv:
        for script, rows in run["scripts"].items():
            if "holdout" in script and "--holdout" not in sys.argv:
                continue  # read only when reporting: the decider is never tuned on a holdout
            for i, r in enumerate(rows, 1):
                if r["verdict"] != "ok" or r["false_action"]:
                    print(f"\n{script}:{i} {r['verdict']} ({r['cause']}) want {r['want']} | {r['said'][:90]}")
                    print("   ", " | ".join(r["trace"])[:400])


if __name__ == "__main__":
    main()

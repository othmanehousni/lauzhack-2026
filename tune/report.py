"""Tables from a scorer run (/tmp/score_<name>.json) and the Python system's eval log, side by side. No model runs here.

  python3 tune/report.py r1 /tmp/baseline.log [--misses] [--holdout] [--confusion=messy_holdout.jsonl]
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


def false_switches(log: Path) -> dict[str, int]:
    """brain.evaluate's own count: actions on lines where the screen it had already showed what was wanted."""
    return {m[1] + ".jsonl": int(m[2]) for m in re.finditer(r"^(\S+)\s+screen correct .*?false switches (\d+)", log.read_text(), re.M)}


def kind(w) -> str:
    if w is None:
        return "desktop"
    head = w.split(" ", 1)[0]
    return head if head in ("file", "url", "app", "control", "keep", "ask", "choose", "close", "undo") else "slide"


def confusion(rows: list[dict]) -> None:
    """What each line wanted against what the decider did, by kind."""
    t = defaultdict(Counter)
    for r in rows:
        got = kind(r["taken"][-1]) if r["taken"] else "offer" if r["asked"] else "unsure" if r["answer"] == "unsure" else "nothing"
        t[kind(r["want"][0])][got if r["verdict"] != "ok" else "ok"] += 1
    cols = sorted({c for v in t.values() for c in v}, key=lambda c: (c != "ok", c))
    print(f"{'want / got':10}" + "".join(f"{c:>9}" for c in cols))
    for w in sorted(t):
        print(f"{w:10}" + "".join(f"{t[w][c] or '':>9}" for c in cols))


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
    py, pysw = (baseline(log), false_switches(log)) if log else ({}, {})
    print(f"{'script':22} {'decider':>12} {'false act':>10} {'false sw':>9} {'recall':>10} {'miss cause':>14} | {'python':>12} {'false act':>10} {'false sw':>9}")
    tot = Counter()
    for script, rows in run["scripts"].items():
        empty = nothing_lines(ROOT / "eval" / script)
        ok = sum(r["verdict"] == "ok" for r in rows)
        fa = sum(e and (bool(r["taken"]) or r["answer"].startswith(("ask ", "unsure"))) for e, r in zip(empty, rows))
        need = [r for r in rows if r["needs_retrieval"]]
        causes = Counter(r["cause"] for r in rows if r["cause"])
        sw = sum(r["verdict"] == "false switch" for r in rows)
        line = f"{script:22} {pct(ok, len(rows)):>12} {pct(fa, sum(empty)):>10} {sw:>9} {pct(sum(r['retrieved'] for r in need), len(need)):>10} " \
               f"{'R%d N%d D%d' % (causes['retrieval'], causes['retrieval or deck move'], causes['decision']):>14}"
        tot.update(ok=ok, n=len(rows), fa=fa, sw=sw, empty=sum(empty), need=len(need), found=sum(r["retrieved"] for r in need),
                   R=causes["retrieval"], N=causes["retrieval or deck move"], D=causes["decision"])
        if script in py:
            p = py[script]
            pfa = sum(e and s != "-" for e, (_, s) in zip(empty, p))
            line += f" | {pct(sum(o for o, _ in p), len(p)):>12} {pct(pfa, sum(empty)):>10} {pysw.get(script, '-'):>9}"
            tot.update(pok=sum(o for o, _ in p), pn=len(p), pfa=pfa, psw=pysw.get(script, 0))
        print(line)
    print(f"{'total':22} {pct(tot['ok'], tot['n']):>12} {pct(tot['fa'], tot['empty']):>10} {tot['sw']:>9} {pct(tot['found'], tot['need']):>10} "
          f"{'R%d N%d D%d' % (tot['R'], tot['N'], tot['D']):>14}" + (f" | {pct(tot['pok'], tot['pn']):>12} {pct(tot['pfa'], tot['empty']):>10} {tot['psw']:>9}" if py else ""))
    for script in [a.split("=", 1)[1] for a in sys.argv if a.startswith("--confusion=")]:
        print(f"\nconfusion on {script}")
        confusion(run["scripts"][script])
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

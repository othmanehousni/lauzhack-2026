"""Does an untrained Gemma 4 E2B decide better than the fine-tuned 270M deciders (r4, r6b) on the same prompts? All on Modal CPU
through LiteRT-LM 0.19.1, greedy. r4 and r6b as the app runs them (constrained at done); E2B unconstrained, its first line, an
answer outside the grammar wrong (`timing`: a constrained conversation takes E2B 8 s to create, the 270M 0.9 s).

  python3 tune/build.py --train 3000 --dev 3000      tune/data/{train,dev}.jsonl: 200 dev rows to score, 12 train rows as examples
  export MODAL_PROFILE=$(~/Desktop/lemodb-proto/switch.sh) MODAL_IMAGE_BUILDER_VERSION=2025.06
  modal run eval/probe_gemma4.py                      table, rows where E2B and r6b differ, /tmp/probe_gemma4.json
"""
import json
import random
import re
import sys
import time
from pathlib import Path

import modal

ROOT = Path(__file__).resolve().parent.parent
E2B = ("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm")  # Apache 2.0, ungated
E2B_PATH = f"/vol/gemma4/{E2B[1]}"
TRAINED = {"r4": "/vol/r4/decider_q8_ekv1024.litertlm", "r6b": "/vol/r6b/decider_q8_ekv1024.litertlm"}
SHOT_VERBS = ["nothing", "show", "wait", "unsure", "ask", "slide", "open", "search", "slides", "control", "undo", "next"]

INSTRUCTION = """You decide, for a presenter's screen assistant in a meeting, whether the sentence just said asks to change what the audience sees, and how. Each message is one decision:
screen: what is on the shared screen ("desktop" when nothing is; "X over Y" when X covers the slide deck Y)
next: the slide after the one on screen
offered: the candidate numbers the assistant last offered the presenter to pick from, or none
shown: what the assistant's last action put on screen, or none
heard: the sentences before, oldest first
now (done): the sentence just finished; now (speaking): the sentence so far, still being spoken
then up to 8 numbered candidates: slides, files, sites and apps that may be meant.

Answer with one line and nothing else, one of:
nothing: no request to change the screen: talk, remarks to people, questions to the room, talk about a slide or a file, plans for later, speech in another language, a request taken back, a request that cannot apply to the screen, or what is asked for is already on screen
wait: a request has begun but what it asks for is not said yet
unsure: a clear request for something that no candidate is, even if a word of it matches one
show N: put candidate N on screen when the request means it: by name, by what it is about, by a pronoun for something just mentioned, or by picking one of the offered candidates; also when a slide of the deck on screen answers a question just asked
ask N M (or ask N M K): the request fits two or three candidates equally, such as versions of one file: offer them
slide N, next, previous: go to page N, the next or the previous slide of the deck on screen or behind
slides: back to the slides, closing what covers them
open WORDS: open an app or site by its name in lowercase, or an address with a dot, when it is not a candidate
search WORDS: search the web for WORDS
click WORDS: click the link or button with that text on the page on screen
control X: on the page or file on screen, X one of: scroll down, scroll up, scroll to the top, scroll to the bottom, zoom in, zoom out, reset the zoom, go back a page, go forward a page, play the video, pause the video, full screen, exit full screen
close: close what is in front
undo: put back the screen from before the last action, when the presenter says it was wrong
Most sentences are talk: answer nothing unless the presenter asks for a change on screen."""

vol = modal.Volume.from_name("decider")
img = (modal.Image.debian_slim(python_version="3.12")
       .pip_install("litert-lm==0.19.1", "huggingface_hub", "numpy")
       .env({"HF_HOME": "/vol/hf"})
       .add_local_dir(ROOT / "tune", "/root/tune", ignore=["__pycache__", "data", "*.jsonl"]))
app = modal.App("probe-gemma4")


@app.function(image=img, cpu=2, memory=2048, timeout=1800, volumes={"/vol": vol})
def fetch() -> None:
    from huggingface_hub import hf_hub_download
    if not Path(E2B_PATH).exists():
        hf_hub_download(*E2B, local_dir="/vol/gemma4")
        vol.commit()
    print(E2B_PATH, Path(E2B_PATH).stat().st_size)


@app.function(image=img, cpu=8, memory=8192, timeout=3600, volumes={"/vol": vol})
def run(model: str, rows: list[dict], system: str | None = None, shots: list[dict] = (), kv: int = 1024, constrain: bool = True,
        warm: bool = True) -> list[dict]:
    """Each row's raw answer, the answer scored, and the seconds to clone the conversation and to answer. Constrained at done as the app
    (outside the grammar while speaking = nothing), or never (E2B: a constrained conversation costs it 8 s; outside the grammar = wrong).
    Few-shot: the last example is sent to the base, so its preface is prefilled once and every clone starts from it."""
    import litert_lm as lm
    sys.path.insert(0, "/root/tune")
    from prompt import grammar, prompt
    from score import SPACE, text_of
    engine = lm.Engine(model, backend=lm.Backend.CPU(), max_num_tokens=kv)
    msgs = [m for s in (shots[:-1] if warm else shots) for m in ({"role": "user", "content": prompt(s["state"])}, {"role": "assistant", "content": s["answer"]})]
    cfg = lm.ConstrainedDecodingConfig(provider=lm.LiteRtLmConstraintProviderType.LL_GUIDANCE)
    base = {c: engine.create_conversation(sampler_config=lm.SamplerConfig(top_k=1, seed=0), system_message=system, messages=msgs or None,
                                          constrained_decoding_config=cfg if c else None) for c in ((True, False) if constrain else (False,))}
    if shots and warm:
        a = text_of(base[False].send_message(prompt(shots[-1]["state"]), max_output_tokens=16)).strip()
        print(f"last example answered {a!r} (gold {shots[-1]['answer']!r}), preface {base[False].token_count} tokens", flush=True)
    out = []
    for i, e in enumerate(rows):
        st = e["state"]
        p, g = prompt(st), grammar(st)
        c = constrain and st["done"]
        t0 = time.perf_counter()
        conv = base[c].clone()
        t1 = time.perf_counter()
        raw = text_of(conv.send_message(p, max_output_tokens=16, response_format=lm.ResponseFormat.regex(g.replace(" ", SPACE)) if c else None)).strip()
        t2 = time.perf_counter()
        conv.close()
        first = raw.split("\n")[0].strip().strip("`").strip()
        ok = bool(re.fullmatch(g, first))
        out.append({"raw": raw, "answer": first if ok else "nothing" if constrain else "(outside)", "outside": not ok, "clone_s": t1 - t0, "send_s": t2 - t1})
        if i < 3:
            print(repr(raw), out[-1], flush=True)
    return out


@app.function(image=img, cpu=8, memory=8192, timeout=1800, volumes={"/vol": vol})
def timing(p: str, g: str, shots: list[dict]) -> None:
    """What a decision costs E2B: creating or cloning a conversation with and without the constraint, and a prefilled few-shot base."""
    import litert_lm as lm
    sys.path.insert(0, "/root/tune")
    from prompt import prompt
    from score import SPACE, text_of
    engine = lm.Engine(E2B_PATH, backend=lm.Backend.CPU(), max_num_tokens=8192)
    cfg = lm.ConstrainedDecodingConfig(provider=lm.LiteRtLmConstraintProviderType.LL_GUIDANCE)
    sampler = lm.SamplerConfig(top_k=1, seed=0)
    clock = lambda: time.perf_counter()
    for c in (False, True, False, True):
        t0 = clock()
        base = engine.create_conversation(sampler_config=sampler, system_message=INSTRUCTION, constrained_decoding_config=cfg if c else None)
        t1 = clock()
        conv = base.clone()
        t2 = clock()
        a = text_of(conv.send_message(p, max_output_tokens=16, response_format=lm.ResponseFormat.regex(g.replace(" ", SPACE)) if c else None))
        print(f"constrained={c} create {t1 - t0:.2f}s clone {t2 - t1:.2f}s send {clock() - t2:.2f}s {a!r}", flush=True)
        conv.close(), base.close()
    msgs = [m for s in shots[:-1] for m in ({"role": "user", "content": prompt(s["state"])}, {"role": "assistant", "content": s["answer"]})]
    t0 = clock()
    base = engine.create_conversation(sampler_config=sampler, system_message=INSTRUCTION, messages=msgs)
    warm = text_of(base.send_message(prompt(shots[-1]["state"]), max_output_tokens=16))
    t1 = clock()
    print(f"few-shot base warmed in {t1 - t0:.2f}s, {base.token_count} tokens, 12th answered {warm!r} (gold {shots[-1]['answer']!r})", flush=True)
    for _ in range(2):
        t1 = clock()
        conv = base.clone()
        t2 = clock()
        a = text_of(conv.send_message(p, max_output_tokens=16))
        print(f"few-shot clone {t2 - t1:.2f}s send {clock() - t2:.2f}s {a!r}", flush=True)
        conv.close()


@app.local_entrypoint()
def probe_timing():
    sys.path.insert(0, str(ROOT / "tune"))
    from prompt import grammar, prompt
    load = lambda p: [json.loads(l) for l in (ROOT / p).read_text().splitlines() if l.strip()]
    e = load("eval/cases/rehearsal.jsonl")[0]
    timing.remote(prompt(e["state"]), grammar(e["state"]), shots(load("tune/data/train.jsonl")))


@app.local_entrypoint()
def check_warm(n: int = 30, out: str = "/tmp/probe_gemma4.json"):
    """The few-shot form on the first n rows without the prefilled base: does the warm turn change its answers?"""
    d = json.loads(Path(out).read_text())
    rows, ex = d["rows"][:n], d["shots"]
    parts = [run.spawn(E2B_PATH, rows[i::2], INSTRUCTION, ex, 8192, False, False) for i in range(2)]
    cold = [None] * n
    for i, c in enumerate(parts):
        cold[i::2] = c.get()
    warm = d["res"]["e2b+12"][:n]
    right = lambda e, a: a in e.get("accept", [e["answer"]])
    print(f"same answer {sum(w['answer'] == c['answer'] for w, c in zip(warm, cold))}/{n}, right warm {sum(right(e, w['answer']) for e, w in zip(rows, warm))}"
          f" cold {sum(right(e, c['answer']) for e, c in zip(rows, cold))}, send s cold {sum(c['send_s'] for c in cold) / n:.2f}")
    for e, w, c in zip(rows, warm, cold):
        print(e.get("accept"), "warm", w["answer"], "cold", c["answer"])


def shots(train: list[dict]) -> list[dict]:
    """One finished train row per answer verb, short prompts, in a fixed order."""
    sys.path.insert(0, str(ROOT / "tune"))
    from prompt import prompt
    rng = random.Random(1)
    return [rng.choice([e for e in train if e["state"]["done"] and e["answer"].split()[0] == v and len(prompt(e["state"])) < 900])
            for v in SHOT_VERBS]


@app.local_entrypoint()
def main(out: str = "/tmp/probe_gemma4.json"):
    load = lambda p: [json.loads(l) for l in (ROOT / p).read_text().splitlines() if l.strip()]
    rows = ([{**e, "set": "rehearsal"} for e in load("eval/cases/rehearsal.jsonl")] + [{**e, "set": "absent"} for e in load("eval/cases/absent.jsonl")]
            + [{**e, "set": "dev"} for e in random.Random(0).sample(load("tune/data/dev.jsonl"), 200)])
    ex = shots(load("tune/data/train.jsonl"))
    fetch.remote()
    forms = {"r4": (TRAINED["r4"], None, [], 1024, True), "r6b": (TRAINED["r6b"], None, [], 1024, True),
             "e2b": (E2B_PATH, INSTRUCTION, [], 4096, False), "e2b+12": (E2B_PATH, INSTRUCTION, ex, 8192, False)}
    n = {"r4": 3, "r6b": 3, "e2b": 4, "e2b+12": 3}  # containers per form
    calls = {f: [run.spawn(m, rows[i::n[f]], *args) for i in range(n[f])] for f, (m, *args) in forms.items()}
    res = {}
    for f, cs in calls.items():
        parts = [c.get() for c in cs]
        res[f] = [None] * len(rows)
        for i, part in enumerate(parts):
            res[f][i::n[f]] = part
    right = lambda e, a: a in e.get("accept", [e["answer"]])
    print(f"\n{'form':8} {'rehearsal/69':>13} {'absent/15':>10} {'dev/200':>8} {'outside':>8} {'send s':>7} {'clone s':>8}")
    for f, r in res.items():
        c = {k: sum(right(e, x["answer"]) for e, x in zip(rows, r) if e["set"] == k) for k in ("rehearsal", "absent", "dev")}
        print(f"{f:8} {c['rehearsal']:>13} {c['absent']:>10} {c['dev']:>8} {sum(x['outside'] for x in r):>8}"
              f" {sum(x['send_s'] for x in r) / len(r):>7.2f} {sum(x['clone_s'] for x in r) / len(r):>8.2f}")
    diff = [{"set": e["set"], "family": e["family"], "now": e["state"]["now"], "done": e["state"]["done"], "expected": e.get("accept", [e["answer"]]),
             **{f: res[f][i]["answer"] for f in res}, "e2b_raw": res["e2b"][i]["raw"], "e2b+12_raw": res["e2b+12"][i]["raw"]}
            for i, e in enumerate(rows) if len({right(e, res[f][i]["answer"]) for f in ("r6b", "e2b", "e2b+12")}) > 1]
    Path(out).write_text(json.dumps({"rows": rows, "res": res, "shots": ex, "diff": diff}, ensure_ascii=False))
    print(f"\n{len(diff)} rows where r6b and an E2B form differ in being right; wrote {out}")
    for d in diff[:40]:
        print(json.dumps(d, ensure_ascii=False))

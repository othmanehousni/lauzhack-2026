"""Can Gemma 4 E2B listen to a presenter continuously and answer an action straight from the audio, with no recognizer in front?
One three-minute stream of synthesized speech (20 requests, 21 sentences of talk, gaps of silence), fed to one open LiteRT-LM
conversation as successive audio messages of a fixed length, one line asked after each. Model on Modal CPU only.

  export MODAL_PROFILE=$(~/Desktop/lemodb-proto/switch.sh) MODAL_IMAGE_BUILDER_VERSION=2025.06
  modal run eval/probe_gemma4_live.py --n 3          smoke test: three 2 s chunks
  modal run eval/probe_gemma4_live.py                2 s and 4 s chunks in parallel, prints the table, /tmp/probe_gemma4_live.json
"""
import array
import hashlib
import io
import json
import random
import re
import time
import wave
from pathlib import Path

import modal

AUDIO = Path("/Users/admin/Desktop/lauzhack-2026/.stage/heard-audio/Samantha-180")  # sha1(sentence).wav, 16 kHz mono 16-bit
E2B_PATH = "/vol/gemma4/gemma-4-E2B-it.litertlm"
RATE = 16000
KV = 8192

# The script, in order: (sentence, expected action) for a request, (sentence, None) for talk.
SCRIPT = [
    ("Hi everyone, thanks for joining, can you all see my screen?", None),
    ("Warehouses just can't hire enough people to pick orders anymore.", None),
    ("Next slide, please.", "next"),
    ("It learns a new item from just five photos.", None),
    ("This is our fleet dashboard, where you can see each robot handling mixed totes.", None),
    ("Go back one.", "previous"),
    ("We see a forty-eight billion dollar market by 2030.", None),
    ("Jump to slide seven.", "slide 7"),
    ("In terms of traction, we now have forty-two customers and over six hundred robots deployed.", None),
    ("Can you open GitHub?", "open github"),
    ("I'll send you a link to our website after the call.", None),
    ("Close that.", "close"),
    ("Our competitors only move goods around. They don't pick.", None),
    ("Let's, uh, go to slide four.", "slide 4"),
    ("We charge nineteen hundred dollars per robot per month, with no upfront cost.", None),
    ("Our CFO will send the detailed Q3 financials after the call.", None),
    ("So let's start with the Q3 financials, I'll put that deck up.", "open financials"),
    ("Revenue came in at fourteen point two million, which is, uh, twenty-two percent up on last quarter.", None),
    ("And the bit I really like is that ninety-one percent of it is recurring.", None),
    ("Okay, can we move to the next slide?", "next"),
    ("Gross margin went from fifty-four to sixty-one percent, and that's almost entirely the cheaper gripper hardware.", None),
    ("Okay, close that tab.", "close"),
    ("Slide five please.", "slide 5"),
    ("Marc, I think you're on mute.", None),
    ("Before I get into it though, could you bring up YouTube for me?", "open youtube"),
    ("Someone asked about videos of the robot on YouTube, I'll share those later.", None),
    ("Okay, get rid of that window.", "close"),
    ("Previous slide.", "previous"),
    ("I wanted to show you our GitHub, but we're short on time.", None),
    ("Right, let me switch over to the product roadmap deck now.", "open roadmap"),
    ("We'll come back to the scheduler later, it deserves its own discussion.", None),
    ("Okay, on to the next one.", "next"),
    ("I'll show you the roadmap at the end.", None),
    ("Thanks. Jump to eight, the last one.", "slide 8"),
    ("Marc, our CTO, did his PhD at ETH on robotic grasping.", None),
    ("Let's go to the LauzHack website.", "open lauzhack"),
    ("We'll come back to the competitors next week.", None),
    ("Sorry, go back one, Marc wants to see the offer summary again.", "previous"),
    ("And the last slide please", "slide 10"),
    ("Let's go back to slide five for a second, I want to be sure you pick the right deductible.", "slide 5"),
    ("Thanks everyone, that's all from me.", None),
]

SYSTEM = """You listen to a presenter in a meeting and change the shared screen when they ask. Their voice reaches you as it is spoken, in consecutive pieces of {L} seconds of audio; a sentence can run across two pieces.
On screen: the deck "Company Overview", ten slides, at slide 3.
Can be opened: the deck "Q3 Financials", the deck "Product Roadmap", GitHub, YouTube, the LauzHack website.
After each piece answer one line, exactly one of:
nothing
next
previous
slide N (N from 1 to 10)
open financials, open roadmap, open github, open youtube, open lauzhack
close (close what was opened)
Give an action once, in the piece where the presenter finishes asking for it. Talk, remarks to people and plans for later are nothing. Most pieces are nothing."""
ASK = "Your line for this piece:"

image = (modal.Image.debian_slim(python_version="3.12")
         .pip_install("litert-lm==0.19.1", "huggingface_hub", "numpy")
         .env({"HF_HOME": "/vol/hf"}))
vol = modal.Volume.from_name("decider")
app = modal.App("probe-gemma4-live")


# ---------- the stream (runs here, stdlib only) ----------

def samples(path: Path) -> array.array:
    with wave.open(str(path)) as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (RATE, 1, 2), path
        return array.array("h", w.readframes(w.getnframes()))


def voiced(x: array.array, thr: int = 330) -> tuple[int, int]:
    """First and one past the last sample louder than about -40 dBFS."""
    on = [i for i, v in enumerate(x) if abs(v) > thr]
    return (on[0], on[-1] + 1) if on else (0, len(x))


def build(seed: int = 0) -> tuple[bytes, list[dict]]:
    """The stream as WAV bytes, and per sentence where its voice starts and ends in it, in seconds."""
    rng = random.Random(seed)
    out, timeline = array.array("h", [0] * int(1.0 * RATE)), []
    for say, want in SCRIPT:
        x = samples(AUDIO / (hashlib.sha1(say.encode()).hexdigest() + ".wav"))
        a, b = voiced(x)
        timeline.append({"say": say, "want": want, "start": (len(out) + a) / RATE, "end": (len(out) + b) / RATE})
        out.extend(x)
        out.extend([0] * int(rng.uniform(0.8, 2.2) * RATE))  # ponytail: digital silence, no room noise between sentences
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1), w.setsampwidth(2), w.setframerate(RATE)
        w.writeframes(out.tobytes())
    return buf.getvalue(), timeline


# ---------- the model (runs on Modal) ----------

@app.function(image=image, cpu=8, memory=16384, timeout=3600, volumes={"/vol": vol})
def listen(stream: bytes, L: float, n: int = 0, kv: int = KV) -> dict:
    """One conversation, one audio message per L seconds, one line back each. When the conversation nears the KV limit it is
    replaced by a fresh one (the instruction only) and the reset is recorded."""
    import litert_lm as lm
    with wave.open(io.BytesIO(stream)) as w:
        pcm = w.readframes(w.getnframes())
    step = int(L * RATE) * 2
    chunks = [pcm[i:i + step] for i in range(0, len(pcm), step)]
    chunks = chunks[:n] if n else chunks
    t = time.perf_counter()
    engine = lm.Engine(E2B_PATH, backend=lm.Backend.CPU(), audio_backend=lm.Backend.CPU(), max_num_tokens=kv)
    load = time.perf_counter() - t
    fresh = lambda: engine.create_conversation(sampler_config=lm.SamplerConfig(top_k=1, seed=0), system_message=SYSTEM.format(L=f"{L:g}"))
    conv, out, resets = fresh(), [], []
    for i, c in enumerate(chunks):
        path = f"/tmp/chunk{i}.wav"
        with wave.open(path, "wb") as w:
            w.setnchannels(1), w.setsampwidth(2), w.setframerate(RATE)
            w.writeframes(c)
        if conv.token_count > kv - 600:  # room for one more chunk and its answer
            conv.close()
            conv, _ = fresh(), resets.append(i)
        before = conv.token_count
        t = time.perf_counter()
        m = conv.send_message({"role": "user", "content": [{"type": "audio", "path": path}, {"type": "text", "text": ASK}]}, max_output_tokens=8)
        dt = time.perf_counter() - t
        j = m.to_json() if hasattr(m, "to_json") else m
        c = j.get("content", "")
        raw = (c if isinstance(c, str) else "".join(p.get("text", "") for p in c)).strip()
        out.append({"i": i, "raw": raw, "s": dt, "tokens": conv.token_count, "added": conv.token_count - before})
        print(f"{L:g}s #{i} {dt:.2f}s tokens {before}->{conv.token_count} {raw!r}", flush=True)
    return {"L": L, "load_s": load, "chunks": out, "resets": resets}


# ---------- scoring (runs here) ----------

NUM = {w: str(i) for i, w in enumerate("zero one two three four five six seven eight nine ten".split())}
NAMES = [("finan", "financials"), ("q3", "financials"), ("road", "roadmap"), ("git", "github"), ("you", "youtube"), ("lauz", "lauzhack"),
         ("laus", "lauzhack"), ("laws", "lauzhack")]


def norm(raw: str) -> str:
    s = re.sub(r"[`*\"'.:]", "", raw.split("\n")[0].lower()).strip()
    if not s or s.startswith("nothing"):
        return "nothing"
    if s.startswith("next"):
        return "next"
    if s.startswith(("previous", "back", "go back")):
        return "previous"
    if m := re.match(r"slide (\w+)", s):
        return "slide " + NUM.get(m[1], m[1])
    if s.startswith("open"):
        return "open " + next((v for k, v in NAMES if k in s), "?" + s[5:])
    if s.startswith("close"):
        return "close"
    return "other: " + s[:30]


def score(res: dict, timeline: list[dict]) -> dict:
    """Each action is put on a sentence heard in its chunk or the one before: a request it matches if any, else the latest one heard.
    Answer time = the chunk's end, or the previous answer if the model is still busy, plus the compute."""
    L, ready, acts = res["L"], 0.0, []
    for c in res["chunks"]:
        lo, hi = c["i"] * L, (c["i"] + 1) * L
        ready = max(hi, ready) + c["s"]
        a = norm(c["raw"])
        if a == "nothing":
            continue
        near = [k for k, e in enumerate(timeline) if e["start"] < hi and e["end"] > lo - L]
        match = [k for k in near if timeline[k]["want"] == a]
        acts.append({"chunk": c["i"], "act": a, "raw": c["raw"], "at": ready, "to": (match or near or [None])[-1]})
    at = 3  # the slide the model's own answers have reached, from the fixed "slide 3"
    for x in acts:
        x["from"] = at
        at = int(x["act"][6:]) if re.fullmatch(r"slide \d+", x["act"]) else at + {"next": 1, "previous": -1}.get(x["act"], 0)
    req = [k for k, e in enumerate(timeline) if e["want"]]
    first = {}
    for x in acts:
        if x["to"] is not None:
            first.setdefault(x["to"], x)
    right = [k for k in req if k in first and first[k]["act"] == timeline[k]["want"]]
    step = {"next": 1, "previous": -1}
    same = [k for k in req if k in first and k not in right and timeline[k]["want"] in step  # next or previous said as the slide it lands on
            and first[k]["act"] in {f"slide {n + step[timeline[k]['want']]}" for n in (3, first[k]["from"])}]
    cut = [k for k in req if int(timeline[k]["start"] // L) != int((timeline[k]["end"] - 1e-6) // L)]
    s = sorted(r["s"] for r in res["chunks"])
    return {"L": L, "chunks": len(s), "median_s": s[len(s) // 2], "worst_s": s[-1], "rtf": s[len(s) // 2] / L,
            "right": len(right), "same": len(same), "req": len(req), "late": sorted(first[k]["at"] - timeline[k]["end"] for k in right + same),
            "false": sum(1 for k, e in enumerate(timeline) if not e["want"] and k in first), "talk": len(timeline) - len(req),
            "silence": sum(1 for x in acts if x["to"] is None), "extra": len(acts) - len(first),
            "cut": len(cut), "cut_lost": sum(1 for k in cut if k not in right + same), "acts": acts}


def self_check() -> None:
    assert [norm(r) for r in ("Nothing.", "`slide seven`", "open the LauzHack website", "Open Q3 Financials", "previous\nnothing", "hmm")] == \
           ["nothing", "slide 7", "open lauzhack", "open financials", "previous", "other: hmm"]
    tl = [{"say": "a", "want": None, "start": 0.5, "end": 1.5}, {"say": "b", "want": "next", "start": 1.6, "end": 2.4},
          {"say": "c", "want": "close", "start": 5.0, "end": 5.5}]
    res = {"L": 2.0, "chunks": [{"i": 0, "raw": "nothing", "s": 0.5}, {"i": 1, "raw": "next", "s": 0.5}, {"i": 2, "raw": "open github", "s": 3.0}]}
    sc = score(res, tl)
    assert (sc["right"], sc["cut"], sc["cut_lost"], sc["false"], sc["late"]) == (1, 1, 0, 0, [1.6 + 0.5]), sc
    assert sc["acts"][1]["to"] == 2 and sc["right"] == 1  # wrong action on "c", counted as heard but wrong
    res["chunks"][1]["raw"] = "slide 4"  # "next" from slide 3, said as the slide it lands on
    assert (score(res, tl)["right"], score(res, tl)["same"]) == (0, 1)


@app.local_entrypoint()
def main(n: int = 0, chunks: str = "2,4", out: str = "/tmp/probe_gemma4_live.json", rescore: bool = False):
    """--rescore: score the results saved in OUT again, no Modal call."""
    self_check()
    if rescore:
        d = json.loads(Path(out).read_text())
        timeline, res = d["timeline"], d["res"]
    else:
        stream, timeline = build()
        print(f"stream {len(stream) / 2 / RATE:.1f} s, {sum(1 for e in timeline if e['want'])} requests, {sum(1 for e in timeline if not e['want'])} talk")
        res = [c.get() for c in [listen.spawn(stream, float(L), n) for L in chunks.split(",")]]
        Path(out).write_text(json.dumps({"timeline": timeline, "res": res}, indent=1))
    if n:
        return
    print("\n| chunk | chunks | s/chunk median | worst | real-time factor | right of 20 | + said as the slide number | after the sentence's last word (median, worst)"
          " | false of 21 talk | in silence | repeats | cut by an edge | cut and lost | resets |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in res:
        s = score(r, timeline)
        late = f"{s['late'][len(s['late']) // 2]:.1f} s, {s['late'][-1]:.1f} s" if s["late"] else "n/a"
        print(f"| {s['L']:g} s | {s['chunks']} | {s['median_s']:.2f} | {s['worst_s']:.2f} | {s['rtf']:.2f} | {s['right']} | {s['same']} | {late} | {s['false']} |"
              f" {s['silence']} | {s['extra']} | {s['cut']} | {s['cut_lost']} | {len(r['resets'])} |")
    for r in res:
        cs = r["chunks"]
        k = max(len(cs) // 5, 1)
        med = lambda xs: sorted(xs)[len(xs) // 2]
        print(f"\n{r['L']:g} s: engine load {r['load_s']:.1f} s; median s/chunk first {k} {med([c['s'] for c in cs[:k]]):.2f}, last {k} {med([c['s'] for c in cs[-k:]]):.2f};"
              f" tokens per chunk {med([c['added'] for c in cs])}, at the end {cs[-1]['tokens']}, resets at chunks {r['resets']}")
        s = score(r, timeline)
        for k, e in enumerate(timeline):
            got = [x for x in s["acts"] if x["to"] == k]
            if e["want"] or got:
                print(f"  {e['start']:6.1f}-{e['end']:6.1f}  want {str(e['want']):16} got {', '.join(f'{x['act']} (chunk {x['chunk']}, +{x['at'] - e['end']:.1f} s)' for x in got) or '-':40} {e['say']}")
        for x in s["acts"]:
            if x["to"] is None:
                print(f"  in silence: {x['act']} at chunk {x['chunk']} {x['raw']!r}")
    print(f"\nraw results in {out}")

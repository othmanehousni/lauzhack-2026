"""How well does Gemma 4 E2B hear, given audio directly, against Apple's on-device recognizer? Runs the model on Modal only.

  export MODAL_PROFILE=$(~/Desktop/lemodb-proto/switch.sh) MODAL_IMAGE_BUILDER_VERSION=2025.06
  modal run eval/probe_gemma4_audio.py            80 sentences, clean plus three harder variants, prints the table
  modal run eval/probe_gemma4_audio.py --n 2      smoke test on two files
"""
import glob
import hashlib
import json
import random
import re
from pathlib import Path

import modal

ROOT = Path("/Users/admin/Desktop/lauzhack-2026")
AUDIO = {"samantha": ROOT / ".stage/heard-audio/Samantha-180", "daniel": ROOT / ".stage/heard-audio/Daniel-180"}
HEARD = ROOT / ".claude/worktrees/agent-a0aab340ed729f762/eval/heard"
OUT = Path(__file__).parent.parent / ".stage/g4audio.json"
REPO = "litert-community/gemma-4-E2B-it-litert-lm"
PROMPT = "Transcribe this English speech exactly, word for word. Output only the transcript."
VARIANTS = ["clean", "voice", "noise", "pause"]

image = (modal.Image.debian_slim(python_version="3.12")
         .pip_install("litert-lm>=0.19.1", "numpy", "huggingface_hub")
         .env({"HF_HOME": "/vol/hf"}))
vol = modal.Volume.from_name("g4audio", create_if_missing=True)
app = modal.App("g4audio")


# ---------- audio (runs on Modal) ----------

def read_wav(b: bytes):
    import io
    import wave

    import numpy as np
    with wave.open(io.BytesIO(b)) as w:
        sr, ch, sw, n = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        x = np.frombuffer(w.readframes(n), dtype={2: np.int16, 4: np.int32}[sw]).astype(np.float32) / (2 ** (8 * sw - 1))
    x = x.reshape(-1, ch).mean(1)
    if sr != 16000:  # ponytail: linear resample, fine for speech at these rates
        x = np.interp(np.arange(0, len(x), sr / 16000), np.arange(len(x)), x).astype(np.float32)
    return x


def write_wav(x, path: str) -> None:
    import wave

    import numpy as np
    with wave.open(path, "wb") as w:
        w.setnchannels(1), w.setsampwidth(2), w.setframerate(16000)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype(np.int16).tobytes())


def rms(x):
    import numpy as np
    return float(np.sqrt(np.mean(x[np.abs(x) > 1e-3] ** 2)))  # level of the voiced part, not the trailing silence


def variant(x, kind: str, other, seed: int):
    import numpy as np
    if kind == "clean":
        return x
    if kind == "voice":  # the other speaker, 12 dB under, looped or cut to length
        o = np.resize(other, len(x))
        return x + o * (rms(x) / rms(o)) * 10 ** (-12 / 20)
    if kind == "noise":  # pink noise 15 dB under the speech
        rng = np.random.default_rng(seed)
        f = np.fft.rfftfreq(len(x))
        spec = rng.standard_normal(len(f)) + 1j * rng.standard_normal(len(f))
        spec[1:] /= np.sqrt(f[1:])
        spec[0] = 0
        p = np.fft.irfft(spec, len(x)).astype(np.float32)
        return x + p * (rms(x) / float(np.sqrt(np.mean(p ** 2)))) * 10 ** (-15 / 20)
    if kind == "pause":  # one second of silence at the quietest 30 ms in the middle 40-60% of the voiced span
        on = np.flatnonzero(np.abs(x) > 0.01)
        a, b = (on[0], on[-1]) if len(on) > 1 else (0, len(x) - 1)
        lo, hi, win = a + int(0.4 * (b - a)), a + int(0.6 * (b - a)), 480
        e = np.convolve(x[lo:hi] ** 2, np.ones(win), "valid")
        cut = lo + int(np.argmin(e)) + win // 2
        return np.concatenate([x[:cut], np.zeros(16000, np.float32), x[cut:]])
    raise ValueError(kind)


@app.function(image=image, volumes={"/vol": vol}, cpu=8, memory=16384, timeout=3600)
def transcribe(items: list[dict]) -> list[dict]:
    import os
    import time

    import litert_lm
    from huggingface_hub import hf_hub_download, list_repo_files
    files = [f for f in list_repo_files(REPO) if f.endswith(".litertlm")]
    name = next((f for f in files if f == "gemma-4-E2B-it.litertlm"), files[0])
    print("files", files, "using", name)
    path = hf_hub_download(REPO, name, local_dir="/vol/model")
    vol.commit()
    t = time.time()
    engine = litert_lm.Engine(path, backend=litert_lm.Backend.CPU(), audio_backend=litert_lm.Backend.CPU())
    print(f"engine loaded in {time.time() - t:.1f}s")
    out = []
    for i, it in enumerate(items):
        x, other = read_wav(it["wav"]), read_wav(it["other"])
        for kind in VARIANTS:
            wav = f"/tmp/{i}-{kind}.wav"
            write_wav(variant(x, kind, other, i), wav)
            conv = engine.create_conversation(sampler_config=litert_lm.SamplerConfig(top_k=1, seed=0))
            t = time.time()
            m = conv.send_message({"role": "user", "content": [{"type": "audio", "path": wav}, {"type": "text", "text": PROMPT}]},
                                  max_output_tokens=128)  # a runaway repeat otherwise runs to the KV limit
            dt = time.time() - t
            j = m.to_json() if hasattr(m, "to_json") else m
            c = j.get("content", "")
            text = c if isinstance(c, str) else "".join(p.get("text", "") for p in c)
            out.append({"id": it["id"], "kind": kind, "text": text.strip(), "s": dt})
            os.remove(wav)
        print(i, it["id"][:8], out[-4]["text"][:80], f"{out[-4]['s']:.2f}s")
    return out


# ---------- scoring (runs here, stdlib only) ----------

ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()
ORD = {"one": "first", "two": "second", "three": "third", "five": "fifth", "eight": "eighth", "nine": "ninth", "twelve": "twelfth"}


def words_of(n: int) -> str:
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else " " + ONES[n % 10])
    for size, w in ((10 ** 9, "billion"), (10 ** 6, "million"), (1000, "thousand"), (100, "hundred")):
        if n >= size:
            return words_of(n // size) + " " + w + ("" if n % size == 0 else " " + words_of(n % size))


def ordinal(w: str) -> str:
    *head, last = w.split()
    last = ORD.get(last) or (last[:-1] + "ieth" if last.endswith("y") else last + "th")
    return " ".join(head + [last])


def norm(s: str) -> list[str]:
    s = s.lower().replace("%", " percent").replace("&", " and ").replace("’", "'")
    s = re.sub(r"(\d),(\d{3})", r"\1\2", s)
    s = re.sub(r"(\d+)(st|nd|rd|th)\b", lambda m: " " + ordinal(words_of(int(m[1]))) + " ", s)
    s = re.sub(r"(\d+)\.(\d+)", lambda m: f" {words_of(int(m[1]))} point {' '.join(ONES[int(d)] for d in m[2])} ", s)
    s = re.sub(r"\d+", lambda m: " " + words_of(int(m[0])) + " ", s)
    s = s.replace("'", "")
    return re.sub(r"[^a-z ]+", " ", s).split()


def edits(r: list[str], h: list[str]) -> int:
    d = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, hw in enumerate(h, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (rw != hw))
    return d[-1]


def span(r: list[str], h: list[str]) -> tuple[int, list[str]]:
    """Edits against the best-matching run of h: Apple's utterances run across sentence boundaries in the live stream,
    so words of the neighbouring sentences at either end are free. Applied the same way to both recognizers."""
    D = [[(0, j) for j in range(len(h) + 1)]] + [[(i, 0)] + [None] * len(h) for i in range(1, len(r) + 1)]
    for i in range(1, len(r) + 1):
        for j in range(1, len(h) + 1):
            s, d, n = D[i - 1][j - 1], D[i - 1][j], D[i][j - 1]
            D[i][j] = min((s[0] + (r[i - 1] != h[j - 1]), s[1]), (d[0] + 1, d[1]), (n[0] + 1, n[1]))
    j = min(range(len(h) + 1), key=lambda j: D[-1][j][0])
    return D[-1][j][0], h[D[-1][j][1]:j]


NUMWORDS = set(ONES + TENS[2:] + ["hundred", "thousand", "million", "billion"] +[ordinal(w) for w in ONES[1:] + TENS[2:]])


def keys(say: str, lower_seen: set[str]) -> list[list[str]]:
    """Names and numbers in the script: capitalised words not at a sentence start (or that never appear lower case), and digits or number words."""
    out = []
    for m in re.finditer(r"[A-Za-z0-9][\w'.%-]*", say):
        w, before = m[0].rstrip("."), say[:m.start()].rstrip()
        start = not before or before[-1] in ".?!"
        n = norm(w)
        if not n or w in ("I", "I'm", "I'll", "I've", "I'd"):
            continue
        if any(ch.isdigit() for ch in w) or all(t in NUMWORDS for t in n) or (w[0].isupper() and (not start or w.lower() not in lower_seen)):
            out.append(n)
    return out


def score(rows: list[tuple[str, str]], lower_seen: set[str]) -> dict:
    e = n = exact = khit = kn = 0
    for say, hyp in rows:
        r = norm(say)
        c, h = span(r, norm(hyp))
        e, n, exact = e + c, n + len(r), exact + (r == h)
        hs = " " + " ".join(h) + " "
        for k in keys(say, lower_seen):
            kn, khit = kn + 1, khit + (" " + " ".join(k) + " " in hs)
    return {"wer": e / n, "exact": exact / len(rows), "keys": khit / max(kn, 1), "kn": kn}


def self_check() -> None:
    assert norm("We're at 3,400 users, up 12% on the 2nd!") == "were at three thousand four hundred users up twelve percent on the second".split()
    assert edits("a b c".split(), "a x c d".split()) == 2
    assert span("next one".split(), "it plans picks next one".split()) == (0, ["next", "one"])
    assert span("a b c".split(), "x a c y".split()) == (1, ["a", "c"])
    assert [" ".join(k) for k in keys("Hi Marc, open GitHub and slide 7.", {"hi"})] == ["marc", "github", "seven"]


@app.local_entrypoint()
def main(n: int = 80, seed: int = 0):
    self_check()
    rows, seen = {}, set()
    for voice in AUDIO:
        for f in sorted(glob.glob(str(HEARD / f"*.{voice}.jsonl"))):
            for line in open(f):
                j = json.loads(line)
                wav = AUDIO[voice] / (hashlib.sha1(j["say"].encode()).hexdigest() + ".wav")
                if wav.exists() and (voice, j["say"]) not in seen:
                    seen.add((voice, j["say"]))
                    apple = " ".join(u.get("text", "") for u in j["heard"]).strip()
                    rows[f"{voice}:{wav.stem}"] = {"voice": voice, "say": j["say"], "apple": apple, "wav": wav}
    lower_seen = {w for r in rows.values() for w in re.findall(r"[a-z][\w']*", r["say"])}
    rng = random.Random(seed)
    ids = sorted(rows)
    rng.shuffle(ids)
    wer1 = lambda i: span(norm(rows[i]["say"]), norm(rows[i]["apple"]))[0] / max(len(norm(rows[i]["say"])), 1)
    worst = sorted(ids, key=wer1, reverse=True)[:n // 2]
    pick = worst + [i for i in ids if i not in worst][:n - len(worst)]
    print(f"{len(rows)} sentences with audio and Apple text; picked {len(pick)} (worst {len(worst)}, random {len(pick) - len(worst)})")
    items = []
    for k, i in enumerate(pick):
        others = [o for o in pick if rows[o]["voice"] != rows[i]["voice"] and rows[o]["say"] != rows[i]["say"]]
        others = others or [o for o in pick if o != i] or [i]
        o = others[k % len(others)]
        items.append({"id": i, "wav": rows[i]["wav"].read_bytes(), "other": rows[o]["wav"].read_bytes()})
    shards = 4 if n > 8 else 1
    res = [r for part in transcribe.map([items[s::shards] for s in range(shards)]) for r in part]
    got = {(r["id"], r["kind"]): r for r in res}
    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text(json.dumps({"pick": pick, "worst": worst, "rows": {i: {k: v for k, v in rows[i].items() if k != "wav"} for i in pick},
                               "gemma": res}, indent=1))

    def line(label, rs, secs):
        s = score(rs, lower_seen)
        print(f"| {label:<26} | {100 * s['wer']:5.1f}% | {100 * s['exact']:5.1f}% | {100 * s['keys']:5.1f}% ({s['kn']}) | {secs} |")

    for title, subset in (("all 80", pick), ("random 40", pick[len(worst):]), ("Apple's worst 40", worst)):
        print(f"\n{title}\n| | WER | exact | names+numbers | s/file |\n|---|---|---|---|---|")
        line("Apple clean", [(rows[i]["say"], rows[i]["apple"]) for i in subset], "n/a (live stream)")
        for kind, label in zip(VARIANTS, ["Gemma clean", "Gemma + second voice -12 dB", "Gemma + pink noise -15 dB", "Gemma + 1 s pause"]):
            rs = [got[(i, kind)] for i in subset]
            line(label, [(rows[i]["say"], r["text"]) for i, r in zip(subset, rs)], f"{sum(r['s'] for r in rs) / len(rs):.2f}")
    print("\nApple's ten worst, side by side")
    for i in worst[:10]:
        print(f"\nscript: {rows[i]['say']}\napple:  {rows[i]['apple']}\ngemma:  {got[(i, 'clean')]['text']}\n+voice: {got[(i, 'voice')]['text']}")
    print(f"\nraw results in {OUT}")

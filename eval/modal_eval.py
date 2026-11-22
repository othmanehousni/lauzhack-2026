"""Run copilot.py eval on a Modal GPU, so no model loads on this Mac. The decision model runs in llama-server's CUDA
build and the embeddings in Ollama, as on the Mac; the decks are the fixtures. Models are cached on a Volume.

  export MODAL_PROFILE=$(~/Desktop/lemodb-proto/switch.sh) MODAL_IMAGE_BUILDER_VERSION=2025.06
  modal run eval/modal_eval.py                                  every eval script, as compare.sh would
  modal run eval/modal_eval.py --args "--threshold 0.6 eval eval/web.jsonl --step 0"
  modal run eval/modal_eval.py --vectors /tmp/vectors.npz      every text the evals embed, for eval/tune.py
  modal run eval/modal_eval.py --parity .stage/parity          the --router none eval's texts, vectors and decisions, for
                                                               the Kotlin port's ParityTest
"""
from pathlib import Path

import modal

REPO = Path(__file__).parent.parent
EMBED = "embeddinggemma-2:270m"  # brain.EMBED, not imported so this file runs without the copilot's dependencies
cache = modal.Volume.from_name("copilot-eval-cache", create_if_missing=True)
image = (
    modal.Image.from_registry("ghcr.io/ggml-org/llama.cpp:server-cuda", add_python="3.12")
    .entrypoint([])
    .apt_install("curl", "ca-certificates", "zstd")
    .run_commands("curl -fsSL https://ollama.com/install.sh | sh", f"(ollama serve > /dev/null 2>&1 &) && sleep 5 && ollama pull {EMBED}")
    .pip_install("numpy", "pypdf", "websockets", "reportlab")
    .env({"HF_HOME": "/cache/hf", "LLAMA_CACHE": "/cache/llama", "D1_ONLINE": "1"})
    .add_local_dir(REPO, "/repo", ignore=["library", "lipread", ".venv", ".git", ".stage", "**/__pycache__", "uv.lock"])
)
app = modal.App("copilot-eval", image=image)


def up() -> None:
    """Start Ollama and write the fixture decks to /tmp/library."""
    import subprocess
    import time
    import urllib.request

    # The image's working directory is llama.cpp's /app, and ggml loads backends from there: Ollama would take that CUDA
    # build and crash. So Ollama starts elsewhere, and only the copilot sees /app.
    subprocess.Popen("ollama serve > /tmp/ollama.log 2>&1", shell=True, cwd="/tmp")
    for _ in range(60):
        try:
            urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=1)
            break
        except OSError:
            time.sleep(0.5)
    subprocess.run(["python", "fixtures/make_decks.py", "/tmp/library"], cwd="/repo", check=True, stdout=subprocess.DEVNULL)


@app.function(gpu="L4", timeout=1800, volumes={"/cache": cache})
def run(argv: list[str]) -> None:
    import os
    import subprocess

    up()
    env = os.environ | {"PATH": f"/app:{os.environ['PATH']}", "LD_LIBRARY_PATH": "/app"}
    if subprocess.run(["python", "copilot.py", "--library", "/tmp/library", *argv], cwd="/repo", env=env).returncode:
        subprocess.run("tail -n 30 /tmp/d1-server-*.log /tmp/ollama.log", shell=True)
    cache.commit()


@app.function(gpu="L4", timeout=1800, volumes={"/cache": cache})
def vectors_of_evals() -> bytes:
    import subprocess

    up()
    scripts = " ".join(sorted(f"eval/{p.name}" for p in Path("/repo/eval").glob("*.jsonl")))
    subprocess.run(f"python eval/tune.py texts /tmp/library {scripts} > /tmp/texts.json", shell=True, cwd="/repo", check=True)
    subprocess.run(["python", "-c", "import json, numpy as np, brain; t = json.load(open('/tmp/texts.json')); "
                    "v = np.concatenate([brain.embed(t[i:i + 256]) for i in range(0, len(t), 256)]); "
                    "np.savez_compressed('/cache/vectors.npz', texts=np.array(t), vecs=v); print(len(t), 'texts')"], cwd="/repo", check=True)
    cache.commit()
    return Path("/cache/vectors.npz").read_bytes()


@app.function(gpu="L4", timeout=1800, volumes={"/cache": cache})
def parity_of_evals() -> dict[str, bytes]:
    """The eval as `--router none` runs it, two words at a time, recording every text it embeds with the model's own
    vector, the slides it read and each decide() call, so the Kotlin port can replay the same run without a model."""
    import contextlib
    import io
    import json
    import os
    import sys
    import tempfile

    import numpy as np

    up()
    os.chdir("/repo")
    sys.path.insert(0, "/repo")
    import brain
    from intents import INTENTS

    texts, raws, slides, calls, at = [], [], [], [], {"n": -1, "line": 0}
    scripts = sorted(Path("eval").glob("*.jsonl"))

    def embed(batch: list[str]) -> np.ndarray:
        v = np.array(brain.ollama("/api/embed", {"model": brain.EMBED, "input": batch, "keep_alive": "1h"})["embeddings"], np.float32)
        texts.extend(batch)
        raws.append(v)
        return v / np.linalg.norm(v, axis=1, keepdims=True)

    catalog, born, decide, heard = brain.Catalog.__init__, brain.Brain.__init__, brain.Brain.decide, brain.Brain.heard

    def read_catalog(self, *a, **k):
        catalog(self, *a, **k)
        slides[:] = [{"deck": s.doc, "page": s.page, "title": s.title, "text": s.text} for s in self.slides]

    def new_brain(self, *a, **k):
        born(self, *a, **k)
        at["n"], at["line"] = at["n"] + 1, 1

    def record(self, said, end):
        d = decide(self, said, end)
        calls.append({"script": scripts[at["n"]].stem, "line": at["line"], "said": said, "end": end,
                      "do": str(d.do) if d.do else None, "offer": [str(a) for a in d.offer], "why": d.why})
        return d

    def next_line(self, said):
        heard(self, said)
        at["line"] += 1

    brain.embed, brain.CACHE = embed, Path(tempfile.mkdtemp())
    brain.Catalog.__init__, brain.Brain.__init__, brain.Brain.decide, brain.Brain.heard = read_catalog, new_brain, record, next_line
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        brain.evaluate(scripts, Path("/tmp/library"), brain.Intents(INTENTS), 0.5, 2, fallback=False)
    print(out.getvalue())
    vecs = np.concatenate(raws)
    print(len(texts), "texts embedded,", len(set(texts)), "distinct,", vecs.shape[1], "dimensions,", len(calls), "decisions")
    return {"texts.json": json.dumps(texts).encode(), "vectors.f32": vecs.astype("<f4").tobytes(),
            "python.json": json.dumps({"dim": int(vecs.shape[1]), "slides": slides, "calls": calls, "stdout": out.getvalue()}).encode()}


@app.local_entrypoint()
def main(args: str = "eval " + " ".join(sorted(str(p.relative_to(REPO)) for p in (REPO / "eval").glob("*.jsonl"))), vectors: str = "",
         parity: str = ""):
    if parity:
        Path(parity).mkdir(parents=True, exist_ok=True)
        for name, data in parity_of_evals.remote().items():
            (Path(parity) / name).write_bytes(data)
    elif vectors:
        Path(vectors).write_bytes(vectors_of_evals.remote())
    else:
        run.remote(args.split())

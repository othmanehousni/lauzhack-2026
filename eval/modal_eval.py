"""Run copilot.py eval on a Modal GPU, so no model loads on this Mac. The decision model runs in llama-server's CUDA
build and the embeddings in Ollama, as on the Mac; the decks are the fixtures. Models are cached on a Volume.

  export MODAL_PROFILE=$(~/Desktop/lemodb-proto/switch.sh) MODAL_IMAGE_BUILDER_VERSION=2025.06
  modal run eval/modal_eval.py                                  every eval script, as compare.sh would
  modal run eval/modal_eval.py --args "--threshold 0.6 eval eval/web.jsonl --step 0"
"""
from pathlib import Path

import modal

REPO = Path(__file__).parent.parent
cache = modal.Volume.from_name("copilot-eval-cache", create_if_missing=True)
image = (
    modal.Image.from_registry("ghcr.io/ggml-org/llama.cpp:server-cuda", add_python="3.12")
    .entrypoint([])
    .apt_install("curl", "ca-certificates", "zstd")
    .run_commands("curl -fsSL https://ollama.com/install.sh | sh", "(ollama serve > /dev/null 2>&1 &) && sleep 5 && ollama pull embeddinggemma")
    .pip_install("numpy", "pypdf", "websockets", "reportlab")
    .env({"HF_HOME": "/cache/hf", "LLAMA_CACHE": "/cache/llama", "D1_ONLINE": "1"})
    .add_local_dir(REPO, "/repo", ignore=["library", "lipread", ".venv", ".git", ".stage", "**/__pycache__", "uv.lock"])
)
app = modal.App("copilot-eval", image=image)


@app.function(gpu="L4", timeout=1800, volumes={"/cache": cache})
def run(argv: list[str]) -> None:
    import os
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
    env = os.environ | {"PATH": f"/app:{os.environ['PATH']}", "LD_LIBRARY_PATH": "/app"}
    if subprocess.run(["python", "copilot.py", "--library", "/tmp/library", *argv], cwd="/repo", env=env).returncode:
        subprocess.run("tail -n 30 /tmp/d1-server-*.log /tmp/ollama.log", shell=True)
    cache.commit()


@app.local_entrypoint()
def main(args: str = "eval " + " ".join(sorted(str(p.relative_to(REPO)) for p in (REPO / "eval").glob("*.jsonl")))):
    run.remote(args.split())

"""Fine-tune, convert and score the decider on Modal. Nothing here runs a model on the Mac.

  export MODAL_PROFILE=$(~/Desktop/lemodb-proto/switch.sh) MODAL_IMAGE_BUILDER_VERSION=2025.06
  modal run tune/modal_tune.py::derisk     ten fine-tune steps from the base, convert, compare with the checkpoint
"""
import json
import sys
from pathlib import Path

import modal

HERE = Path(__file__).parent
BASE = "unsloth/gemma-3-270m-it"  # ungated mirror of google/gemma-3-270m-it
EMBEDDER = ("litert-community/embeddinggemma-2-text-270m-litert-lm", "embeddinggemma-2-text-270m.litertlm")
PREFILL, KV = [128, 512], 1024  # prefill signatures and KV cache of the converted decider
STOPS = [106, 1]  # <end_of_turn> first: LiteRT-LM's constraint lets only the first stop token end an answer

MAVEN = "https://dl.google.com/android/maven2"
JARS = [f"{MAVEN}/com/google/ai/edge/litertlm/litertlm-jvm/0.19.1/litertlm-jvm-0.19.1.jar",
        "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar",
        "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.3.21/kotlin-stdlib-2.3.21.jar",
        "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-reflect/2.3.21/kotlin-reflect-2.3.21.jar",
        "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.jar"]
vol = modal.Volume.from_name("decider", create_if_missing=True)
TORCH_CPU = ("torch==2.11.0", "https://download.pytorch.org/whl/cpu")
conv = (modal.Image.debian_slim(python_version="3.12")
        .pip_install(TORCH_CPU[0], index_url=TORCH_CPU[1])
        .pip_install("litert-torch==0.10.0", "litert-lm-builder==0.18.1", "jax==0.12.0", "jaxlib==0.12.0", "tensorflow-cpu==2.21.0",
                     "transformers==5.24.0", "sentencepiece", "accelerate")
        .env({"HF_HOME": "/vol/hf", "TF_CPP_MIN_LOG_LEVEL": "2"})
        .add_local_dir(HERE, "/root/tune", ignore=["__pycache__", "data", "*.jsonl"]))
rt = (modal.Image.debian_slim(python_version="3.12")
      .apt_install("curl", "openjdk-17-jdk-headless")
      .pip_install(TORCH_CPU[0], index_url=TORCH_CPU[1])
      .pip_install("litert-lm==0.19.1", "transformers==5.24.0", "sentencepiece", "numpy")
      .run_commands("mkdir -p /jars", *(f"curl -fsSL -o /jars/{u.rsplit('/', 1)[1]} {u}" for u in JARS))
      .run_commands("mkdir -p /opt/jdk && curl -fsSL https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
                    " | tar xz -C /opt/jdk --strip-components=1")
      .env({"HF_HOME": "/vol/hf"})
      .add_local_dir(HERE.parent / "fixtures", "/root/fixtures")
      .add_local_file(HERE.parent / "brain.py", "/root/brain.py")
      .add_local_file(HERE.parent / "stage.py", "/root/stage.py")
      .add_local_dir(HERE.parent / "eval", "/root/eval")
      .add_local_dir(HERE, "/root/tune", ignore=["__pycache__", "data", "*.jsonl"]))
gpu = (modal.Image.debian_slim(python_version="3.12")
       .pip_install("torch==2.11.0", "transformers==5.24.0", "accelerate", "sentencepiece")
       .env({"HF_HOME": "/vol/hf", "PYTORCH_CUDA_ALLOC_CONF": "expandable_segments:True"})
       .add_local_file(HERE.parent / "intents.py", "/root/intents.py")
       .add_local_dir(HERE.parent / "eval", "/root/eval")
       .add_local_dir(HERE, "/root/tune", ignore=["__pycache__", "data"]))
app = modal.App("decider")


def chat(p: str) -> str:
    return f"<bos><start_of_turn>user\n{p}<end_of_turn>\n<start_of_turn>model\n"


def text_of(m) -> str:
    j = m.to_json() if hasattr(m, "to_json") else m
    c = j.get("content", "")
    return c if isinstance(c, str) else "".join(x.get("text", "") for x in c)


def finetune(base: str, pairs: list[tuple[str, str]], out: str, steps: float, batch: int, lr: float, device: str, seed: int = 0, epochs: bool = False) -> None:
    """Full fine-tune on the answer tokens only; logits are computed for those positions alone (the vocabulary is 262k)."""
    import random
    import shutil
    import time

    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    torch.manual_seed(seed)
    tok = AutoTokenizer.from_pretrained(base)
    model = AutoModelForCausalLM.from_pretrained(base, dtype=torch.float32, attn_implementation="sdpa" if device == "cuda" else "eager").to(device)
    model.train()
    cap = model.config.get_text_config().final_logit_softcapping
    data = [(tok(chat(p), add_special_tokens=False).input_ids, tok(a + "<end_of_turn>", add_special_tokens=False).input_ids) for p, a in pairs]
    rng, t0 = random.Random(seed), time.time()

    def epoch() -> list[list[int]]:  # random batches of similar length, so little of each is padding: up to 1.5 batch rows and 12k tokens
        order, out = rng.sample(range(len(data)), len(data)), []
        for i in range(0, len(order), batch * 64):
            pool = sorted(order[i:i + batch * 64], key=lambda j: len(data[j][0]) + len(data[j][1]))
            while pool:
                k = 1
                while k < min(len(pool), batch * 3 // 2) and (k + 1) * (len(data[pool[k]][0]) + len(data[pool[k]][1])) <= 12000:
                    k += 1
                out.append(pool[:k])
                pool = pool[k:]
        rng.shuffle(out)
        return out

    batches = epoch()
    steps = round(steps * len(batches)) if epochs else int(steps)
    opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=0.0, betas=(0.9, 0.98))
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda t: min(1.0, (t + 1) / max(1, steps // 20)) * max(0.0, 1 - t / steps))
    for step in range(steps):
        if not batches:
            batches = epoch()
        rows = [data[i] for i in batches.pop()]
        width = max(len(p) + len(a) for p, a in rows)
        ids = torch.zeros(len(rows), width, dtype=torch.long)
        att = torch.zeros(len(rows), width, dtype=torch.long)
        pick, gold = torch.zeros(len(rows), width, dtype=torch.bool), []
        for r, (p, a) in enumerate(rows):
            ids[r, :len(p) + len(a)] = torch.tensor(p + a)
            att[r, :len(p) + len(a)] = 1
            pick[r, len(p) - 1:len(p) + len(a) - 1] = True
            gold += a
        with torch.autocast(device, dtype=torch.bfloat16, enabled=device == "cuda"):
            h = model.model(input_ids=ids.to(device), attention_mask=att.to(device)).last_hidden_state[pick.to(device)]
            logits = model.lm_head(h).float()
        if cap:
            logits = torch.tanh(logits / cap) * cap
        loss = torch.nn.functional.cross_entropy(logits, torch.tensor(gold, device=device))
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        opt.zero_grad(set_to_none=True)
        if step % max(1, steps // 50) == 0 or step == steps - 1:
            print(f"step {step} loss {loss.item():.4f} lr {sched.get_last_lr()[0]:.2e} {time.time() - t0:.0f}s", flush=True)
    model.eval()
    model.save_pretrained(out)
    tok.save_pretrained(out)
    shutil.copy(f"{base}/tokenizer.model", f"{out}/tokenizer.model")


def convert(ckpt: str, outdir: str, name: str) -> str:
    """The checkpoint as a .litertlm: litert-torch's Gemma 3 270M builder, dynamic int8, Gemma's chat turns and stop tokens."""
    import glob
    import os
    import time

    from litert_torch.generative.examples.gemma3 import gemma3
    from litert_torch.generative.layers import kv_cache
    from litert_torch.generative.utilities import converter
    from litert_torch.generative.utilities.export_config import ExportConfig
    t0 = time.time()
    os.makedirs(outdir, exist_ok=True)
    config = ExportConfig()
    config.kvcache_layout = kv_cache.KV_LAYOUT_TRANSPOSED
    config.mask_as_input = True
    converter.convert_to_litert(
        gemma3.build_model_270m(ckpt), output_path=outdir, output_name_prefix=name, prefill_seq_len=PREFILL, kv_cache_max_len=KV,
        quantize="dynamic_int8", export_config=config, output_format="litertlm", tokenizer_model_path=f"{ckpt}/tokenizer.model",
        start_token_id=2, stop_token_ids=STOPS, prompt_prefix="<start_of_turn>user\n",
        prompt_suffix="<end_of_turn>\n<start_of_turn>model\n", model_prompt_prefix="<start_of_turn>model\n",
        model_prompt_suffix="<end_of_turn>\n", user_prompt_prefix="<start_of_turn>user\n", user_prompt_suffix="<end_of_turn>\n")
    out = max(glob.glob(f"{outdir}/{name}*.litertlm"), key=os.path.getmtime)
    print(f"converted {out} {os.path.getsize(out) / 1e6:.0f} MB in {time.time() - t0:.0f}s", flush=True)
    return out


@app.function(image=gpu, gpu=["L4", "A10G"], timeout=4 * 3600, volumes={"/vol": vol})
def train(name: str, seed: int = 0, n: int = 60000, epochs: float = 2.0, lr: float = 1e-4, batch: int = 32, base: str = BASE) -> None:
    """Build the data in the container, fine-tune the base on it, and score the checkpoint on the dev split (greedy, unconstrained)."""
    from huggingface_hub import snapshot_download
    import shutil
    import subprocess
    from collections import Counter

    sys.path.insert(0, "/root/tune")
    from prompt import prompt
    subprocess.run([sys.executable, "/root/tune/build.py", "--seed", str(seed), "--train", str(n), "--dev", "3000"], check=True)
    rows = {k: [json.loads(l) for l in open(f"/root/tune/data/{k}.jsonl")] for k in ("train", "dev")}
    out = f"/vol/{name}"
    shutil.copytree("/root/tune/data", f"{out}/data", dirs_exist_ok=True)
    start = "/vol/base" if base == BASE else snapshot_download(base, local_dir=f"/vol/base-{base.split('/')[-1]}")
    finetune(start, [(prompt(e["state"]), e["answer"]) for e in rows["train"]], f"{out}/ckpt", epochs, batch, lr, "cuda", seed, epochs=True)
    vol.commit()
    ok, total = Counter(), Counter()
    for f, want, got in hf_dev(f"{out}/ckpt", rows["dev"]):
        f = f.split("/")[0] + ("/prefix" if "/" in f else "")
        total[f] += 1
        ok[f] += got == want
    print(f"dev exact {sum(ok.values())}/{sum(total.values())} = {sum(ok.values()) / sum(total.values()):.3f}")
    for f in sorted(total, key=lambda f: ok[f] / total[f]):
        print(f"  {f:32} {ok[f]:4}/{total[f]:<4} {ok[f] / total[f]:.2f}")


def hf_dev(ckpt: str, dev: list[dict]) -> list[tuple[str, str, str]]:
    """(family, expected, answer) of the checkpoint on dev rows: greedy, unconstrained, on the GPU."""
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    sys.path.insert(0, "/root/tune")
    from prompt import prompt
    tok = AutoTokenizer.from_pretrained(ckpt, padding_side="left")
    model = AutoModelForCausalLM.from_pretrained(ckpt, dtype=torch.bfloat16).cuda().eval()
    out = []
    for i in range(0, len(dev), 64):
        part = dev[i:i + 64]
        enc = tok([chat(prompt(e["state"])) for e in part], add_special_tokens=False, return_tensors="pt", padding=True).to("cuda")
        with torch.no_grad():
            g = model.generate(**enc, max_new_tokens=16, do_sample=False)
        out += [(e["family"], e["answer"], tok.decode(row, skip_special_tokens=False).split("<end_of_turn>")[0].strip())
                for e, row in zip(part, g[:, enc.input_ids.shape[1]:])]
    return out


@app.function(image=gpu, gpu=["L4", "A10G"], timeout=3600, volumes={"/vol": vol})
def dev_hf(names: list[str], devs: list[str]) -> dict:
    """Each checkpoint /vol/<name>/ckpt on each dev set /vol/<dev>/data/dev.jsonl."""
    rows = {d: [json.loads(l) for l in open(f"/vol/{d}/data/dev.jsonl")] for d in devs}
    return {f"{n}@{d}": hf_dev(f"/vol/{n}/ckpt", rows[d]) for n in names for d in devs}


@app.local_entrypoint()
def devs(names: str, sets: str, out: str = "/tmp/devs.json"):
    Path(out).write_text(json.dumps(dev_hf.remote(names.split(","), sets.split(","))))
    print("wrote", out)


SPACE = "\u2581"  # LiteRT-LM's LLGuidance sees SentencePiece's space mark, not a space: a constraint must spell spaces with it


def decider(path: str):
    import litert_lm
    return litert_lm.Engine(path, backend=litert_lm.Backend.CPU(), max_num_tokens=KV)


def decide(engine, p: str, regex: str | None = None, max_tokens: int = 16) -> str:
    import litert_lm
    conv = engine.create_conversation(sampler_config=litert_lm.SamplerConfig(top_k=1, seed=0),
                                      constrained_decoding_config=litert_lm.ConstrainedDecodingConfig(
                                          provider=litert_lm.LiteRtLmConstraintProviderType.LL_GUIDANCE) if regex else None)
    try:
        return text_of(conv.send_message(p, max_output_tokens=max_tokens,
                                         response_format=litert_lm.ResponseFormat.regex(regex) if regex else None))
    finally:
        conv.close()


DEMO = [
    ({"screen": {"kind": "slide", "deck": "Northwind Q2 Review", "page": 3, "pages": 9, "title": "Revenue by region"}, "offered": [],
      "heard": ["okay so that covers the regions"], "now": "so let's look at the market size", "done": True,
      "candidates": [{"kind": "slide", "deck": "Northwind Q2 Review", "page": 6, "pages": 9, "title": "Market size", "text": "TAM $12B; SAM $2B"},
                     {"kind": "slide", "deck": "Northwind Q2 Review", "page": 8, "pages": 9, "title": "Team", "text": "Ana, CEO; Bo, CTO"},
                     {"kind": "site", "name": "GitHub", "url": "github.com"}]}, "show 1"),
    ({"screen": None, "offered": [], "heard": [], "now": "can you open get hub", "done": True,
      "candidates": [{"kind": "app", "name": "Notes"}, {"kind": "site", "name": "GitHub", "url": "github.com"}]}, "show 2"),
    ({"screen": {"kind": "slide", "deck": "Bio 101", "page": 2, "pages": 7, "title": "Agenda"}, "offered": [], "heard": [],
      "now": "next slide please", "done": True, "candidates": [{"kind": "slide", "deck": "Bio 101", "page": 1, "pages": 7, "title": "Bio 101", "text": "Cells"}]}, "next"),
    ({"screen": None, "offered": [], "heard": ["then you wear"], "now": "I need a cappucc", "done": True,
      "candidates": [{"kind": "file", "name": "Coffee order.xlsx", "folder": "Office"}, {"kind": "app", "name": "Calculator"}]}, "nothing"),
]


@app.function(image=conv, cpu=8, memory=32768, timeout=3600, volumes={"/vol": vol})
def derisk_train() -> None:
    """Ten fine-tune steps from the untouched base, on CPU."""
    from huggingface_hub import snapshot_download
    from transformers import AutoTokenizer
    sys.path.insert(0, "/root/tune")
    from prompt import prompt
    base = snapshot_download(BASE, local_dir="/vol/base")
    tok = AutoTokenizer.from_pretrained(base)
    p0 = prompt(DEMO[0][0])
    templ = tok.apply_chat_template([{"role": "user", "content": p0}], tokenize=False, add_generation_prompt=True)
    print("chat template matches:", templ == chat(p0), repr(templ[-60:]))
    finetune(base, [(prompt(s), a) for s, a in DEMO], "/vol/derisk/ckpt", steps=10, batch=4, lr=1e-4, device="cpu")
    vol.commit()


@app.function(image=conv, cpu=8, memory=32768, timeout=3600, volumes={"/vol": vol})
def convert_remote(ckpt: str, outdir: str, name: str) -> str:
    out = convert(ckpt, outdir, name)
    vol.commit()
    return out


@app.function(image=rt, cpu=8, memory=16384, timeout=3600, volumes={"/vol": vol})
def derisk_check(ckpt: str, out: str) -> None:
    """The .litertlm against the checkpoint: same answers, stops, regex constraint, long prompts, the embedder."""
    import time

    import litert_lm
    import numpy as np
    import torch
    from huggingface_hub import hf_hub_download
    from transformers import AutoModelForCausalLM, AutoTokenizer
    sys.path.insert(0, "/root/tune")
    from prompt import grammar, prompt
    tok = AutoTokenizer.from_pretrained(ckpt)
    model = AutoModelForCausalLM.from_pretrained(ckpt, dtype=torch.float32, attn_implementation="eager").eval()
    engine = decider(out)
    probes = DEMO + [({**DEMO[0][0], "now": "um so"}, "?"), ({**DEMO[1][0], "now": "open the notes app"}, "?")]
    for i, (s, want) in enumerate(probes):
        p = prompt(s)
        ids = tok(chat(p), add_special_tokens=False).input_ids
        if i == 0:
            conv = engine.create_conversation()
            rendered = conv.render_message_to_string({"role": "user", "content": p})
            print("litert renders:", repr(rendered[:40]), "...", repr(rendered[-50:]), "| same as HF:", rendered in (chat(p), chat(p)[5:]))
            print("litert tokens == HF tokens:", engine.tokenize(chat(p)[5:]) == ids[1:], len(ids), "prompt tokens")
            conv.close()
        with torch.no_grad():
            g = model.generate(torch.tensor([ids]), max_new_tokens=16, do_sample=False)
        hf = tok.decode(g[0, len(ids):], skip_special_tokens=False).split("<end_of_turn>")[0]
        t = time.time()
        lt = decide(engine, p)
        t1 = time.time()
        lc = decide(engine, p, grammar(s).replace(" ", SPACE))
        t2 = time.time()
        forced = [decide(engine, p, r.replace(" ", SPACE)) for r in ("nothing|wait", "search [a-z0-9][a-z0-9 .\\-']{0,60}", "open [a-z0-9][a-z0-9 .\\-/']{0,40}")]
        try:
            raw = decide(engine, p, grammar(s))
        except Exception as e:  # noqa: BLE001
            raw = f"{type(e).__name__}: {str(e)[:120]}"
        print(f"want {want!r} | HF {hf!r} | litert {lt!r} {t1 - t:.2f}s | constrained {lc!r} {t2 - t1:.2f}s | forced {forced} | spaces unmapped: {raw!r}", flush=True)
    long = prompt({**DEMO[0][0], "heard": ["word " * 400, "word " * 400], "now": "word " * 600})
    print("long prompt tokens:", len(tok(chat(long), add_special_tokens=False).input_ids))
    for name, p in (("long prompt", long), ("over-KV prompt", "word " * 1500)):
        try:
            print(name, "answer:", repr(decide(engine, p)))
        except Exception as e:  # noqa: BLE001
            print(name, "refused:", type(e).__name__, str(e)[:300])
    emb = litert_lm.EmbeddingEngine(hf_hub_download(*EMBEDDER, local_dir="/vol/embedder"))
    texts = ["task: search result | query: pull up the slides", "title: Market size | text: TAM $12B; SAM $2B", "title: GitHub | text: site github.com"]
    t = time.time()
    v = np.array([r.embedding for r in emb.compute_embedding_batch(texts)])
    print("embedding", v.shape, "norms", np.linalg.norm(v, axis=1).round(3), "cos q-docs", (v[1:] @ v[0]).round(3), f"{(time.time() - t) / 3 * 1e3:.0f} ms each")


@app.function(image=rt, cpu=8, memory=16384, timeout=1800, volumes={"/vol": vol})
def constraint_cost(out: str) -> None:
    """How long a constrained decision takes, by the shape of the regex, and a prompt past one prefill window."""
    import re
    import time
    sys.path.insert(0, "/root/tune")
    from prompt import grammar, prompt
    engine = decider(out)
    p, g = prompt(DEMO[0][0]), grammar(DEMO[0][0])
    variants = {"none": None, "grammar": g, "unbounded": re.sub(r"\{0,\d+\}", "*", g), "no free text": "|".join(a for a in g.split("|") if "[a-z0-9]" not in a),
                "show only": "show [1-6]", "nothing|wait": "nothing|wait"}
    import litert_lm
    cfg = lambda: litert_lm.ConstrainedDecodingConfig(provider=litert_lm.LiteRtLmConstraintProviderType.LL_GUIDANCE)
    sampler = litert_lm.SamplerConfig(top_k=1, seed=0)
    fmt = litert_lm.ResponseFormat.regex(g.replace(" ", SPACE))
    for _ in range(2):
        t0 = time.time()
        c = engine.create_conversation(sampler_config=sampler, constrained_decoding_config=cfg())
        t1 = time.time()
        a = text_of(c.send_message(p, max_output_tokens=16, response_format=fmt))
        t2 = time.time()
        c.close()
        print(f"create {t1 - t0:.2f}s send {t2 - t1:.2f}s {a!r}", flush=True)
    base = engine.create_conversation(sampler_config=sampler, constrained_decoding_config=cfg())
    for _ in range(3):
        t0 = time.time()
        c = base.clone()
        t1 = time.time()
        a = text_of(c.send_message(p, max_output_tokens=16, response_format=fmt))
        t2 = time.time()
        c.close()
        print(f"clone {t1 - t0:.2f}s send {t2 - t1:.2f}s {a!r}", flush=True)
    c = base.clone()
    t0 = time.time()
    a = text_of(c.send_message(p, max_output_tokens=16))
    print(f"clone, no format: send {time.time() - t0:.2f}s {a!r}", flush=True)
    for n in (600, 900):
        print(f"{n}-word prompt:", repr(decide(engine, prompt({**DEMO[0][0], "now": "word " * n}).replace("now (done): ", "now (done): " + "word " * (n - 60)), g.replace(" ", SPACE))))


JVM_CHECK = """import com.google.ai.edge.litertlm.*;
import java.util.*;
public class T {
  public static void main(String[] a) throws Exception {
    Engine e = new Engine(new EngineConfig(a[0], new Backend.CPU(), null, null, 1024));
    e.initialize();
    String p = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(a[1])), "UTF-8");
    Class<?> marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker");
    var config = ConversationConfig.class.getDeclaredConstructor(Contents.class, List.class, List.class, SamplerConfig.class, boolean.class,
        List.class, Map.class, LoraConfig.class, boolean.class, Integer.class, ThinkingConfig.class, boolean.class, Boolean.class, String.class,
        SpeculativeDecodingConfig.class, int.class, marker);
    var send = Conversation.class.getDeclaredMethod("sendMessage$default", Conversation.class, String.class, Map.class,
        RepetitionPenaltyConfig.class, NoRepeatNgramConfig.class, SuppressTokensConfig.class, Integer.class, ThinkingConfig.class,
        ResponseFormat.class, int.class, Object.class);
    for (String rx : new String[]{null, "show 1|nothing", "show\u25811|nothing"}) {
      long t = System.nanoTime();
      Conversation c = e.createConversation((ConversationConfig) config.newInstance(null, null, null, new SamplerConfig(1, 1.0, 0.0, 0), false,
          null, null, null, false, null, null, rx != null, null, null, null, 30711, null));
      long t1 = System.nanoTime();
      try {
        Object m = send.invoke(null, c, p, null, null, null, null, null, null, rx == null ? null : ResponseFormat.Companion.regex(rx), 126, null);
        System.out.println("JVM " + rx + " -> " + m + String.format(" create %.2fs send %.2fs", (t1 - t) / 1e9, (System.nanoTime() - t1) / 1e9));
      } catch (Throwable x) { System.out.println("JVM " + rx + " -> " + (x.getCause() != null ? x.getCause() : x)); }
      c.close();
    }
  }
}
"""


@app.function(image=rt, cpu=4, memory=8192, timeout=1800, volumes={"/vol": vol})
def jvm_check(out: str) -> None:
    """The same constraint through LiteRT-LM's JVM API, as the Kotlin app calls it."""
    import subprocess
    sys.path.insert(0, "/root/tune")
    from prompt import prompt
    Path("/tmp/T.java").write_text(JVM_CHECK)
    Path("/tmp/p.txt").write_text(prompt(DEMO[0][0]))
    cp = ":".join(str(p) for p in Path("/jars").glob("*.jar"))
    c = subprocess.run(["/opt/jdk/bin/javac", "-cp", cp, "-d", "/tmp", "/tmp/T.java"], capture_output=True, text=True)
    if c.returncode:
        return print(c.stdout[-2000:], c.stderr[-2000:])
    r = subprocess.run(["/opt/jdk/bin/java", "-cp", f"/tmp:{cp}", "T", out, "/tmp/p.txt"], capture_output=True, text=True)
    print("\n".join(l for l in (r.stdout + r.stderr).splitlines() if l.startswith(("JVM", "Exception", "\tat", "Caused")))[:3000])


@app.local_entrypoint()
def derisk(skip: bool = False):
    if not skip:
        derisk_train.remote()
        convert_remote.remote("/vol/derisk/ckpt", "/vol/derisk", "decider")
    derisk_check.remote("/vol/derisk/ckpt", "/vol/derisk/decider_q8_ekv1024.litertlm")


@app.function(image=conv, cpu=8, memory=32768, timeout=3600, volumes={"/vol": vol})
def convert_run(name: str, prefix: str = "decider") -> str:
    out = convert(f"/vol/{name}/ckpt", f"/vol/{name}", prefix)
    vol.commit()
    return out


@app.function(image=rt, cpu=4, memory=8192, timeout=3 * 3600, volumes={"/vol": vol})
def score_script(model: str, script: str, constrained: bool = True, heard: bool = False) -> list[dict]:
    from huggingface_hub import hf_hub_download
    sys.path.insert(0, "/root/tune")
    import score
    catalog = score.Catalog(hf_hub_download(*EMBEDDER, local_dir="/vol/embedder"))
    return score.run_script(catalog, score.Decider(model), Path("/root/eval") / script, constrained, heard)


@app.function(image=rt, cpu=4, memory=8192, timeout=3 * 3600, volumes={"/vol": vol})
def score_dev(model: str, data: str, start: int, stop: int, families: str = "", constrained: bool = True) -> list:
    sys.path.insert(0, "/root/tune")
    import score
    rows = [json.loads(l) for l in open(data)][start:stop]
    rows = [e for e in rows if not families or e["family"] in families.split(",")]
    return score.dev_rows(score.Decider(model), rows, constrained)


@app.local_entrypoint()
def evaluate(name: str, scripts: str = "", dev: int = 3000, convert_first: bool = False, prefix: str = "decider", families: str = "",
             mode: str = "constrained", text: str = "say", cases: str = ""):
    """Score /vol/<name>/<prefix>_q8_ekv1024.litertlm on eval scripts (all by default; "none" for none), as said or as heard
    (text=heard: eval/heard), the first `dev` dev rows (of the given families only, if any) and the rows of eval/<cases>."""
    if convert_first:
        convert_run.remote(name, prefix)
    model = f"/vol/{name}/{prefix}_q8_ekv1024.litertlm"
    names = [] if scripts == "none" else scripts.split(",") if scripts else sorted(p.name for p in (HERE.parent / "eval").glob("*.jsonl"))
    constrained = mode == "constrained"
    calls = [score_script.spawn(model, s, constrained, text == "heard") for s in names]
    chunks = [(model, f"/vol/{name}/data/dev.jsonl", i, min(dev, i + 150), families, constrained) for i in range(0, dev, 150)]
    case_rows = score_dev.spawn(model, f"/root/eval/{cases}", 0, 10 ** 6, "", constrained) if cases else None
    devs = [x for part in score_dev.starmap(chunks) for x in part] if dev else []
    out = {"scripts": {s: c.get() for s, c in zip(names, calls)}, "dev": devs, "cases": case_rows.get() if case_rows else []}
    tag = name + ("" if prefix == "decider" else "_" + prefix) + ("" if constrained else "_free") + ("_heard" if text == "heard" else "")
    Path(f"/tmp/score_{tag}.json").write_text(json.dumps(out))
    print(f"wrote /tmp/score_{tag}.json")


@app.function(image=rt, cpu=4, memory=8192, timeout=3600, volumes={"/vol": vol})
def embed_eval() -> bytes:
    """Catalog and query vectors for every eval line (whole, after the line before, its last six words), for tuning retrieval with numpy."""
    import io

    import numpy as np
    from huggingface_hub import hf_hub_download
    sys.path.insert(0, "/root/tune")
    import score
    cat = score.Catalog(hf_hub_download(*EMBEDDER, local_dir="/vol/embedder"))
    keys, texts = [], []
    for p in sorted(Path("/root/eval").glob("*.jsonl")):
        says = [json.loads(l)["say"] for l in p.read_text().splitlines() if l.strip()]
        for i, say in enumerate(says):
            for kind, q in (("now", say), ("both", f"{says[i - 1]} {say}" if i else say), ("tail", " ".join(say.split()[-6:]))):
                keys.append(f"{p.name}|{i}|{kind}")
                texts.append(f"task: search result | query: {q}")
    variants, owners = [], []
    for i, it in cat.items.items():
        name = it.get("title") or it["name"]
        alts = {"slide": [f"title: {name} | text: none"] + ([f"title: {it['deck']} | text: presentation slides"] if it.get("page") == 1 else []),
                "file": [f"title: {name} | text: none"], "site": [f"title: {name} | text: {name} website, {it.get('url')}"],
                "app": [f"title: {name} | text: {name} app"]}[it["kind"]]
        variants += alts
        owners += [f"{i}|alt"] * len(alts)
    buf = io.BytesIO()
    np.savez_compressed(buf, ids=np.array(cat.ids), docs=cat.vecs, keys=np.array(keys), queries=cat.embed(texts),
                        alt_owner=np.array(owners), alt_docs=cat.embed(variants))
    return buf.getvalue()


@app.local_entrypoint()
def vectors(out: str = "/tmp/eval_vectors.npz"):
    Path(out).write_bytes(embed_eval.remote())
    print("wrote", out)

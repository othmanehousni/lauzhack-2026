"""Train the open-vocabulary corrector (corrector.py) on Modal: LoRA on Qwen3-4B-Instruct, then 4-bit MLX.

  modal run train_corrector.py      # ~1 h, a few dollars; then download with the command it prints

Training examples pair a lip-reader's guesses with the true sentence:
- simulated from public text (Wikipedia, UltraChat): the sentence's lip shapes are corrupted a little and re-read
  as the sound-alike common words a lip reader would output; rare words (names, brands) can never be output as is,
  exactly like the real model. Half the examples with rare words also get a dictionary that contains them.
- real: the lip model's guesses on calib/nbest_train.jsonl (your training clips; text only, no video).
No personal data beyond those guess/sentence texts is sent.
"""
import io
import json
import math
import random
import re

import modal

app = modal.App("lipread-corrector")
vol = modal.Volume.from_name("lipread-corrector", create_if_missing=True)
BASE = "Qwen/Qwen3-4B-Instruct-2507"
data_image = (modal.Image.debian_slim(python_version="3.11")
              .pip_install("datasets==3.6.0", "wordfreq==3.1.1")
              .add_local_file("data/cmudict.dict", "/root/data/cmudict.dict")
              .add_local_python_source("corrector"))
gpu_image = (modal.Image.debian_slim(python_version="3.11")
             .pip_install("torch==2.6.0", "transformers==4.57.1", "peft==0.17.1", "accelerate==1.10.1")
             .add_local_file("data/cmudict.dict", "/root/data/cmudict.dict")
             .add_local_python_source("corrector"))
mlx_image = (modal.Image.debian_slim(python_version="3.11")
             .pip_install("mlx[cpu]==0.32.4", "mlx-lm==0.32.2"))
VOCAB = 30_000  # what the lip reader can output: roughly its training vocabulary of common words


def words_of(text):
    return re.sub(r"[^a-z' ]", " ", text.lower()).split()


# ---------- simulated lip-reading guesses ----------

def vocabulary():
    from collections import defaultdict
    from wordfreq import top_n_list, word_frequency
    import corrector
    words = [w for w in top_n_list("en", 2 * VOCAB) if re.fullmatch(r"[a-z]+('[a-z]+)?", w)][:VOCAB]
    by_shape = defaultdict(list)
    for w in words:  # most frequent first
        by_shape[corrector.lip_shape(w)].append((w, math.log(word_frequency(w, "en"))))
    return set(words), dict(by_shape)


def corrupt(shape, rate, rng):
    """Visual noise: a few lip shapes misread, missed or imagined."""
    out = []
    for c in shape:
        x = rng.random()
        if x < rate / 3:
            out.append(rng.choice("PFTSJDKRWEAO"))
        elif x < 2 * rate / 3:
            continue
        elif x < rate:
            out += [c, rng.choice("PFTSJDKRWEAO")]
        else:
            out.append(c)
    return "".join(out)


def read_as_words(shape, by_shape, rng, temp=1.0):
    """Sample a sequence of common words whose lip shapes spell `shape` (a lip reader's guess for that stretch)."""
    n, edges = len(shape), [[] for _ in range(len(shape) + 1)]
    for i in range(n):
        for j in range(i + 1, min(n, i + 12) + 1):
            for w, lf in by_shape.get(shape[i:j], [])[:8]:
                edges[j].append((i, w, lf / temp))
        edges[i + 1].append((i, None, -14 / temp))  # skip a shape no word explains
    alpha = [0.0] + [-math.inf] * n
    for j in range(1, n + 1):
        scores = [alpha[i] + s for i, _, s in edges[j]]
        m = max(scores)
        alpha[j] = m + math.log(sum(math.exp(s - m) for s in scores))
    out, j = [], n
    while j > 0:
        scores = [alpha[i] + s for i, _, s in edges[j]]
        m = max(scores)
        i, w, _ = rng.choices(edges[j], weights=[math.exp(s - m) for s in scores])[0]
        if w:
            out.append(w)
        j = i
    return out[::-1]


def guesses_for(words, vocab, by_shape, rng, k=8):
    """An n-best list like the lip model's: common words often right, rare words always misread."""
    import corrector
    level = rng.uniform(0.05, 0.8)  # how hard this sentence is to read
    noisy = [corrupt(corrector.lip_shape(w), rng.uniform(0, 0.15), rng) for w in words]  # same video for all guesses
    base = [w in vocab and rng.random() > level for w in words]
    hyps = []
    for _ in range(k + 4):
        keep = [b if rng.random() > 0.15 else (not b and w in vocab) for b, w in zip(base, words)]
        out, span = [], ""
        for w, kept, shape in zip(words + [None], keep + [True], noisy + [""]):
            if not kept and w is not None:
                span += shape
                continue
            if span:
                out += read_as_words(span, by_shape, rng, temp=rng.uniform(0.7, 1.5))
                span = ""
            if w is not None:
                out.append(w)
        hyps.append(" ".join(out))
    hyps = [h for h in dict.fromkeys(hyps) if h]
    hyps.sort(key=lambda h: -len(set(h.split()) & set(words)))  # better guesses first, like a ranked n-best
    return hyps[:k]


def sentences(source, n, seed):
    """Clean 5-16 word English sentences without digits."""
    from datasets import load_dataset
    ds = (load_dataset("wikimedia/wikipedia", "20231101.en", split="train", streaming=True) if source == "wiki"
          else load_dataset("HuggingFaceH4/ultrachat_200k", split="train_sft", streaming=True))
    ds = ds.shuffle(seed=seed, buffer_size=10_000)
    out = []
    for row in ds:
        text = row["text"] if source == "wiki" else row["messages"][0]["content"]
        for s in re.split(r"(?<=[.!?])\s+", text.replace("\n", " ")):
            s = s.strip()
            if (5 <= len(s.split()) <= 16 and re.fullmatch(r"[A-Za-z][A-Za-z' ,.?!-]*[.?!]", s)
                    and not re.search(r"[A-Z]{3,}", s)):
                out.append(s)
        if len(out) >= n:
            return out[:n]
    return out


def example(target, guesses, dictionary=()):
    import corrector
    return {"messages": corrector.messages(guesses, list(dictionary)), "target": target}


def build_chunk(args):
    """Worker: simulated examples for a chunk of sentences."""
    chunk, seed, rare_pool = args
    import corrector
    rng = random.Random(seed)
    vocab, by_shape = vocabulary()
    out = []
    for s in chunk:
        words = words_of(s)
        hyps = guesses_for(words, vocab, by_shape, rng)
        if not hyps:
            continue
        rare = [w for w in re.findall(r"[A-Za-z']+", s) if w.lower() not in vocab and len(w) > 2]
        dictionary = []
        if (rare and rng.random() < 0.6) or rng.random() < 0.25:  # sometimes only distractors: don't force it
            dictionary = rare + rng.sample(rare_pool, min(len(rare_pool), rng.randint(5, 60)))
            dictionary = corrector.relevant(list(dict.fromkeys(dictionary)), hyps)
        out.append(example(s, hyps, dictionary))
    return out


@app.function(image=data_image, cpu=16, memory=16384, timeout=3 * 3600, volumes={"/vol": vol})
def make_data(n_wiki: int = 50_000, n_chat: int = 35_000):
    from multiprocessing import Pool
    texts = sentences("wiki", n_wiki, 0) + sentences("chat", n_chat, 1)
    random.Random(0).shuffle(texts)
    vocab, _ = vocabulary()
    rare_pool = sorted({w for s in texts for w in re.findall(r"[A-Za-z']+", s) if w.lower() not in vocab and len(w) > 2})
    chunks = [(texts[i:i + 500], i, rare_pool) for i in range(0, len(texts), 500)]
    with Pool(16) as pool:
        synthetic = [e for part in pool.map(build_chunk, chunks) for e in part]
    for name, rows in (("train", synthetic[1000:]), ("val", synthetic[:1000])):
        with open(f"/vol/data_{name}.jsonl", "w") as f:
            f.writelines(json.dumps(r) + "\n" for r in rows)
    vol.commit()
    return {"synthetic": len(synthetic), "rare_pool": len(rare_pool), "samples": synthetic[:2]}


def real_examples(real):
    """The lip model's own guesses on your training clips, in the same format lipread uses at runtime."""
    out = []
    for line in real.decode().splitlines():
        r = json.loads(line)
        guesses = [g for g in dict.fromkeys(r["guesses"][:8] + [r["ctc"]]) if g]
        if guesses:
            out.append(example(r["text"].capitalize() + ".", guesses))
    return out


# ---------- LoRA training ----------

@app.function(image=gpu_image, gpu="H100", timeout=4 * 3600, volumes={"/vol": vol})
def train(real: bytes = b"", epochs: float = 1.0, lr: float = 2e-4, rank: int = 16, max_tokens: int = 12_000,
          eval_n: int = 300):
    import torch
    from peft import LoraConfig, get_peft_model
    from transformers import AutoModelForCausalLM, AutoTokenizer
    vol.reload()
    tok = AutoTokenizer.from_pretrained(BASE)
    model = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype=torch.bfloat16).cuda()
    model.gradient_checkpointing_enable()
    model.enable_input_require_grads()
    model = get_peft_model(model, LoraConfig(r=rank, lora_alpha=2 * rank, lora_dropout=0.05, task_type="CAUSAL_LM",
                                             target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                                                             "gate_proj", "up_proj", "down_proj"]))
    rows = [json.loads(line) for line in open("/vol/data_train.jsonl")] + real_examples(real) * 4  # real errors count more
    val = [json.loads(line) for line in open("/vol/data_val.jsonl")][:eval_n]

    def encode(r):  # loss only on the answer tokens
        prompt = tok.apply_chat_template(r["messages"], add_generation_prompt=True, tokenize=True)
        full = tok.apply_chat_template(r["messages"] + [{"role": "assistant", "content": r["target"]}], tokenize=True)
        assert full[:len(prompt)] == prompt
        return full, [-100] * len(prompt) + full[len(prompt):]
    data = sorted((encode(r) for r in rows), key=lambda x: len(x[0]))
    batches, cur = [], []
    for item in data:  # length-bucketed batches of ~max_tokens tokens
        if cur and (len(cur) + 1) * len(item[0]) > max_tokens:
            batches.append(cur)
            cur = []
        cur.append(item)
    batches.append(cur)
    steps = int(len(batches) * epochs)
    order = [b for _ in range(math.ceil(epochs)) for b in random.Random(0).sample(batches, len(batches))][:steps]
    params = [p for p in model.parameters() if p.requires_grad]
    opt = torch.optim.AdamW(params, lr=lr, weight_decay=0.0)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1, (s + 1) / 100) * 0.5 * (1 + math.cos(math.pi * s / steps)))

    def wer_on(rows, use_model=True):
        errs = n = 0
        model.eval()
        for r in rows:
            if use_model:
                ids = tok.apply_chat_template(r["messages"], add_generation_prompt=True, return_tensors="pt").cuda()
                with torch.no_grad():
                    out = model.generate(ids, max_new_tokens=60, do_sample=False)
                hyp = tok.decode(out[0, ids.shape[1]:], skip_special_tokens=True)
            else:  # the lip reader's top guess
                hyp = r["messages"][1]["content"].split("\n")[1 if r["messages"][1]["content"].startswith("dictionary") else 0]
                hyp = re.sub(r"^\d+\. | \[.*\]$", "", hyp)
            ref, h = words_of(r["target"]), words_of(hyp)
            d = list(range(len(h) + 1))
            for i, a in enumerate(ref, 1):
                prev, d[0] = d[0], i
                for j, b in enumerate(h, 1):
                    prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (a != b))
            errs, n = errs + d[-1], n + len(ref)
        model.train()
        return round(errs / n, 4)

    log = {"examples": len(rows), "steps": steps, "val_top_guess_wer": wer_on(val, False),
           "val_wer_before": wer_on(val[:100])}
    print(log, flush=True)
    model.train()
    for step, batch in enumerate(order):
        width = max(len(ids) for ids, _ in batch)
        ids = torch.tensor([x + [tok.pad_token_id] * (width - len(x)) for x, _ in batch]).cuda()
        labels = torch.tensor([y + [-100] * (width - len(y)) for _, y in batch]).cuda()
        mask = torch.tensor([[1] * len(x) + [0] * (width - len(x)) for x, _ in batch]).cuda()
        loss = model(input_ids=ids, attention_mask=mask, labels=labels).loss
        loss.backward()
        torch.nn.utils.clip_grad_norm_(params, 1.0)
        opt.step()
        sched.step()
        opt.zero_grad()
        if step % 200 == 0:
            print(f"step {step}/{steps} loss {loss.item():.3f}", flush=True)
    log["val_wer_after"] = wer_on(val)
    print(log, flush=True)
    model = model.merge_and_unload()
    model.save_pretrained("/vol/merged", safe_serialization=True)
    tok.save_pretrained("/vol/merged")
    vol.commit()
    return log


@app.function(image=mlx_image, cpu=8, memory=32768, timeout=3600, volumes={"/vol": vol})
def to_mlx():
    import shutil
    import subprocess
    vol.reload()
    shutil.rmtree("/vol/mlx", ignore_errors=True)
    subprocess.run(["python", "-m", "mlx_lm", "convert", "--hf-path", "/vol/merged", "--mlx-path", "/vol/mlx",
                    "-q", "--q-bits", "4"], check=True)
    shutil.rmtree("/vol/merged")  # only the 4-bit MLX model is kept for download
    vol.commit()


@app.local_entrypoint()
def main(real: str = "calib/nbest_train.jsonl", skip_data: bool = False):
    if not skip_data:
        print(make_data.remote())
    print(train.remote(open(real, "rb").read()))
    to_mlx.remote()
    print("Download:  modal volume get lipread-corrector mlx models/corrector  "
          "then:  modal volume delete lipread-corrector")

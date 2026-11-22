"""Fine-tune the lip model's encoder to one person in MLX. train_profile.py runs this on a Modal GPU (MLX's CUDA
backend), so the weights come back ready for local MLX inference; it also runs on a Mac.

  .venv/bin/python finetune_mlx.py --out /tmp/lips.safetensors      train on calib/clips.npz (15% held out)
  .venv/bin/python finetune_mlx.py --epochs 1                        time one epoch, report peak memory

Encoder only, AdamW (bias correction on, like torch) lr 5e-5 wd 0.01, grad clip 5, BatchNorm statistics frozen,
random 88 crop + flip + speed/lighting/blanking augmentation, best epoch by held-out CTC WER. Batches of 8 run as
micro-batches (default 4) with gradient accumulation, padded to the whole batch's length so the result is the same.
"""
import argparse
import time

import mlx.core as mx
import mlx.optimizers as optim
import numpy as np
from mlx.utils import tree_flatten, tree_map

import vsr_mlx as vm


def to_input(clip, train, aug=False):
    """(T, 96, 96) uint8 -> (T, 88, 88, 1) normalized; same random draws as the PyTorch recipe it was ported from."""
    x = clip.astype(np.float32) / 255.0
    if train:  # augment like Auto-AVSR training: random 88x88 crop, horizontal flip
        dy, dx = np.random.randint(0, 9, 2)
        x = x[:, dy:dy + 88, dx:dx + 88]
        if np.random.rand() < 0.5:
            x = x[:, :, ::-1]
    else:
        x = x[:, 4:92, 4:92]
    if aug:  # lighting changes, and blank short stretches like Auto-AVSR's time masking
        x = x * np.random.uniform(0.8, 1.2) + np.random.uniform(-0.08, 0.08)
        for _ in range(len(x) // 25):
            t0 = np.random.randint(0, len(x))
            x[t0:t0 + np.random.randint(0, 10)] = x.mean()
    return ((x - 0.421) / 0.165)[..., None].astype(np.float32)


def sample(clip, text, cut, spans, aug):
    """A training example: optionally a random run of 3+ words cut at the word times, random speed 0.85-1.15x."""
    words = text.split()
    if spans and cut is not None and len(words) >= 5 and np.random.rand() < spans:
        a = np.random.randint(0, len(words) - 2)
        b = np.random.randint(a + 3, len(words) + 1)
        clip, words = clip[cut[a]:cut[b]], words[a:b]
    if aug and np.random.rand() < 0.8:
        n = max(10, round(len(clip) / np.random.uniform(0.85, 1.15)))
        clip = clip[np.linspace(0, len(clip) - 1, n).round().astype(int)]
    return clip, " ".join(words)


def evaluate(lip, clips, texts, beam=False):
    """Corpus WER on held-out clips: greedy CTC, or the full beam search with the LM."""
    lip.model.eval()
    hyps = []
    for c in clips:
        enc = lip.model.encoder(mx.array(to_input(c, False))[None])[0]
        if beam:
            ended = vm.beam_search(lip.model, lip.lm, enc)
            best = ended[0][0][1:-1].tolist() if ended else []
        else:
            ids = mx.argmax(lip.model.ctc.ctc_lo(enc), -1).tolist()
            best = [i for t, i in enumerate(ids) if i and (t == 0 or i != ids[t - 1])]
        hyps.append(lip.text(best))
    return vm.wer(" ".join(texts).upper().split(), " ".join(hyps).upper().split())


def train(clips, texts, cuts, epochs=20, spans=0.0, aug=True, lr=5e-5, batch=8, micro=4, out=None, log=print):
    """Returns {"baseline_ctc", "epochs": [...], "best_epoch", "tuned_ctc", "tuned_beam"}; saves the best encoder
    weights (fp16 safetensors, load with vsr_mlx.load_avsr(out)) to `out` when it beats the stock model."""
    np.random.seed(0)
    mx.random.seed(0)
    # Every batch has a new length, so MLX's buffer cache fills with odd sizes (13 GB footprint, swapping): cap it.
    mx.set_cache_limit(1 << 30)
    order = np.random.default_rng(0).permutation(len(clips))
    n_val = max(3, len(clips) * 15 // 100)
    val, tr = order[:n_val], order[n_val:]
    v_clips, v_texts = [clips[i] for i in val], [texts[i] for i in val]
    log(f"{len(tr)} clips to train on, {n_val} held out")
    lip = vm.LipModel()  # stock weights
    model = lip.model
    vm.trainable_encoder(model)
    opt = optim.AdamW(learning_rate=lr, weight_decay=0.01, bias_correction=True)
    res = {"baseline_ctc": evaluate(lip, v_clips, v_texts), "epochs": []}
    best_val, best_state = res["baseline_ctc"], None
    log(f"stock model: held-out CTC WER {best_val:.1%}")
    for epoch in range(epochs):
        model.train()
        mx.reset_peak_memory()
        t0, losses = time.time(), []
        perm = np.random.permutation(len(tr))
        for b in range(0, len(perm), batch):
            items = [sample(clips[tr[i]], texts[tr[i]], cuts[tr[i]], spans, aug) for i in perm[b:b + batch]]
            T = max(len(c) for c, _ in items)  # pad every micro-batch to the full batch's length
            acc, total = None, 0.0
            for m in range(0, len(items), micro):
                part = items[m:m + micro]
                x = np.zeros((len(part), T, 88, 88, 1), np.float32)
                for j, (c, _) in enumerate(part):
                    x[j, :len(c)] = to_input(c, True, aug)
                labels = [[lip.tok.get(p, 1) for p in lip.sp.encode(t.upper(), out_type=str)] for _, t in part]
                loss, g = vm.loss_and_grads(model, mx.array(x), [len(c) for c, _ in part], labels)
                w = len(part) / len(items)  # per-micro-batch means -> the whole batch's mean
                g = tree_map(lambda a: a * w, g)
                acc = g if acc is None else tree_map(mx.add, acc, g)
                mx.eval(acc, loss)
                total += loss.item() * w
            acc, _ = optim.clip_grad_norm(acc, 5.0)
            opt.update(model, acc)
            mx.eval(model.parameters(), opt.state)
            losses.append(total)
        secs, peak = time.time() - t0, mx.get_peak_memory() / 1e9
        v = evaluate(lip, v_clips, v_texts)
        res["epochs"].append({"epoch": epoch + 1, "loss": round(float(np.mean(losses)), 3), "val_ctc": round(v, 4),
                              "seconds": round(secs, 1), "peak_gb": round(peak, 2)})
        log(res["epochs"][-1])
        if v < best_val:
            best_val, best_state = v, dict(tree_flatten(model.trainable_parameters()))
    if best_state:
        model.load_weights(list(best_state.items()), strict=False)
        if out:
            mx.save_safetensors(out, {k: a.astype(mx.float16) for k, a in best_state.items()})
            log(f"saved {out}")
    res["best_epoch"] = min(res["epochs"], key=lambda e: e["val_ctc"])["epoch"] if best_state else 0
    res["tuned_ctc"], res["tuned_beam"] = evaluate(lip, v_clips, v_texts), evaluate(lip, v_clips, v_texts, beam=True)
    log(f"held-out WER after tuning (epoch {res['best_epoch']}): CTC {res['tuned_ctc']:.1%}, "
        f"beam {res['tuned_beam']:.1%}")
    return res


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--spans", type=float, default=0.0, help="chance of training on a cut run of words")
    ap.add_argument("--micro", type=int, default=4, help="micro-batch size (gradient accumulation to 8)")
    ap.add_argument("--out", help="where to save the tuned encoder weights (lipread --calibrate uses profile/lips.safetensors)")
    args = ap.parse_args()
    from lipread import load_calib
    train(*load_calib(), epochs=args.epochs, spans=args.spans, micro=args.micro, out=args.out)

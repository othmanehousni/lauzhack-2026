"""Compare the MLX port against the PyTorch reference, layer by layer and through beam search.

The reference is the ESPnet code vendored in github.com/amanvirparhar/chaplin. It needs Python 3.11 for distutils.

  uv run --no-project --python 3.11 --with torch --with mlx --with numpy --with six check.py /path/to/chaplin [--crops clip.npy]
"""

import argparse
import json
import sys
import time
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
p.add_argument("ref", type=Path, help="checkout of github.com/amanvirparhar/chaplin")
p.add_argument("--crops", type=Path, help="mouth crops (T, 88, 88) uint8; random noise if omitted")
p.add_argument("--file", default="lipread.safetensors")
args = p.parse_args()
here = Path(__file__).parent
sys.path[:0] = [str(here), str(args.ref)]

import mlx.core as mx
import numpy as np
import torch
from espnet.nets.batch_beam_search import BatchBeamSearch
from espnet.nets.pytorch_backend.e2e_asr_transformer import E2E
from espnet.nets.pytorch_backend.lm.transformer import TransformerLM
from espnet.nets.pytorch_backend.transformer.mask import subsequent_mask

import model as M

raw = here / "weights/raw"
reader = M.LipReader(file=args.file)
V = len(reader.tokens)
load = lambda name: torch.load(raw / name, map_location="cpu", weights_only=True)
ref = E2E(V, argparse.Namespace(**json.loads((raw / "vsr.json").read_text())[2]))
ref.load_state_dict(load("vsr.pth"))
lm = TransformerLM(V, argparse.Namespace(**json.loads((raw / "lm.json").read_text())))
lm.load_state_dict(load("lm.pth"))
ref.eval()
lm.eval()

rng = np.random.default_rng(0)
crops = np.load(args.crops) if args.crops else rng.integers(0, 256, (50, 88, 88), dtype=np.uint8)
worst = 0.0


def report(name: str, mine, theirs) -> None:
    global worst
    mine, theirs = np.asarray(mine, np.float32), np.asarray(theirs, np.float32)
    diff = float(np.abs(mine - theirs).max())
    worst = max(worst, diff / float(np.abs(theirs).max()))
    print(f"{name:26} max |diff| {diff:.2e}   reference scale {np.abs(theirs).max():.2e}")


with torch.no_grad():
    x = ((torch.tensor(crops).float() / 255 - 0.421) / 0.165)[None]
    t_feats, t_enc = ref.encode(x, extract_resnet_feats=True), ref.encode(x)
    t_ctc = ref.ctc.log_softmax(t_enc[None])[0]
    ys = torch.tensor(rng.integers(1, V - 1, (1, 9)))
    ys[0, 0] = V - 1
    t_dec = ref.decoder(ys, subsequent_mask(9)[None], t_enc[None], None)[0][0].log_softmax(-1)
    t_lm = lm.decoder(lm.encoder(lm.embed(ys), lm._target_mask(ys))[0])[0].log_softmax(-1)

feats = reader.features(crops)
enc = reader.encode(feats)
report("front-end features", feats, t_feats)
report("encoder output", enc, t_enc)
report("ctc log-probabilities", M.log_softmax(M.linear(reader.w, "ctc", enc)), t_ctc)

same_enc = mx.array(t_enc.numpy()).astype(reader.dtype)
memory, tokens = M.cross_memory(reader.w, same_enc), mx.array(ys.numpy())
report("decoder, one pass", M.decoder_step(reader.w, tokens, None, memory)[0][0], t_dec[-1])
report("lm, one pass", M.lm_step(reader.w, tokens, None)[0][0], t_lm[-1])
dec_cache = lm_cache = None
dec_steps, lm_steps = [], []
for i in range(9):
    out, dec_cache = M.decoder_step(reader.w, tokens[:, i:i + 1], dec_cache, memory)
    dec_steps.append(out[0])
    out, lm_cache = M.lm_step(reader.w, tokens[:, i:i + 1], lm_cache)
    lm_steps.append(out[0])
report("decoder, token by token", mx.stack(dec_steps), t_dec)
report("lm, token by token", mx.stack(lm_steps), t_lm)

search = BatchBeamSearch(beam_size=40, vocab_size=V, weights=dict(decoder=0.9, ctc=0.1, lm=0.3, length_bonus=0.0),
                         scorers=ref.scorers() | {"lm": lm}, sos=V - 1, eos=V - 1, token_list=reader.tokens, pre_beam_score_key="decoder")
t = time.perf_counter()
with torch.no_grad():
    theirs = search(t_enc)
t_ref = time.perf_counter() - t
t = time.perf_counter()
mine = reader.decode(same_enc)
t_mine = time.perf_counter() - t
print(f"\nbeam search on the same encoder output: reference {t_ref:.2f}s, MLX {t_mine:.2f}s")
agree = True
for (score, toks), h in zip(mine[:5], theirs[:5]):
    their_toks = [int(t) for t in h.yseq[1:-1]]
    agree &= toks == their_toks and abs(score - float(h.score)) < 0.05
    print(f"  mlx {score:9.3f} {reader.text(toks)!r}\n  ref {float(h.score):9.3f} {reader.text(their_toks)!r}")
print(f"  end to end (MLX encoder): {reader.text(reader.decode(enc)[0][1])!r}")
print(f"\nworst relative difference {worst:.2e}; 5-best lists {'agree' if agree else 'DIFFER'}")
sys.exit(0 if agree and worst < 2e-2 else 1)

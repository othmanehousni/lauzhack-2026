"""One-time PyTorch -> MLX weight conversion for vsr_mlx.py, plus parity checks against the PyTorch model.

  .venv/bin/python convert_weights.py           writes mlx_weights/{model,lm}.safetensors (setup.sh runs this)
  .venv/bin/python convert_weights.py --check   parity checkpoints vs PyTorch on CPU (fp32)
  .venv/bin/python convert_weights.py --wer     held-out WER (76 clips) + timings, MLX vs PyTorch

Needs torch and the PyTorch weights; setup.sh installs both for the conversion and removes them afterwards.
The checks compare the personal model when a PyTorch personal.pt is present, the stock model otherwise.

Keys stay the same; conv weights go channels-last (5-D (0,2,3,4,1), 4-D (0,2,3,1), 3-D (0,2,1)).
"""
import os
import sys
import time

import numpy as np
import torch

import mlx.core as mx
import vsr_mlx as vm

HERE = os.path.dirname(os.path.abspath(__file__))
VSR = os.path.join(HERE, "vsr")
MODEL = f"{VSR}/benchmarks/LRS3/models/LRS3_V_WER19.1"
LM = f"{VSR}/benchmarks/LRS3/language_models/lm_en_subword"
PERSONAL = os.path.join(HERE, "personal.pt")
PERM = {5: (0, 2, 3, 4, 1), 4: (0, 2, 3, 1), 3: (0, 2, 1)}
sys.path.insert(0, VSR)  # ESPnet / pipelines, for the parity checks


def convert(src, dst, dtype=None):
    sd = torch.load(src, map_location="cpu")
    out = {}
    for k, v in sd.items():
        if k.endswith("num_batches_tracked"):
            continue
        a = v.float().numpy() if dtype is None else v.to(dtype).numpy()
        out[k] = mx.array(a.transpose(PERM[a.ndim]) if a.ndim in PERM else a)
    mx.save_safetensors(dst, out)
    print(f"{dst}: {len(out)} tensors")
    return out


def main():
    os.makedirs(vm.WEIGHTS, exist_ok=True)
    convert(f"{MODEL}/model.pth", os.path.join(vm.WEIGHTS, "model.safetensors"))
    convert(f"{LM}/model.pth", os.path.join(vm.WEIGHTS, "lm.safetensors"))
    if os.path.exists(PERSONAL):  # fine-tuned encoder params only, kept fp16
        convert(PERSONAL, os.path.join(vm.WEIGHTS, "personal.safetensors"), torch.float16)
    vm.load_avsr(), vm.load_lm()  # strict loads: every key and shape matches
    print("Loaded strictly into vsr_mlx.")


# ---------------------------------------------------------------- parity checks (torch CPU fp32, eval mode)

PERSONAL_MLX = os.path.join(vm.WEIGHTS, "personal.safetensors") if os.path.exists(PERSONAL) else None


def torch_avsr():
    from pipelines.model import AVSR
    a = AVSR("video", f"{MODEL}/model.pth", f"{MODEL}/model.json", f"{LM}/model.pth", f"{LM}/model.json",
             penalty=0.0, ctc_weight=0.1, lm_weight=0.3, beam_size=20, device="cpu")
    if os.path.exists(PERSONAL):
        a.model.load_state_dict({k: v.float() for k, v in torch.load(PERSONAL).items()}, strict=False)
    return a


def pad8(c):
    return np.concatenate([c, c[-1:].repeat(-len(c) % 8, 0)])


def report(name, err, tol):
    print(f"  {name:<34} {err:.2e}  (tol {tol:g})  {'ok' if err <= tol else 'FAIL'}", flush=True)
    return err <= tol


def maxdiff(a, b):
    return float(np.abs(np.asarray(a, np.float32) - np.asarray(b, np.float32)).max())


def setup():
    from lipread import load_calib
    from pipelines.data.transforms import VideoTransform
    clips, texts, _ = load_calib()
    a = torch_avsr()
    tr = VideoTransform(speed_rate=1)

    def t_encode(clip):  # exactly LipReader.encode_crops, on CPU
        with torch.no_grad():
            return a.model.encode(tr(torch.tensor(pad8(clip)))).numpy()[:len(clip)]
    return clips, texts, a, t_encode


def batch(clips, texts, a):
    """Two clips of different lengths, zero-padded, with masks and labels, for torch and MLX."""
    lens = np.array([len(c) for c in clips])
    x = np.zeros((len(clips), lens.max(), 88, 88, 1), np.float32)
    for b, c in enumerate(clips):
        x[b, :len(c)] = vm.normalize(c)
    tok = {t: i for i, t in enumerate(a.token_list)}
    import sentencepiece
    sp = sentencepiece.SentencePieceProcessor(model_file=os.path.join(vm.TOKENS, "unigram5000.model"))
    labels = [[tok.get(p, 1) for p in sp.encode(t.upper(), out_type=str)] for t in texts]
    mask_t = (torch.arange(lens.max())[None] < torch.tensor(lens)[:, None]).unsqueeze(1)
    return x, lens, labels, torch.tensor(x[..., 0]).unsqueeze(1), mask_t


def check():
    import math
    import mlx.nn as nn
    import mlx.optimizers as optim
    from espnet.nets.ctc_prefix_score import CTCPrefixScoreTH
    from espnet.nets.pytorch_backend.transformer.add_sos_eos import add_sos_eos
    from espnet.nets.pytorch_backend.transformer.mask import target_mask
    clips, texts, a, t_encode = setup()
    lip = vm.LipModel(PERSONAL_MLX)
    m, lm = lip.model, lip.lm
    good = True

    print("1. front end -> each encoder block -> encoder output (clip 0, personal weights)")
    clip = clips[0]
    outs = {}
    hooks = [a.model.encoder.frontend.register_forward_hook(lambda mod, i, o: outs.__setitem__("f", o))]
    for i, layer in enumerate(a.model.encoder.encoders):
        hooks.append(layer.register_forward_hook(lambda mod, inp, o, i=i: outs.__setitem__(i, o[0][0])))
    with torch.no_grad():
        enc_t = a.model.encoder(torch.tensor(vm.normalize(clip)[..., 0])[None, None], None)[0][0].numpy()
    for h in hooks:
        h.remove()
    e = m.encoder
    f = e.frontend(mx.array(vm.normalize(clip))[None])
    good &= report("front end (B,T,512)", maxdiff(f, outs["f"]), 1e-3)
    h, pos = e.embed[0](f) * math.sqrt(vm.D), vm.rel_pos(f.shape[1])
    errs = []
    for i, layer in enumerate(e.encoders):
        h = layer(h, pos, None)
        errs.append(maxdiff(h, outs[i]))
    print("  per-block max abs diff:", " ".join(f"{v:.1e}" for v in errs))
    good &= report("worst encoder block", max(errs), 1e-3)
    good &= report("encoder output", maxdiff(e.after_norm(h)[0], enc_t), 1e-3)

    print("2. masked batch B=2 (clips 1, 2)")
    x, lens, labels, xt, mask_t = batch([clips[1], clips[2]], [texts[1], texts[2]], a)
    mask = (mx.arange(x.shape[1])[None] < mx.array(lens)[:, None])[:, None]
    with torch.no_grad():
        hs_t = a.model.encoder(xt, mask_t)[0].numpy()
    hs_m = np.array(m.encoder(mx.array(x), mask))
    good &= report("padded batch encoder output", maxdiff(hs_m, hs_t), 1e-3)

    print("3. decoder + LM next-token log-probs, CTC prefix scores")
    enc_tt = torch.tensor(enc_t)
    with torch.no_grad():
        y = a.beam_search(enc_tt)[0].yseq
        dec_err = lm_err = 0.0
        steps, ym = vm.Steps(m, lm, mx.array(enc_t)), mx.array(y.numpy().astype(np.int32))[None]
        for k in range(1, len(y)):  # full-prefix forward and the beam search's cached steps, vs torch
            ys = y[:k][None]
            dt = a.model.decoder.batch_score(ys, [None], enc_tt[None])[0]
            lt = a.beam_search.full_scorers["lm"].batch_score(ys, [None], None)[0]
            dec_full = nn.log_softmax(m.decoder(ym[:, :k], mx.array(enc_t)[None])[:, -1])
            dec_step, lm_step = steps(ym[:, k - 1], k - 1)
            dec_err = max(dec_err, maxdiff(dec_full, dt), maxdiff(dec_step, dt))
            lm_err = max(lm_err, maxdiff(lm(ym[:, :k]), lt), maxdiff(lm_step, lt))
            if k == 1:
                ids0 = torch.topk(dt, 30)[1]
        good &= report(f"decoder log-probs ({len(y) - 1} prefixes)", dec_err, 1e-3)
        good &= report(f"LM log-probs ({len(y) - 1} prefixes)", lm_err, 1e-3)
        lp_t = torch.log_softmax(a.model.ctc.ctc_lo(enc_tt), -1)
        lp, T = lp_t.numpy(), len(lp_t)
        th = CTCPrefixScoreTH(lp_t[None].clone(), torch.tensor([T]), 0, vm.EOS)
        ys0 = torch.full((1, 1), vm.EOS, dtype=torch.long)
        sc_t, st = th(ys0, None, ids0)
        r0 = np.full((T, 2, 1), vm.LOGZERO, np.float32)
        r0[:, 1, 0] = np.cumsum(lp[:, 0])
        sc_m, r, psi = vm.ctc_prefix(lp, ys0.numpy(), r0, np.zeros(1, np.float32), ids0.numpy())
        cols = np.r_[ids0.numpy()[0], vm.EOS]
        err = maxdiff(sc_m[0, cols], sc_t[0, cols])
        toks = ids0[0, :3]  # step 2: three hyps, scoring ids that include each one's last token
        scorer = a.beam_search.part_scorers["ctc"]
        states = [scorer.select_state(st, 0, int(t)) for t in toks]
        bstate = (torch.stack([s[0] for s in states], 2), torch.stack([s[1] for s in states]), states[0][2], states[0][3])
        ys1 = torch.cat([ys0.repeat(3, 1), toks[:, None]], 1)
        ids1 = ids0.repeat(3, 1)
        sc_t2 = th(ys1, bstate, ids1)[0].numpy()
        sc_m2 = vm.ctc_prefix(lp, ys1.numpy(), r[:, :, 0, :3], psi[0, toks.numpy()], ids1.numpy())[0]
        for b in range(3):
            cols = np.r_[ids1.numpy()[b], vm.EOS]
            err = max(err, maxdiff(sc_m2[b, cols], sc_t2[b, cols]))
        good &= report("CTC prefix scores (2 steps)", err, 1e-3)

    print("4. losses and gradients (clips 1, 2; dropout off)")
    names = [n for n, p in a.model.named_parameters() if n.startswith("encoder")]

    def torch_grads(dtype):  # losses and encoder grads (MLX layout, float64)
        a.model.to(dtype)
        for p in a.model.parameters():
            p.requires_grad_(False)
        params = dict(a.model.named_parameters())
        for n in names:
            params[n].requires_grad_(True)
        hs, hmask = a.model.encoder(xt.to(dtype), mask_t)
        ys_pad = torch.nn.utils.rnn.pad_sequence([torch.tensor(y) for y in labels], batch_first=True, padding_value=-1)
        l_ctc = a.model.ctc(hs, torch.tensor(lens), ys_pad)
        ys_in, ys_out = add_sos_eos(ys_pad, a.model.sos, a.model.eos, -1)
        l_att = a.model.criterion(a.model.decoder(ys_in, target_mask(ys_in, -1), hs, hmask)[0], ys_out)
        (0.1 * l_ctc + 0.9 * l_att).backward()
        g = {n: params[n].grad.double().numpy() for n in names}
        a.model.zero_grad(set_to_none=True)
        for p in a.model.parameters():
            p.requires_grad_(False)
        a.model.float()
        return l_ctc.item(), l_att.item(), {n: v.transpose(PERM[v.ndim]) if v.ndim in PERM else v for n, v in g.items()}

    l_ctc, l_att, g_t = torch_grads(torch.float32)
    *_, g_64 = torch_grads(torch.float64)  # ground truth: torch's own fp32 grads are off by ~2e-3 in blocks 0-2
    params = dict(a.model.named_parameters())
    keys = vm.trainable_encoder(m)
    assert sorted(keys) == sorted(names)
    hs_m = m.encoder(mx.array(x), mask)
    ys_in_m, ys_out_m = vm.pad_ids([[vm.EOS] + y for y in labels], vm.EOS), vm.pad_ids([y + [vm.EOS] for y in labels], -1)
    good &= report("CTC loss (rel)", abs(vm.ctc_loss(m.ctc.ctc_lo(hs_m), lens, labels).item() / l_ctc - 1), 1e-4)
    good &= report("label-smoothing loss (rel)",
                   abs(vm.ls_loss(m.decoder(ys_in_m, hs_m, mask), ys_out_m).item() / l_att - 1), 1e-4)
    loss_m, grads = nn.value_and_grad(m, vm.train_loss)(m, mx.array(x), lens, labels)
    good &= report("total loss (rel)", abs(loss_m.item() / (0.1 * l_ctc + 0.9 * l_att) - 1), 1e-4)
    g_m = {k: np.array(v).astype(np.float64) for k, v in nn.utils.tree_flatten(grads)}
    loss_c, grads = vm.loss_and_grads(m, mx.array(x), lens, labels, frames=16)  # the training path, 8-frame chunks
    g_c = {k: np.array(v).astype(np.float64) for k, v in nn.utils.tree_flatten(grads)}

    def rel(g, ref):
        return math.sqrt(sum(((g[n] - ref[n]) ** 2).sum() for n in names) / sum((ref[n] ** 2).sum() for n in names))
    print(f"  grad norm {math.sqrt(sum((g_64[n] ** 2).sum() for n in names)):.4g}; vs torch fp32: "
          f"MLX {rel(g_m, g_t):.2e}, torch fp64 {rel(g_64, g_t):.2e}")
    good &= report("encoder grads vs torch fp64 (rel)", rel(g_m, g_64), 1e-4)
    good &= report("  same, chunked training step", rel(g_c, g_64), 1e-3)
    good &= report("  chunked step loss (rel)", abs(loss_c.item() / loss_m.item() - 1), 1e-5)
    good &= report("  (torch fp32 vs torch fp64)", rel(g_t, g_64), 1e-2)
    # the loss functions alone, on identical logits, incl. an impossible (too long) label -> zero_infinity
    logits = np.random.default_rng(0).normal(0, 3, (3, 12, vm.ODIM)).astype(np.float32)
    labs, lns = [[5, 5, 7], [9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21], [3, 4]], np.array([12, 12, 7])
    lt = torch.tensor(logits, requires_grad=True)
    ct = torch.nn.CTCLoss(reduction="sum", zero_infinity=True)(
        lt.log_softmax(2).transpose(0, 1), torch.cat([torch.tensor(y) for y in labs]), torch.tensor(lns),
        torch.tensor([len(y) for y in labs])) / 3
    ct.backward()
    cm, cg = mx.value_and_grad(lambda z: vm.ctc_loss(z, lns, labs))(mx.array(logits))
    good &= report("ctc_loss value vs torch (rel)", abs(cm.item() / ct.item() - 1), 1e-5)
    good &= report("ctc_loss d/dlogits (abs)", maxdiff(cg, lt.grad), 1e-5)
    tgt = np.array([[5, 6, 7, -1], [8, 9, 10, 11], [3, -1, -1, -1]])
    lt.grad = None
    lst = a.model.criterion(lt[:, :4].contiguous(), torch.tensor(tgt))
    lst.backward()
    lsm, lsg = mx.value_and_grad(lambda z: vm.ls_loss(z[:, :4], mx.array(tgt)))(mx.array(logits))
    good &= report("ls_loss value vs torch (rel)", abs(lsm.item() / lst.item() - 1), 1e-5)
    good &= report("ls_loss d/dlogits (abs)", maxdiff(lsg, lt.grad), 1e-5)

    print("5. AdamW (bias correction on, wd 0.01, lr 5e-5) + grad clip 5, two steps from the same grads")
    sel = names[:3] + names[-3:]
    ps = [params[n].detach().clone().requires_grad_() for n in sel]
    opt_t = torch.optim.AdamW(ps, lr=5e-5, weight_decay=0.01)
    mp = {n: mx.array(params[n].detach().numpy()) for n in sel}
    opt_m = optim.AdamW(learning_rate=5e-5, weight_decay=0.01, bias_correction=True)
    for scale in (100.0, -0.3):
        gs = [torch.tensor(np.float32(g_t[n].transpose(np.argsort(PERM[g_t[n].ndim])) if g_t[n].ndim in PERM
                                      else g_t[n])) * scale for n in sel]
        for p, g in zip(ps, gs):
            p.grad = g.clone()
        torch.nn.utils.clip_grad_norm_(ps, 5.0)
        opt_t.step()
        gm, _ = optim.clip_grad_norm({n: mx.array(g.numpy()) for n, g in zip(sel, gs)}, 5.0)
        mp = opt_m.apply_gradients(gm, mp)
    good &= report("params after 2 steps (abs)", max(maxdiff(mp[n], p.detach()) for n, p in zip(sel, ps)), 1e-7)
    for p in a.model.parameters():
        p.requires_grad_(False)
    del grads, g_m, g_c, g_t, g_64, params
    a.model.zero_grad(set_to_none=True)
    m.eval()

    order = sorted(np.random.default_rng(0).permutation(len(clips))[:len(clips) * 15 // 100])
    print("6. beam search top-1 on 20 held-out clips")
    same_e2e = same_beam = 0
    for i in order[:20]:
        et = t_encode(clips[i])
        with torch.no_grad():
            top_t = a.beam_search(torch.tensor(et))[0].yseq.tolist()
        em = lip.encode_crops(clips[i])
        same_e2e += vm.beam_search(m, lm, em)[0][0].tolist() == top_t
        same_beam += vm.beam_search(m, lm, mx.array(et))[0][0].tolist() == top_t
    good &= report("top-1 differs, MLX end to end (of 20)", 20 - same_e2e, 0)
    good &= report("top-1 differs, same encoder input", 20 - same_beam, 0)

    print(f"7. CTC greedy text on all {len(clips)} clips (lipread's padded encode)")
    collapse = lambda ids: [i for t, i in enumerate(ids) if i and (t == 0 or i != ids[t - 1])]  # noqa: E731
    diff_text, errs, t0 = 0, [], time.time()
    for i, c in enumerate(clips):
        et = t_encode(c)
        em = np.array(lip.encode_crops(c))
        errs.append(maxdiff(em, et))
        ct = collapse(a.model.ctc.argmax(torch.tensor(et)[None])[0].tolist())
        diff_text += collapse(np.array(mx.argmax(m.ctc.ctc_lo(mx.array(em)), -1)).tolist()) != ct
        if i % 64 == 63:
            print(f"    {i + 1} clips, {time.time() - t0:.0f} s", flush=True)
    errs = np.array(errs)
    print(f"  encoder max abs diff per clip: median {np.median(errs):.1e}, worst clip {errs.argmax()}, "
          f"{(errs > 1e-3).sum()} of {len(errs)} clips over 1e-3")
    good &= report("encoder output, worst clip", errs.max(), 1e-3)
    good &= report("clips with different CTC text", diff_text, 0)
    print("ALL PARITY CHECKS PASSED" if good else "SOME CHECKS FAILED")


def wer_and_timings():
    """Held-out split of finetune.py (rng 0, 15%), lipread's pipeline (padded encode, beam 20 + LM), personal."""
    clips, texts, a, t_encode = setup()
    lip = vm.LipModel(PERSONAL_MLX)
    val = sorted(np.random.default_rng(0).permutation(len(clips))[:len(clips) * 15 // 100])
    ref = " ".join(texts[i] for i in val).upper().split()
    secs = sum(len(clips[i]) for i in val) / 25
    first = lambda ts: next((t for t in ts if t), "")  # noqa: E731  top of LipReader.guesses
    t = {k: 0.0 for k in ("torch_enc_cpu", "torch_enc_mps", "torch_beam", "mlx_enc", "mlx_beam")}
    hyp_t, hyp_m = [], []
    for i in val:
        t0 = time.perf_counter()
        et = t_encode(clips[i])
        t1 = time.perf_counter()
        with torch.no_grad():
            hyps = a.beam_search(torch.tensor(et))
        t["torch_enc_cpu"] += t1 - t0
        t["torch_beam"] += time.perf_counter() - t1
        hyp_t.append(first(lip.text(h.yseq[1:-1].tolist()) for h in hyps))
    lip.guesses(lip.encode_crops(clips[val[0]]))  # warm up
    for i in val:
        t0 = time.perf_counter()
        em = lip.encode_crops(clips[i])
        t1 = time.perf_counter()
        hyp_m.append(first(lip.guesses(em)))
        t["mlx_enc"] += t1 - t0
        t["mlx_beam"] += time.perf_counter() - t1
    a.model.encoder.to("mps")  # lipread's current setup: torch encoder on the Apple GPU
    from pipelines.data.transforms import VideoTransform
    tr = VideoTransform(speed_rate=1)
    for rep in range(2):  # MPS compiles once per input length: report the second (warm) pass
        t["torch_enc_mps"] = 0.0
        for i in val:
            t0 = time.perf_counter()
            with torch.no_grad():
                a.model.encode(tr(torch.tensor(pad8(clips[i]))).to("mps")).cpu()
            t["torch_enc_mps"] += time.perf_counter() - t0
    w_t, w_m = vm.wer(ref, " ".join(hyp_t).upper().split()), vm.wer(ref, " ".join(hyp_m).upper().split())
    print(f"held-out WER, {len(val)} clips ({secs:.0f} s of video): torch {w_t:.2%}, MLX {w_m:.2%}; "
          f"same top-1 on {sum(x == y for x, y in zip(hyp_t, hyp_m))}/{len(val)}")
    for k, v in t.items():
        print(f"  {k:<14} {v:7.1f} s total, {v / len(val) * 1000:6.0f} ms per clip")


if __name__ == "__main__":
    if "--check" in sys.argv:
        check()
    elif "--wer" in sys.argv:
        wer_and_timings()
    else:
        main()

"""Auto-AVSR lip reading (LRS3_V_WER19.1 + its subword LM) in MLX: inference, beam search, training losses.

Weights come from convert_weights.py (mlx_weights/*.safetensors); no torch needed at runtime.
Module and parameter names equal the PyTorch/ESPnet ones, so checkpoints map 1:1.
"""
import math
import os
import threading
from functools import lru_cache, partial

import mlx.core as mx
import mlx.nn as nn
import numpy as np
from mlx.utils import tree_map

HERE = os.path.dirname(os.path.abspath(__file__))
WEIGHTS = os.path.join(HERE, "mlx_weights")
TOKENS = os.path.join(HERE, "vsr", "pipelines", "tokens")
ODIM = 5049  # blank 0, unk 1, ..., sos = eos = 5048
EOS = ODIM - 1
D = 768
LOGZERO = np.float32(-1e10)  # ESPnet's CTC prefix scorer "log 0"
FMIN = float(np.finfo(np.float32).min)  # ESPnet's attention mask fill


# ---------------------------------------------------------------- layers

@partial(mx.compile, shapeless=True)
def _bn(x, w, b, mean, var):  # one fused kernel: keeps no full-size intermediates for the backward pass
    s = w * mx.rsqrt(var + 1e-5)  # every BatchNorm here uses torch's default eps
    return x * s + (b - mean * s)


class BatchNorm(nn.Module):
    """Eval-only BatchNorm over the last (channel) axis, any rank: always the frozen pre-training statistics.
    (mlx.nn.BatchNorm rejects 5-D input, and fine-tuning must never update the statistics anyway.)"""

    def __init__(self, n):
        super().__init__()
        self.weight, self.bias = mx.ones((n,)), mx.zeros((n,))
        self.running_mean, self.running_var = mx.zeros((n,)), mx.ones((n,))

    def __call__(self, x):
        return _bn(x, self.weight, self.bias, self.running_mean, self.running_var)


def LN(n, eps=1e-12):
    return nn.LayerNorm(n, eps=eps)


@lru_cache(None)
def _div(d):
    return np.exp(np.arange(0, d, 2, dtype=np.float32) * np.float32(-(math.log(10000.0) / d)))


def _sincos(pos, d):
    a = pos[:, None].astype(np.float32) * _div(d)
    pe = np.empty((len(pos), d), np.float32)
    pe[:, 0::2], pe[:, 1::2] = np.sin(a), np.cos(a)
    return mx.array(pe)[None]


@lru_cache(64)
def rel_pos(T, d=D):
    """ESPnet RelPositionalEncoding: (1, 2T-1, d), row j is relative distance T-1-j."""
    return _sincos(np.arange(T - 1, -T, -1), d)


@lru_cache(64)
def abs_pos(L, d=D):
    return _sincos(np.arange(L), d)


def rel_shift(x):
    """(B, h, T, 2T-1) -> (B, h, T, T) with out[i, j] = x[i, T-1-i+j] (ESPnet's pad-and-reshape trick)."""
    B, h, T, P = x.shape
    x = mx.concatenate([mx.zeros((B, h, T, 1), x.dtype), x], -1).reshape(B, h, P + 1, T)
    return x[:, :, 1:].reshape(B, h, T, P)[..., : P // 2 + 1]


class MHA(nn.Module):
    """ESPnet MultiHeadedAttention; rel=True is RelPositionMultiHeadedAttention (new style, zero_triu off)."""

    def __init__(self, d=D, h=12, dropout=0.1, rel=False):
        super().__init__()
        self.h, self.dk = h, d // h
        self.linear_q, self.linear_k = nn.Linear(d, d), nn.Linear(d, d)
        self.linear_v, self.linear_out = nn.Linear(d, d), nn.Linear(d, d)
        if rel:
            self.linear_pos = nn.Linear(d, d, bias=False)
            self.pos_bias_u, self.pos_bias_v = mx.zeros((h, self.dk)), mx.zeros((h, self.dk))
        self.dropout = nn.Dropout(dropout)

    def heads(self, x):  # (B, T, d) -> (B, T, h, dk)
        return x.reshape(x.shape[0], x.shape[1], self.h, self.dk)

    def __call__(self, q, k, v, mask=None, pos=None):
        """mask: bool (B or 1, 1 or Tq, Tk), True = attend. Key/value batch may be 1 and broadcast."""
        q = self.heads(self.linear_q(q))
        k = self.heads(self.linear_k(k)).transpose(0, 2, 3, 1)  # (B, h, dk, Tk)
        v = self.heads(self.linear_v(v)).transpose(0, 2, 1, 3)
        if pos is None:
            scores = (q.transpose(0, 2, 1, 3) @ k) / math.sqrt(self.dk)
        else:
            p = self.heads(self.linear_pos(pos)).transpose(0, 2, 3, 1)
            ac = (q + self.pos_bias_u).transpose(0, 2, 1, 3) @ k
            bd = rel_shift((q + self.pos_bias_v).transpose(0, 2, 1, 3) @ p)
            scores = (ac + bd) / math.sqrt(self.dk)
        if mask is None:
            attn = mx.softmax(scores, axis=-1)
        else:
            m = mask[:, None]
            attn = mx.where(m, mx.softmax(mx.where(m, scores, FMIN), axis=-1), 0.0)
        x = self.dropout(attn) @ v  # (B, h, Tq, dk)
        return self.linear_out(x.transpose(0, 2, 1, 3).reshape(x.shape[0], x.shape[2], -1))


class FFN(nn.Module):
    def __init__(self, d, units, dropout=0.1):
        super().__init__()
        self.w_1, self.w_2 = nn.Linear(d, units), nn.Linear(units, d)
        self.dropout = nn.Dropout(dropout)

    def __call__(self, x):
        return self.w_2(self.dropout(nn.relu(self.w_1(x))))


# ---------------------------------------------------------------- visual front end

POOL = nn.MaxPool2d(3, 2, 1)  # MaxPool3d (1,3,3)/(1,2,2)/(0,1,1), applied per frame


class BasicBlock(nn.Module):
    def __init__(self, cin, cout, stride):
        super().__init__()
        self.conv1, self.bn1 = nn.Conv2d(cin, cout, 3, stride, 1, bias=False), BatchNorm(cout)
        self.conv2, self.bn2 = nn.Conv2d(cout, cout, 3, 1, 1, bias=False), BatchNorm(cout)
        if stride != 1 or cin != cout:
            self.downsample = [nn.Conv2d(cin, cout, 1, stride, bias=False), BatchNorm(cout)]

    def __call__(self, x):
        out = self.bn2(self.conv2(nn.silu(self.bn1(self.conv1(x)))))
        res = self.downsample[1](self.downsample[0](x)) if "downsample" in self else x
        return nn.silu(out + res)  # swish after the residual add


class ResNet(nn.Module):
    def __init__(self):
        super().__init__()
        cin = 64
        for i, c in enumerate((64, 128, 256, 512), 1):
            setattr(self, f"layer{i}", [BasicBlock(cin, c, 1 if i == 1 else 2), BasicBlock(c, c, 1)])
            cin = c

    def __call__(self, x):
        for i in range(1, 5):
            for block in getattr(self, f"layer{i}"):
                x = block(x)
        return x.mean((1, 2))


class Conv3dResNet(nn.Module):
    def __init__(self):
        super().__init__()
        self.frontend3D = [nn.Conv3d(1, 64, (5, 7, 7), (1, 2, 2), (2, 3, 3), bias=False), BatchNorm(64)]
        self.trunk = ResNet()

    def __call__(self, x, tpad=2):  # (B, T, 88, 88, 1) -> (B, T, 512); tpad=0: x already has 2 frames each side
        x = mx.pad(x, [(0, 0), (tpad, tpad), (0, 0), (0, 0), (0, 0)]) if tpad else x
        B, T = x.shape[0], x.shape[1] - 4
        # The 5x7x7 Conv3d has one input channel, so it equals a 7x7 Conv2d over 5 stacked frames: ~5x faster in MLX.
        frames = mx.concatenate([x[:, dt:dt + T] for dt in range(5)], -1).reshape(B * T, *x.shape[2:4], 5)
        w = self.frontend3D[0].weight[..., 0].transpose(0, 2, 3, 1)  # (64, 5, 7, 7) -> (64, 7, 7, 5)
        x = nn.silu(self.frontend3D[1](mx.conv2d(frames, w, 2, 3)))
        return self.trunk(POOL(x)).reshape(B, T, -1)


# ---------------------------------------------------------------- conformer encoder

class ConvModule(nn.Module):
    def __init__(self, d=D, k=31):
        super().__init__()
        self.pointwise_cov1 = nn.Conv1d(d, 2 * d, 1)  # (sic) the checkpoint's key name
        self.depthwise_conv = nn.Conv1d(d, d, k, padding=(k - 1) // 2, groups=d)
        self.norm = BatchNorm(d)
        self.pointwise_cov2 = nn.Conv1d(d, d, 1)

    def __call__(self, x):  # (B, T, d)
        a, b = mx.split(self.pointwise_cov1(x), 2, axis=-1)
        return self.pointwise_cov2(nn.silu(self.norm(self.depthwise_conv(a * mx.sigmoid(b)))))


class ConformerLayer(nn.Module):
    def __init__(self, dropout=0.1):
        super().__init__()
        self.self_attn = MHA(rel=True, dropout=dropout)
        self.feed_forward, self.feed_forward_macaron = FFN(D, 3072, dropout), FFN(D, 3072, dropout)
        self.conv_module = ConvModule()
        self.norm_ff, self.norm_mha, self.norm_ff_macaron = LN(D), LN(D), LN(D)
        self.norm_conv, self.norm_final = LN(D), LN(D)
        self.dropout = nn.Dropout(dropout)

    def __call__(self, x, pos, mask):
        x = x + 0.5 * self.dropout(self.feed_forward_macaron(self.norm_ff_macaron(x)))
        h = self.norm_mha(x)
        x = x + self.dropout(self.self_attn(h, h, h, mask, pos))
        x = x + self.dropout(self.conv_module(self.norm_conv(x)))
        x = x + 0.5 * self.dropout(self.feed_forward(self.norm_ff(x)))
        return self.norm_final(x)


class Encoder(nn.Module):
    def __init__(self):
        super().__init__()
        self.frontend = Conv3dResNet()
        self.embed = [nn.Linear(512, D)]
        self.encoders = [ConformerLayer() for _ in range(12)]
        self.after_norm = LN(D)
        self.dropout = nn.Dropout(0.1)

    def __call__(self, x, mask=None):
        """x: (B, T, 88, 88, 1) normalized crops, or (B, T, 512) front-end features; mask: bool (B, 1, T) or None.
        No temporal subsampling."""
        x = self.embed[0](x if x.ndim == 3 else self.frontend(x)) * math.sqrt(D)
        x, pos = self.dropout(x), self.dropout(rel_pos(x.shape[1]))
        for layer in self.encoders:
            x = layer(x, pos, mask)
        return self.after_norm(x)


# ---------------------------------------------------------------- decoder, CTC, LM

class DecoderLayer(nn.Module):
    def __init__(self, dropout=0.1):
        super().__init__()
        self.self_attn, self.src_attn = MHA(dropout=dropout), MHA(dropout=dropout)
        self.feed_forward = FFN(D, 3072, dropout)
        self.norm1, self.norm2, self.norm3 = LN(D), LN(D), LN(D)
        self.dropout = nn.Dropout(dropout)

    def __call__(self, x, tgt_mask, mem, mem_mask):
        h = self.norm1(x)
        x = x + self.dropout(self.self_attn(h, h, h, tgt_mask))
        x = x + self.dropout(self.src_attn(self.norm2(x), mem, mem, mem_mask))
        return x + self.dropout(self.feed_forward(self.norm3(x)))


def causal(L):
    return mx.tril(mx.ones((L, L), mx.bool_))[None]


class Decoder(nn.Module):
    def __init__(self):
        super().__init__()
        self.embed = [nn.Embedding(ODIM, D)]
        self.decoders = [DecoderLayer() for _ in range(6)]
        self.after_norm = LN(D)
        self.output_layer = nn.Linear(D, ODIM)
        self.dropout = nn.Dropout(0.1)

    def __call__(self, ys, mem, mem_mask=None):
        """ys (B, L) ids -> logits (B, L, V). mem batch may be 1."""
        x = self.dropout(self.embed[0](ys) * math.sqrt(D) + abs_pos(ys.shape[1]))
        for layer in self.decoders:
            x = layer(x, causal(ys.shape[1]), mem, mem_mask)
        return self.output_layer(self.after_norm(x))


class CTC(nn.Module):
    def __init__(self):
        super().__init__()
        self.ctc_lo = nn.Linear(D, ODIM)
        self.dropout = nn.Dropout(0.1)  # training only, before ctc_lo


class AVSR(nn.Module):
    def __init__(self):
        super().__init__()
        self.encoder, self.decoder, self.ctc = Encoder(), Decoder(), CTC()


class LMLayer(nn.Module):
    def __init__(self):
        super().__init__()
        self.self_attn = MHA(512, 8, dropout=0.0)
        self.feed_forward = FFN(512, 2048, dropout=0.0)
        self.norm_ff, self.norm_mha = LN(512), LN(512)

    def __call__(self, x, mask):
        h = self.norm_mha(x)
        x = x + self.self_attn(h, h, h, mask)
        return x + self.feed_forward(self.norm_ff(x))


class LMEncoder(nn.Module):
    def __init__(self):
        super().__init__()
        self.embed = [nn.Linear(128, 512), LN(512, eps=1e-5)]  # torch.nn.LayerNorm default eps here
        self.encoders = [LMLayer() for _ in range(16)]
        self.after_norm = LN(512)


class TransformerLM(nn.Module):
    """ESPnet TransformerLM, no positional encoding."""

    def __init__(self):
        super().__init__()
        self.embed = nn.Embedding(ODIM, 128)
        self.encoder = LMEncoder()
        self.decoder = nn.Linear(512, ODIM)

    def __call__(self, ys):
        """ys (n, L) -> next-token log-probs (n, V)."""
        e = self.encoder
        x = nn.relu(e.embed[1](e.embed[0](self.embed(ys))))
        mask = (ys != 0)[:, None, :] & causal(ys.shape[1])
        for layer in e.encoders:
            x = layer(x, mask)
        return nn.log_softmax(self.decoder(e.after_norm(x[:, -1])), axis=-1)


def load_avsr(personal=None):
    """The base model (strict), then optionally fine-tuned encoder weights on top (fp16 file, cast to fp32)."""
    model = AVSR()
    model.load_weights(os.path.join(WEIGHTS, "model.safetensors"))
    if personal:
        model.load_weights([(k, v.astype(mx.float32)) for k, v in mx.load(personal).items()], strict=False)
    model.eval()
    mx.eval(model.parameters())
    return model


def load_lm():
    lm = TransformerLM()
    lm.load_weights(os.path.join(WEIGHTS, "lm.safetensors"))
    lm.eval()
    mx.eval(lm.parameters())
    return lm


def trainable_encoder(model):
    """Freeze all but the encoder's weights (BatchNorm statistics stay frozen); returns the trainable keys."""
    model.freeze()
    model.encoder.unfreeze()
    model.encoder.freeze(keys=["running_mean", "running_var"], strict=False)
    keys = [k for k, _ in nn.utils.tree_flatten(model.trainable_parameters())]
    assert len(keys) == 508 and all(k.startswith("encoder.") for k in keys), len(keys)
    return keys


# ---------------------------------------------------------------- beam search (ESPnet BatchBeamSearch, one pass)

def ctc_prefix(lp, ys, r_prev, s_prev, ids):
    """ESPnet CTCPrefixScoreTH for n hyps over their pre-beam ids. lp (T, V); ys (n, L+1) with sos;
    r_prev (T, 2, n); s_prev (n,); ids (n, k). Returns (scores (n, V), r (T, 2, n, k), psi (n, V))."""
    T, L = len(lp), ys.shape[1] - 1
    xs, xb = lp[:, ids], lp[:, 0, None, None]  # (T, n, k), (T, 1, 1)
    r = np.full((T, 2) + ids.shape, LOGZERO, np.float32)
    if L == 0:
        r[0, 0] = xs[0]
    r_sum = np.logaddexp(r_prev[:, 0], r_prev[:, 1])  # (T, n)
    phi = np.where(ids == ys[:, -1:], r_prev[:, 1, :, None], r_sum[:, :, None])
    start = max(L, 1)
    for t in range(start, T):
        r[t, 0] = np.logaddexp(r[t - 1, 0], phi[t - 1]) + xs[t]
        r[t, 1] = np.logaddexp(r[t - 1, 0], r[t - 1, 1]) + xb[t]
    phix = np.concatenate([phi[:1], phi[:-1]]) + xs
    psi_s = np.logaddexp.reduce(np.concatenate([phix[start:], r[start - 1, 0][None]]), 0)
    psi = np.full((len(ys), lp.shape[1]), LOGZERO, np.float32)
    np.put_along_axis(psi, ids, psi_s, 1)
    psi[:, EOS] = r_sum[-1]
    psi[:, 0] = LOGZERO
    return psi - s_prev[:, None], r, psi


def end_detect(ended, i, M=3, d_end=np.log(1 * np.exp(-10))):
    """ESPnet e2e_asr_common.end_detect over (yseq, score) pairs."""
    if not ended:
        return False
    best = max(s for _, s in ended)
    count = 0
    for m in range(M):
        same = [s for y, s in ended if len(y) == i - m]
        if same and max(same) - best < d_end:
            count += 1
    return count == M


def _attend(att, q, k, v):
    """One new query per hyp against cached keys/values: q (n, h, 1, dk), k/v (n or 1, h, L, dk) -> (n, 1, d)."""
    x = mx.softmax((q @ k.transpose(0, 1, 3, 2)) / math.sqrt(att.dk), axis=-1) @ v
    return att.linear_out(x.transpose(0, 2, 1, 3).reshape(x.shape[0], 1, -1))


def _qkv(att, h):  # (n, 1, d) -> three (n, h, 1, dk)
    return [att.heads(f(h)).transpose(0, 2, 1, 3) for f in (att.linear_q, att.linear_k, att.linear_v)]


def _cat(cache, k):
    return k if cache is None else mx.concatenate([cache, k], 2)


class Steps:
    """Incremental decoder + LM for the beam search: one new position per call with per-layer key/value caches.
    Same math as the full-prefix forward (causal attention), without recomputing the prefix every step."""

    def __init__(self, model, lm, enc):
        self.dec, self.lm = model.decoder, lm
        mem = enc[None]
        self.mem_kv = [[a.heads(f(mem)).transpose(0, 2, 1, 3) for f in (a.linear_k, a.linear_v)]
                       for a in (layer.src_attn for layer in self.dec.decoders)]
        self.dc = [[None, None] for _ in self.dec.decoders]
        self.lc = [[None, None] for _ in lm.encoder.encoders]

    def __call__(self, y, i):
        """y (n,) ids at position i -> decoder and LM next-token log-probs (n, V) each."""
        d = self.dec
        x = d.embed[0](y)[:, None] * math.sqrt(D) + abs_pos(i + 1)[:, i:]
        for layer, c, (mk, mv) in zip(d.decoders, self.dc, self.mem_kv):
            q, k, v = _qkv(layer.self_attn, layer.norm1(x))
            c[0], c[1] = _cat(c[0], k), _cat(c[1], v)
            x = x + _attend(layer.self_attn, q, c[0], c[1])
            a = layer.src_attn
            x = x + _attend(a, a.heads(a.linear_q(layer.norm2(x))).transpose(0, 2, 1, 3), mk, mv)
            x = x + layer.feed_forward(layer.norm3(x))
        dec = nn.log_softmax(d.output_layer(d.after_norm(x[:, 0])), axis=-1)
        e = self.lm.encoder  # LM key mask (ys != 0) is all-true here: hyps never contain the blank
        x = nn.relu(e.embed[1](e.embed[0](self.lm.embed(y))))[:, None]
        for layer, c in zip(e.encoders, self.lc):
            q, k, v = _qkv(layer.self_attn, layer.norm_mha(x))
            c[0], c[1] = _cat(c[0], k), _cat(c[1], v)
            x = x + _attend(layer.self_attn, q, c[0], c[1])
            x = x + layer.feed_forward(layer.norm_ff(x))
        return dec, nn.log_softmax(self.lm.decoder(e.after_norm(x[:, 0])), axis=-1)

    def select(self, idx):
        idx = mx.array(idx)
        for c in self.dc + self.lc:
            c[0], c[1] = c[0][idx], c[1][idx]


def beam_search(model, lm, enc, beam=20, ctc_weight=0.1, lm_weight=0.3, pre_beam=30):
    """enc (T, 768) -> all ended hypotheses [(yseq with sos/eos, score)], best first, like AVSR.beam_search."""
    T = enc.shape[0]
    step = Steps(model, lm, enc)
    lp = np.array(nn.log_softmax(model.ctc.ctc_lo(enc), axis=-1))
    ys = np.full((1, 1), EOS, np.int64)
    score = np.zeros(1, np.float32)
    r_prev = np.full((T, 2, 1), LOGZERO, np.float32)
    r_prev[:, 1, 0] = np.cumsum(lp[:, 0])
    s_prev = np.zeros(1, np.float32)
    ended = []
    for i in range(T):
        dec, lmp = step(mx.array(ys[:, -1].astype(np.int32)), i)
        mx.eval(dec, lmp)
        dec, lmp = np.array(dec), np.array(lmp)
        w = (1 - ctc_weight) * dec
        w += lm_weight * lmp
        ids = np.argpartition(-dec, pre_beam - 1, axis=1)[:, :pre_beam]
        sc, r, psi = ctc_prefix(lp, ys, r_prev, s_prev, ids)
        w += ctc_weight * sc
        w += score[:, None]
        flat = w.ravel()
        top = np.argpartition(-flat, beam - 1)[:beam]
        top = top[np.argsort(-flat[top], kind="stable")]
        prev, tok = top // ODIM, top % ODIM
        hit = ids[prev] == tok[:, None]
        col = np.where(hit.any(1), hit.argmax(1), 0)
        ys, score = np.concatenate([ys[prev], tok[:, None]], 1), flat[top]
        r_prev, s_prev = r[:, :, prev, col], psi[prev, tok]
        if i == T - 1:
            ys = np.concatenate([ys, np.full((len(ys), 1), EOS)], 1)
        done = ys[:, -1] == EOS
        ended += [(ys[b], float(score[b])) for b in np.flatnonzero(done)]
        ys, score, r_prev, s_prev = ys[~done], score[~done], r_prev[:, :, ~done], s_prev[~done]
        step.select(prev[~done])
        if end_detect(ended, i) or not len(ys):
            break
    return sorted(ended, key=lambda h: h[1], reverse=True)


# ---------------------------------------------------------------- training losses

def ctc_loss(logits, lens, labels):
    """torch.nn.CTCLoss(reduction="sum", zero_infinity=True) / B on raw logits (B, T, V).
    lens: (B,) frame counts; labels: list of non-empty token id lists."""
    B, T, _ = logits.shape
    lp = nn.log_softmax(logits, axis=-1)
    L = max(len(y) for y in labels)
    S = 2 * L + 1
    ext = np.zeros((B, S), np.int32)  # blank, y1, blank, y2, ..., blank
    for b, y in enumerate(labels):
        ext[b, 1:2 * len(y):2] = y
    skip = np.zeros((B, S), bool)
    skip[:, 3::2] = ext[:, 3::2] != ext[:, 1:-2:2]  # may jump the blank between two different tokens
    lpe = mx.take_along_axis(lp, mx.array(ext)[:, None, :], axis=2)  # (B, T, S)
    NEG = -1e30
    skip, lens = mx.array(skip), mx.array(np.asarray(lens))
    alpha = mx.where(mx.arange(S) < 2, lpe[:, 0], NEG)
    pad1, pad2 = mx.full((B, 1), NEG), mx.full((B, 2), NEG)
    for t in range(1, T):
        a1 = mx.concatenate([pad1, alpha[:, :-1]], 1)
        a2 = mx.where(skip, mx.concatenate([pad2, alpha[:, :-2]], 1), NEG)
        new = mx.logaddexp(mx.logaddexp(alpha, a1), a2) + lpe[:, t]
        alpha = mx.where((t < lens)[:, None], new, alpha)
    last = mx.array(np.array([2 * len(y) for y in labels])[:, None])
    nll = -mx.logaddexp(mx.take_along_axis(alpha, last, 1), mx.take_along_axis(alpha, last - 1, 1))[:, 0]
    return mx.where(nll > 1e29, 0.0, nll).sum() / B  # zero_infinity


def ls_loss(logits, target, smoothing=0.1):
    """ESPnet LabelSmoothingLoss (KL to the smoothed target, ignore -1, divided by batch size)."""
    B, _, V = logits.shape
    lp = nn.log_softmax(logits, axis=-1)
    ok = target != -1
    lp_t = mx.take_along_axis(lp, mx.where(ok, target, 0)[..., None], -1)[..., 0]
    conf, s = 1 - smoothing, smoothing / (V - 1)
    const = conf * math.log(conf) + (V - 1) * s * math.log(s)  # sum of q log q
    kl = const - s * (lp.sum(-1) - lp_t) - conf * lp_t
    return mx.where(ok, kl, 0.0).sum() / B


def pad_ids(seqs, value):
    out = np.full((len(seqs), max(map(len, seqs))), value, np.int32)
    for b, s in enumerate(seqs):
        out[b, :len(s)] = s
    return mx.array(out)


def train_loss(model, x, lens, labels):
    """Auto-AVSR's 0.1 CTC + 0.9 attention loss. x: (B, T, 88, 88, 1) zero-padded crops (or (B, T, 512) front-end
    features); lens (B,); labels: token id lists."""
    mask = (mx.arange(x.shape[1])[None] < mx.array(np.asarray(lens))[:, None])[:, None]
    hs = model.encoder(x, mask)
    l_ctc = ctc_loss(model.ctc.ctc_lo(model.ctc.dropout(hs)), lens, labels)
    ys_in, ys_out = pad_ids([[EOS] + y for y in labels], EOS), pad_ids([y + [EOS] for y in labels], -1)
    return 0.1 * l_ctc + 0.9 * ls_loss(model.decoder(ys_in, hs, mask), ys_out)


def loss_and_grads(model, x, lens, labels, frames=32):
    """train_loss and its gradients for the model's trainable parameters, like nn.value_and_grad, but the visual
    front end (over 30 MB per frame in its backward pass) runs in chunks of ~`frames` frames, each evaluated on its
    own, so memory stays bounded. Exact: time is zero-padded once, so each chunk's Conv3d sees its true neighbours."""
    fe, (B, T) = model.encoder.frontend, x.shape[:2]
    k = max(1, frames // B)
    xp = mx.pad(x, [(0, 0), (2, 2), (0, 0), (0, 0), (0, 0)])
    parts = [(t, xp[:, t:t + k + 4]) for t in range(0, T, k)]
    feats = mx.concatenate([fe(c, 0) for _, c in parts], 1)
    mx.eval(feats)

    def rest(params, feats):
        model.update(params)
        return train_loss(model, feats, lens, labels)
    params = model.trainable_parameters()
    loss, (grads, d_feats) = mx.value_and_grad(rest, argnums=(0, 1))(params, feats)
    mx.eval(loss, grads, d_feats)
    model.update(params)
    fe_grad = nn.value_and_grad(fe, lambda c, w: (fe(c, 0) * w).sum())
    acc = grads["encoder"]["frontend"]  # zeros so far
    for t, c in parts:
        acc = tree_map(mx.add, acc, fe_grad(c, d_feats[:, t:t + k])[1])
        mx.eval(acc)
    grads["encoder"]["frontend"] = acc
    return loss, grads


def wer(ref, hyp):
    d = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, d[0] = d[0], i
        for j, h in enumerate(hyp, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (r != h))
    return d[-1] / max(len(ref), 1)


def normalize(crops):
    """(T, H, W) uint8 mouth crops -> (T, 88, 88, 1) float32, like VideoTransform (center crop, 0.421/0.165)."""
    h, w = crops.shape[1:3]
    t, l = int(round((h - 88) / 2.0)), int(round((w - 88) / 2.0))
    return ((crops[:, t:t + 88, l:l + 88].astype(np.float32) / 255.0 - 0.421) / 0.165)[..., None]


# ---------------------------------------------------------------- LipReader's model API

class LipModel:
    """The model half of lipread.LipReader: encode_crops, ctc, text, collapse, guesses, word_frames, align.
    Encoder outputs are mx.arrays (T, 768). One lock around all MLX work (live + finisher threads)."""

    def __init__(self, personal=None, beam=20):
        import sentencepiece
        self.model, self.lm, self.beam = load_avsr(personal), load_lm(), beam
        units = [line.split()[0] for line in open(os.path.join(TOKENS, "unigram5000_units.txt")).read().splitlines()]
        self.token_list = ["<blank>"] + units + ["<eos>"]
        self.tok = {t: i for i, t in enumerate(self.token_list)}
        self.sp = sentencepiece.SentencePieceProcessor(model_file=os.path.join(TOKENS, "unigram5000.model"))
        self.lock = threading.RLock()

    def encode_crops(self, crops):
        n = len(crops)
        # Pad to a multiple of 8 frames by repeating the last one, like the PyTorch path (outputs match it).
        crops = np.concatenate([crops, crops[-1:].repeat(-n % 8, 0)])
        with self.lock:
            enc = self.model.encoder(mx.array(normalize(crops))[None])[0, :n]
            mx.eval(enc)
        return enc

    def log_probs(self, enc):
        with self.lock:
            return np.array(nn.log_softmax(self.model.ctc.ctc_lo(enc), axis=-1))

    def ctc(self, enc):
        """Fast per-frame token guesses (0 = no speech). Stays blank on a still face, so it also finds pauses."""
        with self.lock:
            return mx.argmax(self.model.ctc.ctc_lo(enc), axis=-1).tolist()

    def text(self, ids):
        return "".join(self.token_list[i] for i in ids).replace("▁", " ").strip().lower()

    def collapse(self, ids):
        """CTC per-frame ids -> text: merge repeats, drop blanks."""
        return self.text([i for t, i in enumerate(ids) if i and (t == 0 or i != ids[t - 1])])

    def guesses(self, enc):
        """Full beam search with the language model. Returns distinct guesses, best first."""
        with self.lock:
            hyps = beam_search(self.model, self.lm, enc, self.beam)
        return list(dict.fromkeys(t for t in (self.text(y[1:-1].tolist()) for y, _ in hyps) if t))

    def align(self, enc, text):
        """CTC forced alignment of a known sentence: frame cut points [start, between each word..., end]."""
        frames = self.word_frames(enc, text)
        if frames is None:
            return None
        mids = [(f[-1] + g[0] + 1) // 2 for f, g in zip(frames, frames[1:])]
        return np.array([frames[0][0], *mids, frames[-1][-1] + 1])

    def word_frames(self, enc, text):
        """CTC forced alignment of a known sentence: the frames where each word's tokens fire."""
        pieces = self.sp.encode(text.upper(), out_type=str)
        ids = [self.tok.get(p, 1) for p in pieces]
        word = np.cumsum([p.startswith("▁") for p in pieces]) - 1  # word index of each token
        if enc is None or len(enc) < len(ids) or word[-1] + 1 != len(text.split()):
            return None
        lp = self.log_probs(enc)
        labels = np.zeros(2 * len(ids) + 1, int)  # blank, token, blank, token, ..., blank
        labels[1::2] = ids
        skip = np.zeros(len(labels), bool)
        skip[3::2] = labels[3::2] != labels[1:-2:2]  # may jump the blank between two different tokens
        dp, back = np.full(len(labels), -np.inf), np.zeros((len(lp), len(labels)), np.int8)
        dp[:2] = lp[0, labels[:2]]
        for t in range(1, len(lp)):  # Viterbi: stay, advance one state, or skip a blank
            cand = np.stack([dp, np.r_[-np.inf, dp[:-1]], np.where(skip, np.r_[-np.inf, -np.inf, dp[:-2]], -np.inf)])
            back[t], dp = cand.argmax(0), cand.max(0) + lp[t, labels]
        if not np.isfinite(dp[-2:]).any():
            return None
        s, tok_at = len(labels) - 1 if dp[-1] >= dp[-2] else len(labels) - 2, np.empty(len(lp), int)
        for t in range(len(lp) - 1, -1, -1):
            tok_at[t] = s // 2 if s % 2 else -1
            s -= back[t, s]
        return [np.flatnonzero((tok_at >= 0) & (word[np.maximum(tok_at, 0)] == w)) for w in range(word[-1] + 1)]

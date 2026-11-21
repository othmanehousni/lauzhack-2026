"""Auto-AVSR video-only lip reading in MLX.

Mouth crops (T, 88, 88) -> ResNet-18 front-end -> 12-layer Conformer -> joint CTC/attention beam search with a
subword Transformer language model. Weights come from convert.py with the batch norms already folded.

  uv run lipread/model.py     self-check of the vectorised CTC prefix scorer
"""

import math
from pathlib import Path

import mlx.core as mx
import mlx.nn as nn
import numpy as np

DIM, HEADS, ENC_LAYERS, DEC_LAYERS, LM_LAYERS, LM_HEADS = 768, 12, 12, 6, 16, 8
MEAN, STD = 0.421, 0.165
pool = nn.MaxPool2d(3, 2, 1)

Weights = dict[str, mx.array]
Cache = list[tuple[mx.array, mx.array]]  # per layer: attention keys and values, (B, heads, L, dim / heads)


def linear(w: Weights, p: str, x: mx.array) -> mx.array:
    return x @ w[p + ".weight"].T + w[p + ".bias"]


def norm(w: Weights, p: str, x: mx.array, eps: float = 1e-12) -> mx.array:
    return mx.fast.layer_norm(x, w[p + ".weight"], w[p + ".bias"], eps)


def feed_forward(w: Weights, p: str, x: mx.array) -> mx.array:
    return linear(w, p + ".w_2", nn.relu(linear(w, p + ".w_1", x)))


def conv(w: Weights, p: str, x: mx.array, stride: int, padding: int) -> mx.array:
    return mx.conv2d(x, w[p + ".weight"], stride, padding) + w[p + ".bias"]


def split_heads(x: mx.array, heads: int) -> mx.array:
    return x.reshape(*x.shape[:-1], heads, -1).swapaxes(-2, -3)


def merge_heads(x: mx.array) -> mx.array:
    x = x.swapaxes(-2, -3)
    return x.reshape(*x.shape[:-2], -1)


def log_softmax(x: mx.array) -> mx.array:
    x = x.astype(mx.float32)
    return x - mx.logsumexp(x, axis=-1, keepdims=True)


def sinusoid(positions: mx.array, dim: int) -> mx.array:
    angle = positions.astype(mx.float32)[:, None] * mx.exp(mx.arange(0, dim, 2, dtype=mx.float32) * -(math.log(10000.0) / dim))
    return mx.stack([mx.sin(angle), mx.cos(angle)], axis=-1).reshape(-1, dim)


def frontend(w: Weights, frames: mx.array) -> mx.array:
    """Normalised mouth crops (T, 88, 88) -> (T, 512). The output for frame t depends on frames t-2..t+2 only."""
    T = frames.shape[0]
    padded = mx.pad(frames, [(2, 2), (0, 0), (0, 0)])
    x = mx.stack([padded[i:i + T] for i in range(5)], axis=-1)
    x = pool(nn.silu(conv(w, "stem", x, 2, 3)))
    for layer in range(1, 5):
        for block in range(2):
            p, stride = f"trunk.{layer}.{block}", 2 if layer > 1 and block == 0 else 1
            y = conv(w, p + ".conv2", nn.silu(conv(w, p + ".conv1", x, stride, 1)), 1, 1)
            if p + ".downsample.weight" in w:
                x = conv(w, p + ".downsample", x, stride, 0)
            x = nn.silu(x + y)
    return x.mean(axis=(1, 2))


def rel_attention(w: Weights, p: str, x: mx.array, pos: mx.array) -> mx.array:
    T = x.shape[0]
    q, k, v = (split_heads(linear(w, f"{p}.linear_{n}", x), HEADS) for n in "qkv")
    pe = split_heads(pos @ w[p + ".linear_pos.weight"].T, HEADS)
    content = (q + w[p + ".pos_bias_u"][:, None]) @ k.swapaxes(-1, -2)
    position = (q + w[p + ".pos_bias_v"][:, None]) @ pe.swapaxes(-1, -2)
    # Transformer-XL shift: column j of row i becomes relative offset i - j.
    position = mx.pad(position, [(0, 0), (0, 0), (1, 0)]).reshape(HEADS, 2 * T, T)[:, 1:].reshape(HEADS, T, 2 * T - 1)[:, :, :T]
    scores = (content + position) / math.sqrt(DIM // HEADS)
    return linear(w, p + ".linear_out", merge_heads(mx.softmax(scores, axis=-1) @ v))


def conv_module(w: Weights, p: str, x: mx.array) -> mx.array:
    a, b = mx.split(linear(w, p + ".pointwise_cov1", x), 2, axis=-1)
    x = mx.conv1d((a * mx.sigmoid(b))[None], w[p + ".depthwise_conv.weight"], padding=15, groups=DIM)[0]
    return linear(w, p + ".pointwise_cov2", nn.silu(x + w[p + ".depthwise_conv.bias"]))


def conformer(w: Weights, feats: mx.array) -> mx.array:
    """Front-end features (T, 512) -> encoder output (T, 768). Full attention over the whole clip."""
    T = feats.shape[0]
    x = linear(w, "encoder.embed", feats) * math.sqrt(DIM)
    pos = sinusoid(mx.arange(T - 1, -T, -1), DIM).astype(x.dtype)
    for i in range(ENC_LAYERS):
        p = f"encoder.encoders.{i}"
        x = x + 0.5 * feed_forward(w, p + ".feed_forward_macaron", norm(w, p + ".norm_ff_macaron", x))
        x = x + rel_attention(w, p + ".self_attn", norm(w, p + ".norm_mha", x), pos)
        x = x + conv_module(w, p + ".conv_module", norm(w, p + ".norm_conv", x))
        x = x + 0.5 * feed_forward(w, p + ".feed_forward", norm(w, p + ".norm_ff", x))
        x = norm(w, p + ".norm_final", x)
    return norm(w, "encoder.after_norm", x)


def attend(w: Weights, p: str, q: mx.array, k: mx.array, v: mx.array, mask: mx.array | None = None) -> mx.array:
    scores = (q @ k.swapaxes(-1, -2)) / math.sqrt(q.shape[-1])
    if mask is not None:
        scores = scores + mask
    return linear(w, p + ".linear_out", merge_heads(mx.softmax(scores, axis=-1) @ v))


def causal_stack(w: Weights, p: str, layers: int, heads: int, x: mx.array, cache: Cache | None, memory: Cache | None = None) -> tuple[mx.array, Cache]:
    """Run new positions x (B, L, dim) through a pre-norm Transformer stack that attends to the cached earlier ones."""
    L, seen = x.shape[1], cache[0][0].shape[2] if cache else 0
    mask = None if L == 1 else mx.concatenate([mx.zeros((L, seen)), mx.triu(mx.full((L, L), -mx.inf), 1)], axis=1).astype(x.dtype)
    new_cache = []
    for i in range(layers):
        lp = f"{p}.decoders.{i}"
        y = norm(w, lp + ".norm1", x)
        q, k, v = (split_heads(linear(w, f"{lp}.self_attn.linear_{n}", y), heads) for n in "qkv")
        if cache:
            k, v = mx.concatenate([cache[i][0], k], axis=2), mx.concatenate([cache[i][1], v], axis=2)
        new_cache.append((k, v))
        x = x + attend(w, lp + ".self_attn", q, k, v, mask)
        if memory is not None:
            q = split_heads(linear(w, lp + ".src_attn.linear_q", norm(w, lp + ".norm2", x)), heads)
            x = x + attend(w, lp + ".src_attn", q, *memory[i])
        x = x + feed_forward(w, lp + ".feed_forward", norm(w, lp + ".norm3", x))
    return norm(w, p + ".after_norm", x), new_cache


def cross_memory(w: Weights, enc: mx.array) -> Cache:
    def project(i: int, n: str) -> mx.array:
        return split_heads(linear(w, f"decoder.decoders.{i}.src_attn.linear_{n}", enc), HEADS)[None]

    return [(project(i, "k"), project(i, "v")) for i in range(DEC_LAYERS)]


def decoder_step(w: Weights, tokens: mx.array, cache: Cache | None, memory: Cache) -> tuple[mx.array, Cache]:
    """Next-token log-probabilities (B, vocab) after feeding the new tokens (B, L)."""
    seen = cache[0][0].shape[2] if cache else 0
    embed = w["decoder.embed.weight"]
    x = embed[tokens] * math.sqrt(DIM) + sinusoid(mx.arange(seen, seen + tokens.shape[1]), DIM).astype(embed.dtype)
    x, cache = causal_stack(w, "decoder", DEC_LAYERS, HEADS, x, cache, memory)
    return log_softmax(linear(w, "decoder.output_layer", x[:, -1])), cache


def lm_step(w: Weights, tokens: mx.array, cache: Cache | None) -> tuple[mx.array, Cache]:
    x = nn.relu(norm(w, "lm.proj_norm", linear(w, "lm.proj", w["lm.embed.weight"][tokens]), eps=1e-5))
    x, cache = causal_stack(w, "lm", LM_LAYERS, LM_HEADS, x, cache)
    return log_softmax(linear(w, "lm.output_layer", x[:, -1])), cache


def ctc_prefix_scores(x: np.ndarray, cand: np.ndarray, r_n: np.ndarray, r_b: np.ndarray, last: np.ndarray, length: int):
    """CTC prefix log-probabilities of extending each hypothesis by each candidate token (Watanabe et al. 2017).

    x: frame log-probabilities (T, vocab), blank at index 0. cand: candidate tokens (n, P).
    r_n, r_b: (T, n) log-probability that the current prefix is emitted by frame t, ending in non-blank / blank.
    last: last token of each prefix (n,). length: number of tokens already in the prefix.
    Returns the prefix scores (n, P) and the new r_n, r_b of shape (T, n, P).

    The usual per-frame loop is a linear recurrence, so it is unrolled into cumulative sums here.
    """
    T, start = x.shape[0], max(length, 1)
    xc, blank = x[:, cand], x[:, 0][:, None, None]
    r_sum = np.logaddexp(r_n, r_b)
    # A repeated token can only extend paths that ended in blank.
    phi = np.where(cand[None] == last[None, :, None], r_b[:, :, None], r_sum[:, :, None])
    new_n = np.full(xc.shape, -np.inf)
    new_b = np.full(xc.shape, -np.inf)
    if length == 0:
        new_n[0] = xc[0]
    with np.errstate(invalid="ignore"):
        cum = np.cumsum(xc[start:], axis=0)
        enter = np.logaddexp.accumulate(phi[start - 1:T - 1] - (cum - xc[start:]), axis=0)
        new_n[start:] = cum + np.logaddexp(new_n[start - 1], enter)
        cum_b = np.cumsum(blank[start:], axis=0)
        new_b[start:] = cum_b + np.logaddexp.accumulate(new_n[start - 1:T - 1] - (cum_b - blank[start:]), axis=0)
        psi = np.logaddexp(np.logaddexp.reduce(phi[start - 1:T - 1] + xc[start:], axis=0), new_n[start - 1])
    return psi, new_n, new_b


def end_detect(ended: list[tuple[float, list[int]]], step: int) -> bool:
    """ESPnet's stopping rule: the last three hypothesis lengths all ended far below the best score."""
    best = max(score for score, _ in ended)
    worse = 0
    for m in range(3):
        same = [score for score, tokens in ended if len(tokens) + 2 == step - m]
        worse += bool(same) and max(same) - best < -10.0
    return worse == 3


def agreed_words(pieces: list[str], hyp: list[int], previous: list[int], committed: int) -> int:
    """How many leading tokens of `hyp` to commit: the prefix shared with the previous hypothesis, cut back to whole words.

    A word is whole once the token after it starts a new word, so the trailing word is never committed.
    """
    agreed = next((i for i, (a, b) in enumerate(zip(hyp, previous)) if a != b), min(len(hyp), len(previous)))
    while agreed > committed and not (agreed < len(hyp) and pieces[hyp[agreed]].startswith("▁")):
        agreed -= 1
    return max(agreed, committed)


class LipReader:
    def __init__(self, weights: Path = Path(__file__).parent / "weights", file: str = "lipread.safetensors"):
        loaded = mx.load(str(weights / file))
        assert isinstance(loaded, dict), "run convert.py first"
        self.w: Weights = loaded
        self.dtype = self.w["stem.weight"].dtype
        self.tokens = ["<blank>", *(line.split()[0] for line in (weights / "tokens.txt").read_text().splitlines()), "<eos>"]
        self.eos = len(self.tokens) - 1

    def features(self, crops: np.ndarray) -> mx.array:
        """Grayscale mouth crops (T, 88, 88) uint8 -> front-end features (T, 512)."""
        return frontend(self.w, ((mx.array(crops).astype(mx.float32) / 255 - MEAN) / STD).astype(self.dtype))

    def encode(self, feats: mx.array) -> mx.array:
        return conformer(self.w, feats)

    def text(self, tokens: list[int]) -> str:
        return "".join(self.tokens[t] for t in tokens).replace("▁", " ").strip().lower()

    def greedy(self, enc: mx.array) -> list[int]:
        best = np.array(mx.argmax(linear(self.w, "ctc", enc), axis=-1))
        return [int(t) for t, prev in zip(best, [0, *best[:-1]]) if t != prev and t != 0]

    def decode(self, enc: mx.array, beam: int = 40, ctc_weight: float = 0.1, lm_weight: float = 0.3,
               prefix: tuple[int, ...] | list[int] = ()) -> list[tuple[float, list[int]]]:
        """Joint CTC/attention beam search. Returns (score, tokens) hypotheses, best first.

        `prefix` tokens are forced and cost a single decoder pass, so re-decoding a mostly settled utterance stays cheap.
        Scores leave the prefix out.
        """
        w, eos, T = self.w, self.eos, enc.shape[0]
        x = np.array(log_softmax(linear(w, "ctc", enc))).astype(np.float64)
        memory = cross_memory(w, enc)
        ys = np.array([[eos, *prefix]])
        score, s_prev = np.zeros(1), np.zeros(1)
        r_n, r_b = np.full((T, 1), -np.inf), np.cumsum(x[:, 0])[:, None]
        for i, token in enumerate(prefix):
            psi, new_n, new_b = ctc_prefix_scores(x, np.full((1, 1), token), r_n, r_b, ys[:, i], i)
            s_prev, r_n, r_b = psi[:, 0], new_n[:, :, 0], new_b[:, :, 0]
        dec_cache = lm_cache = None
        feed, pre_beam = mx.array(ys), int(1.5 * beam)
        ended: list[tuple[float, list[int]]] = []
        for step in range(len(prefix), T):
            dec, dec_cache = decoder_step(w, feed, dec_cache, memory)
            full = (1 - ctc_weight) * dec
            if lm_weight:
                lm, lm_cache = lm_step(w, feed, lm_cache)
                full = full + lm_weight * lm
            top = mx.argpartition(-dec, pre_beam - 1, axis=-1)[:, :pre_beam]
            # The end token is always a candidate; the extra column is a blank (never chosen) where the pre-beam already has it.
            candidates = mx.concatenate([top, mx.where((top == eos).any(axis=1, keepdims=True), 0, eos)], axis=1)
            attention = mx.take_along_axis(full, candidates, axis=1)
            mx.eval(candidates, attention, dec_cache, lm_cache or [])
            cand = np.array(candidates)
            psi, new_n, new_b = ctc_prefix_scores(x, cand, r_n, r_b, ys[:, -1], step)
            psi = np.where(cand == eos, np.logaddexp(r_n, r_b)[-1][:, None], psi)
            psi[cand == 0] = -np.inf
            total = score[:, None] + np.array(attention) + ctc_weight * (psi - s_prev[:, None])
            flat = np.where(np.isnan(total), -np.inf, total).ravel()
            order = np.argsort(-flat)[:beam]
            order = order[np.isfinite(flat[order])]
            hyp, col = np.divmod(order, cand.shape[1])
            ys = np.concatenate([ys[hyp], cand[hyp, col][:, None]], axis=1)
            score = flat[order]
            done = (ys[:, -1] == eos) | (step == T - 1)
            ended += [(float(s), [int(t) for t in y[1:] if t != eos]) for s, y in zip(score[done], ys[done])]
            keep = ~done
            if not keep.any() or (ended and end_detect(ended, step)):
                break
            hyp, col, ys, score = hyp[keep], col[keep], ys[keep], score[keep]
            s_prev, r_n, r_b = psi[hyp, col], new_n[:, hyp, col], new_b[:, hyp, col]
            index, feed = mx.array(hyp), mx.array(ys[:, -1:])
            dec_cache = [(k[index], v[index]) for k, v in dec_cache]
            lm_cache = lm_cache and [(k[index], v[index]) for k, v in lm_cache]
        return sorted(ended, key=lambda h: -h[0]) or [(0.0, list(prefix))]


def demo() -> None:
    """The unrolled CTC recurrence must match the textbook per-frame loop, token after token."""
    rng = np.random.default_rng(0)
    T, V, n, P = 30, 12, 3, 5
    logits = rng.normal(size=(T, V)) * 3
    x = logits - np.logaddexp.reduce(logits, axis=1, keepdims=True)
    r_n, r_b = np.full((T, n), -np.inf), np.repeat(np.cumsum(x[:, 0])[:, None], n, axis=1)
    last = np.full(n, V - 1)
    for length in range(6):
        cand = np.stack([rng.choice(np.arange(1, V), P, replace=False) for _ in range(n)])
        if length:
            cand[:, 0] = last  # exercise the repeated-token branch
        psi, new_n, new_b = ctc_prefix_scores(x, cand, r_n, r_b, last, length)
        for h in range(n):
            for j, c in enumerate(cand[h]):
                rn, rb = np.full(T, -np.inf), np.full(T, -np.inf)
                phi = r_b[:, h] if c == last[h] else np.logaddexp(r_n[:, h], r_b[:, h])
                start = max(length, 1)
                if length == 0:
                    rn[0] = x[0, c]
                want = rn[start - 1]
                for t in range(start, T):
                    rn[t] = np.logaddexp(rn[t - 1], phi[t - 1]) + x[t, c]
                    rb[t] = np.logaddexp(rn[t - 1], rb[t - 1]) + x[t, 0]
                    want = np.logaddexp(want, phi[t - 1] + x[t, c])
                assert np.allclose(psi[h, j], want) and np.allclose(new_n[:, h, j], rn) and np.allclose(new_b[:, h, j], rb), (length, h, j)
        pick = rng.integers(0, P, n)
        r_n, r_b, last = new_n[:, np.arange(n), pick], new_b[:, np.arange(n), pick], cand[np.arange(n), pick]
    print("ctc_prefix_scores matches the per-frame recurrence")

    pieces = ["<blank>", "▁THE", "▁CAT", "S", "▁SAT"]
    assert agreed_words(pieces, [1, 2, 3, 4], [1, 2, 3, 4], 0) == 3, "the trailing word stays tentative"
    assert agreed_words(pieces, [1, 2, 3], [1, 2, 4], 0) == 1, "a word whose ending is disputed is not committed"
    assert agreed_words(pieces, [1, 2], [], 0) == 0, "one hypothesis alone commits nothing"
    assert agreed_words(pieces, [1, 2], [1, 2], 1) == 1, "commitments never shrink"
    print("agreed_words commits whole agreed words only")


if __name__ == "__main__":
    demo()

"""One-time setup: fetch the Auto-AVSR video-only checkpoint and its subword language model, fold the batch
norms into the convolutions, and write one MLX-layout safetensors file plus the token list into weights/.

  uv run --with torch lipread/convert.py [--dtype float32]

The checkpoints are released by Imperial College London for non-commercial research use only.
"""

import argparse
import shutil
import urllib.request
from pathlib import Path

import mlx.core as mx
import numpy as np
import torch

HF = "https://huggingface.co/Amanvir"
SOURCES = {
    "vsr.pth": f"{HF}/LRS3_V_WER19.1/resolve/main/model.pth",
    "lm.pth": f"{HF}/lm_en_subword/resolve/main/model.pth",
    "tokens.txt": "https://raw.githubusercontent.com/amanvirparhar/chaplin/main/pipelines/tokens/unigram5000_units.txt",
}
BN = ("weight", "bias", "running_mean", "running_var")


def fold(conv: np.ndarray, bn: dict, bias: np.ndarray | None = None) -> tuple[np.ndarray, np.ndarray]:
    scale = bn["weight"] / np.sqrt(bn["running_var"] + 1e-5)
    shift = bn["bias"] - bn["running_mean"] * scale
    return conv * scale.reshape(-1, *[1] * (conv.ndim - 1)), shift if bias is None else shift + bias * scale


def convert_vsr(sd: dict) -> dict:
    sd = {k: v.numpy() for k, v in sd.items() if not k.endswith("num_batches_tracked")}
    out = {}

    def bn(prefix: str) -> dict:
        return {k: sd.pop(f"{prefix}.{k}") for k in BN}

    w, b = fold(sd.pop("encoder.frontend.frontend3D.0.weight"), bn("encoder.frontend.frontend3D.1"))
    # The 5x7x7 3-D stem becomes a 2-D convolution whose 5 input channels are consecutive frames.
    out["stem.weight"], out["stem.bias"] = w[:, 0].transpose(0, 2, 3, 1), b
    for layer in range(1, 5):
        for block in range(2):
            src, dst = f"encoder.frontend.trunk.layer{layer}.{block}", f"trunk.{layer}.{block}"
            for conv, norm, name in (("conv1", "bn1", "conv1"), ("conv2", "bn2", "conv2"), ("downsample.0", "downsample.1", "downsample")):
                if f"{src}.{conv}.weight" in sd:
                    w, b = fold(sd.pop(f"{src}.{conv}.weight"), bn(f"{src}.{norm}"))
                    out[f"{dst}.{name}.weight"], out[f"{dst}.{name}.bias"] = w.transpose(0, 2, 3, 1), b
    for i in range(12):
        p = f"encoder.encoders.{i}.conv_module"
        w, b = fold(sd.pop(f"{p}.depthwise_conv.weight"), bn(f"{p}.norm"), sd.pop(f"{p}.depthwise_conv.bias"))
        out[f"{p}.depthwise_conv.weight"], out[f"{p}.depthwise_conv.bias"] = w.transpose(0, 2, 1), b
        for name in ("pointwise_cov1", "pointwise_cov2"):
            out[f"{p}.{name}.weight"] = sd.pop(f"{p}.{name}.weight")[:, :, 0]
    for k, v in sd.items():
        out[k.replace("encoder.embed.0.", "encoder.embed.").replace("decoder.embed.0.", "decoder.embed.").replace("ctc.ctc_lo.", "ctc.")] = v
    return out


def convert_lm(sd: dict) -> dict:
    prefixes = (("encoder.embed.0.", "proj."), ("encoder.embed.1.", "proj_norm."), ("encoder.encoders.", "decoders."),
                ("encoder.after_norm.", "after_norm."), ("decoder.", "output_layer."), ("embed.", "embed."))
    out = {}
    for k, v in sd.items():
        old, new = next((o, n) for o, n in prefixes if k.startswith(o))
        out["lm." + new + k[len(old):].replace("norm_mha.", "norm1.").replace("norm_ff.", "norm3.")] = v.numpy()
    return out


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--weights", type=Path, default=Path(__file__).parent / "weights")
    p.add_argument("--dtype", default="float16", choices=["float16", "float32"])
    p.add_argument("--out", default="lipread.safetensors")
    args = p.parse_args()
    raw = args.weights / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    for name, url in SOURCES.items():
        if not (raw / name).exists():
            print(f"downloading {name} ...")
            urllib.request.urlretrieve(url, raw / name)
    load = lambda name: torch.load(raw / name, map_location="cpu", weights_only=True)
    arrays = convert_vsr(load("vsr.pth")) | convert_lm(load("lm.pth"))
    mx.save_safetensors(str(args.weights / args.out), {k: mx.array(v).astype(getattr(mx, args.dtype)) for k, v in arrays.items()})
    shutil.copy(raw / "tokens.txt", args.weights / "tokens.txt")
    print(f"wrote {args.weights / args.out}: {len(arrays)} tensors, {sum(v.size for v in arrays.values()) / 1e6:.0f}M parameters, {args.dtype}")


if __name__ == "__main__":
    main()

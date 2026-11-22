"""Train your profile on Modal from the onboarding recordings: the lip profile and the voice, on two GPUs at once.

  .venv/bin/python lipread.py --calibrate    # packs calib/*.npz (not eval.npz) and runs this app

Lips: finetune_mlx.py itself, run with MLX's CUDA backend, so the weights come back ready for local MLX inference
(profile/lips.safetensors). Voice: Qwen3-TTS fine-tuned on the sentences you read aloud (profile/voice_model/).
Uploaded as function inputs: 96x96 mouth crops, the read-aloud clips and their sentences. Inference stays local.
"""
import io

import modal

app = modal.App("lipread-profile")
lips_image = (modal.Image.from_registry("nvidia/cuda:12.8.1-devel-ubuntu24.04", add_python="3.11")  # MLX compiles
              .env({"CUDA_HOME": "/usr/local/cuda", "LD_LIBRARY_PATH": ""})  # kernels at runtime; pip's CUDA libs
              .pip_install("mlx[cuda12]==0.32.4", "numpy==1.26.4", "sentencepiece==0.2.0")
              .add_local_file("mlx_weights/model.safetensors", "/root/mlx_weights/model.safetensors")
              .add_local_file("mlx_weights/lm.safetensors", "/root/mlx_weights/lm.safetensors")
              .add_local_dir("vsr/pipelines/tokens", "/root/vsr/pipelines/tokens")
              .add_local_python_source("vsr_mlx", "finetune_mlx"))


voice_image = (modal.Image.debian_slim(python_version="3.11")
               .apt_install("git", "sox", "libsndfile1", "ffmpeg")
               .pip_install("torch==2.6.0", "torchaudio==2.6.0", "qwen-tts==0.1.1", "speechbrain==1.0.3")
               .run_commands(  # official fine-tuning code (dataset.py), pinned; models baked into the image
                   "git clone -q https://github.com/QwenLM/Qwen3-TTS /opt/qwen3tts && cd /opt/qwen3tts && "
                   "git checkout -q 022e286b98fbec7e1e916cb940cdf532cd9f488e",
                   "python -c \"from huggingface_hub import snapshot_download as d; "
                   "d('Qwen/Qwen3-TTS-12Hz-0.6B-Base', local_dir='/models/base'); "
                   "d('Qwen/Qwen3-TTS-Tokenizer-12Hz', local_dir='/models/tokenizer'); "
                   "d('speechbrain/spkrec-ecapa-voxceleb', local_dir='/models/ecapa'); "
                   "d('openai/whisper-small.en', local_dir='/models/whisper')\""))
vol = modal.Volume.from_name("lipread-profile", create_if_missing=True)
SPEAKER = "me"  # the fine-tuned voice's speaker name


def pack(clips, texts, cuts):
    import numpy as np
    buf = io.BytesIO()
    np.savez_compressed(buf, frames=np.concatenate(clips), lengths=[len(c) for c in clips], texts=texts,
                        ncuts=[0 if c is None else len(c) for c in cuts],
                        cuts=np.concatenate([np.zeros(0, int)] + [c for c in cuts if c is not None]).astype(int))
    return buf.getvalue()


def unpack(blob):
    import numpy as np
    z = np.load(io.BytesIO(blob))
    clips = np.split(z["frames"], np.cumsum(z["lengths"])[:-1])
    cuts = np.split(z["cuts"], np.cumsum(z["ncuts"])[:-1])
    return list(clips), [str(t) for t in z["texts"]], [c if len(c) else None for c in cuts]


@app.local_entrypoint()
def main(data: str = "", profile: str = "profile", epochs: int = 20):
    """Run by `lipread.py --calibrate`, which packs the recordings into `data` (this CLI's Python has no numpy).
    Lips and voice train at the same time on separate GPUs."""
    import json
    import os
    calls = {}
    if data:
        calls["lips"] = lips.spawn(open(data, "rb").read(), epochs)
    index = os.path.join(profile, "voice", "clips.jsonl")
    if os.path.exists(index):
        rows = [json.loads(line) for line in open(index)]
        clips = {r["audio"]: open(os.path.join(profile, "voice", r["audio"]), "rb").read() for r in rows}
        print(f"Uploading {len(rows)} sentences you read aloud ({sum(map(len, clips.values())) / 48000:.0f} s) "
              "for voice training...", flush=True)
        calls["voice"] = voice.spawn(clips, rows, open(os.path.join(profile, "voice.wav"), "rb").read(),
                                     open(os.path.join(profile, "voice.txt")).read().strip())
    if "lips" in calls:
        res = calls["lips"].get()
        print(f"Lip profile: held-out error {res['tuned_beam']:.1%} (stock model {res['baseline_ctc']:.1%} fast "
              f"decoder), best epoch {res['best_epoch']} of {len(res['epochs'])}", flush=True)
        if "weights" in res:
            out = os.path.join(profile, "lips.safetensors")
            open(out + ".tmp", "wb").write(res["weights"])
            os.replace(out + ".tmp", out)
            print(f"Saved {out}", flush=True)
        else:
            print("Lip training didn't beat the stock model on held-out clips; nothing saved.", flush=True)
    if "voice" in calls:
        res = calls["voice"].get()
        samples = os.path.join(profile, "voice_samples")  # listen: real vs zero-shot vs fine-tuned
        os.makedirs(samples, exist_ok=True)
        for name, b in res.pop("samples").items():
            open(os.path.join(samples, f"{name}.wav"), "wb").write(b)
        print("Voice closeness to you (ECAPA cosine; your own held-out sentences score "
              f"{res['real']['similarity']}): zero-shot {res['zero_shot']}, "
              + ", ".join(f"{k} {v}" for k, v in res.items() if k.startswith("epoch_")), flush=True)
        if res["best_epoch"]:
            out = os.path.join(profile, "voice_model")
            os.makedirs(out, exist_ok=True)
            for name in ("config.json", "model.safetensors"):
                with open(os.path.join(out, name), "wb") as f:
                    for chunk in vol.read_file(f"voice/{name}"):
                        f.write(chunk)
            print(f"Saved the fine-tuned voice (epoch {res['best_epoch']}) to {out}/", flush=True)
        else:
            print("Fine-tuning didn't beat zero-shot cloning; keeping the 15 s reference clip.", flush=True)
        if res["best_epoch"]:
            vol.remove_file("voice", recursive=True)  # don't leave your voice on Modal


@app.function(image=voice_image, gpu="L40S", timeout=2 * 3600, volumes={"/vol": vol})
def voice(clips: dict, rows: list, ref: bytes, ref_text: str, epochs: int = 20, lr: float = 2e-5,
          check_every: int = 4) -> dict:
    """Fine-tune Qwen3-TTS-0.6B-Base on your sentences read aloud (the official single-speaker recipe), scoring
    each checkpoint on 3 held-out sentences: closeness to your real voice (ECAPA speaker-embedding cosine) and
    clarity (Whisper word error). Zero-shot cloning from the 15 s reference is the baseline to beat. The best
    fine-tuned checkpoint (if better) is left in the volume as voice/{config.json, model.safetensors}."""
    import io
    import json
    import os
    import shutil
    import sys
    import numpy as np
    import soundfile as sf
    import torch
    import torchaudio
    from speechbrain.inference.speaker import EncoderClassifier
    from transformers import pipeline
    sys.path.insert(0, "/opt/qwen3tts/finetuning")
    from dataset import TTSDataset
    from qwen_tts import Qwen3TTSModel, Qwen3TTSTokenizer

    d = "/tmp/voice"
    os.makedirs(d, exist_ok=True)
    for name, b in clips.items():
        open(f"{d}/{name}", "wb").write(b)
    open(f"{d}/ref.wav", "wb").write(ref)
    rows = [dict(r, audio=f"{d}/{r['audio']}", ref_audio=f"{d}/ref.wav") for r in rows]
    test, train = rows[-3:], rows[:-3]  # the last 3 sentences are only used to score the voices
    tok = Qwen3TTSTokenizer.from_pretrained("/models/tokenizer", device_map="cuda:0")
    for r, c in zip(train, tok.encode([r["audio"] for r in train]).audio_codes):
        r["audio_codes"] = c.cpu().tolist()
    del tok

    ecapa = EncoderClassifier.from_hparams(source="/models/ecapa", run_opts={"device": "cuda"})
    asr = pipeline("automatic-speech-recognition", model="/models/whisper", device=0)

    def embed(wav, sr):
        x = torchaudio.functional.resample(torch.tensor(np.asarray(wav), dtype=torch.float32), sr, 16000)
        e = ecapa.encode_batch(x[None].cuda())[0, 0]
        return e / e.norm()
    centroid = torch.stack([embed(*sf.read(r["audio"])) for r in train]).mean(0)
    centroid = centroid / centroid.norm()  # your voice, from the training sentences

    def words(t):
        import re
        return re.sub(r"[^a-z' ]", " ", t.lower()).split()

    def score(wavs, sr):
        sim = float(np.mean([(embed(w, sr) @ centroid).item() for w in wavs]))
        errs = n = 0
        for w, r in zip(wavs, test):
            ref_w, hyp = words(r["text"]), words(asr({"raw": np.asarray(w, np.float32), "sampling_rate": sr})["text"])
            dist = list(range(len(hyp) + 1))
            for i, a in enumerate(ref_w, 1):
                prev, dist[0] = dist[0], i
                for j, b in enumerate(hyp, 1):
                    prev, dist[j] = dist[j], min(dist[j] + 1, dist[j - 1] + 1, prev + (a != b))
            errs, n = errs + dist[-1], n + len(ref_w)
        return {"similarity": round(sim, 3), "wer": round(errs / n, 3)}

    def wav_bytes(w, sr):
        buf = io.BytesIO()
        sf.write(buf, np.asarray(w, np.float32), sr, format="WAV")
        return buf.getvalue()

    real = [sf.read(r["audio"]) for r in test]
    report = {"real": {"similarity": round(float(np.mean([(embed(*x) @ centroid).item() for x in real])), 3)},
              "samples": {"real": wav_bytes(*real[0])}}
    tts = Qwen3TTSModel.from_pretrained("/models/base", device_map="cuda:0", dtype=torch.bfloat16,
                                        attn_implementation="sdpa")
    texts = [r["text"] for r in test]
    wavs, sr = tts.generate_voice_clone(text=texts, ref_audio=f"{d}/ref.wav", ref_text=ref_text)
    report["zero_shot"], report["samples"]["zero_shot"] = score(wavs, sr), wav_bytes(wavs[0], sr)
    print(report["real"], "zero-shot:", report["zero_shot"], flush=True)

    # --- SFT, adapted from QwenLM/Qwen3-TTS finetuning/sft_12hz.py (Apache-2.0) ---
    model = tts.model
    data = TTSDataset(train, tts.processor, model.config)
    loader = torch.utils.data.DataLoader(data, batch_size=2, shuffle=True, collate_fn=data.collate_fn)
    opt = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=0.01)
    target_spk, best, accum, step = None, None, 4, 0  # gradient accumulation 4, as in the official script
    for epoch in range(1, epochs + 1):
        model.train()
        for batch in loader:
            batch = {k: v.cuda() if torch.is_tensor(v) else v for k, v in batch.items()}
            with torch.autocast("cuda", dtype=torch.bfloat16):
                spk = model.speaker_encoder(batch["ref_mels"].to(model.dtype)).detach()
                target_spk = spk if target_spk is None else target_spk
                ids = batch["input_ids"]
                # text_projection as in generation (the official script skips it; 0.6B's text width differs)
                text = model.talker.text_projection(model.talker.model.text_embedding(ids[:, :, 0]))
                codec = model.talker.model.codec_embedding(ids[:, :, 1]) * batch["codec_embedding_mask"]
                codec[:, 6, :] = spk  # the speaker goes into the codec stream; the text embedding there stays
                emb = text * batch["text_embedding_mask"] + codec
                for i in range(1, 16):
                    emb = emb + (model.talker.code_predictor.get_input_embeddings()[i - 1](batch["codec_ids"][:, :, i])
                                 * batch["codec_mask"].unsqueeze(-1))
                # Losses computed here, not by the model: its HF causal loss shifts labels a second time, and
                # the official script pairs each frame with its own position's hidden state. In generation,
                # position t's hidden state predicts frame t+1's first code, then (with it) the other 15 codes.
                out = model.talker(inputs_embeds=emb[:, :-1], attention_mask=batch["attention_mask"][:, :-1],
                                   output_hidden_states=True)
                ce = torch.nn.functional.cross_entropy
                loss0 = ce(out.logits.float().flatten(0, 1), batch["codec_0_labels"][:, 1:].flatten(), ignore_index=-100)
                frames = batch["codec_ids"][batch["codec_mask"]]
                hidden = out.hidden_states[0][-1][batch["codec_mask"][:, 1:]]
                sub_logits, _ = model.talker.forward_sub_talker_finetune(frames, hidden)
                sub_loss = ce(sub_logits.float().flatten(0, 1), frames[:, 1:].flatten())
                loss = loss0 + 0.3 * sub_loss
            (loss / accum).backward()
            step += 1
            if step % accum == 0:
                torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                opt.step()
                opt.zero_grad()
        print(f"epoch {epoch} loss {loss.item():.3f}", flush=True)
        if epoch % check_every:
            continue
        ckpt = "/tmp/ckpt"  # a custom-voice model: the base folder + new weights + the speaker as codec id 3000
        shutil.copytree("/models/base", ckpt, dirs_exist_ok=True)
        cfg = json.load(open("/models/base/config.json"))
        cfg["tts_model_type"] = "custom_voice"
        cfg["talker_config"]["spk_id"] = {SPEAKER: 3000}
        cfg["talker_config"]["spk_is_dialect"] = {SPEAKER: False}
        json.dump(cfg, open(f"{ckpt}/config.json", "w"), indent=2)
        state = {k: v.detach().cpu() for k, v in model.state_dict().items() if not k.startswith("speaker_encoder")}
        state["talker.model.codec_embedding.weight"][3000] = target_spk[0].detach().cpu().to(torch.bfloat16)
        from safetensors.torch import save_file
        save_file(state, f"{ckpt}/model.safetensors")
        tuned = Qwen3TTSModel.from_pretrained(ckpt, device_map="cuda:0", dtype=torch.bfloat16,
                                              attn_implementation="sdpa")
        wavs, sr = tuned.generate_custom_voice(text=texts, speaker=SPEAKER)
        s = score(wavs, sr)
        del tuned
        print(f"epoch {epoch}:", s, flush=True)
        report[f"epoch_{epoch}"] = s
        ok = s["wer"] <= max(report["zero_shot"]["wer"] + 0.05, 0.1)  # must stay as clear as zero-shot
        if ok and s["similarity"] > max(report["zero_shot"]["similarity"], best[1] if best else -1):
            best = (epoch, s["similarity"])
            report["samples"]["fine_tuned"] = wav_bytes(wavs[0], sr)
            os.makedirs("/vol/voice", exist_ok=True)
            shutil.copy(f"{ckpt}/config.json", "/vol/voice/config.json")
            shutil.copy(f"{ckpt}/model.safetensors", "/vol/voice/model.safetensors")
            vol.commit()
    report["best_epoch"] = best[0] if best else None
    return report


@app.function(image=lips_image, gpu="L40S", timeout=3 * 3600)
def lips(data: bytes, epochs: int = 20) -> dict:
    """finetune_mlx.train on the GPU; returns its report plus the tuned encoder weights (fp16 safetensors bytes)."""
    import os
    import finetune_mlx
    out = "/tmp/lips.safetensors"
    res = finetune_mlx.train(*unpack(data), epochs=epochs, out=out, log=lambda m: print(m, flush=True))
    if os.path.exists(out):
        res["weights"] = open(out, "rb").read()
    return res

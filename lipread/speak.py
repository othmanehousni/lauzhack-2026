"""Speak text in the user's own voice, locally on MLX (Qwen3-TTS).

Uses the voice fine-tuned on your onboarding recording (profile/voice_model, from `lipread.py --calibrate`) when
there is one, else clones the 15 s reference clip (profile/voice.wav + voice.txt) zero-shot.

  .venv/bin/python speak.py "text to say"
  .venv/bin/python speak.py "text" --ref me.wav --ref-text "..."   any reference clip and its exact transcript
  add --save out.wav to write a file instead of playing it
"""
import os
import queue
import sys
import threading
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = "mlx-community/Qwen3-TTS-12Hz-0.6B-Base-bf16"
REF_WAV = os.path.join(HERE, "profile", "voice.wav")  # ~10-15 s of the user talking, saved by onboarding
REF_TXT = os.path.join(HERE, "profile", "voice.txt")  # its exact transcript
TUNED = os.path.join(HERE, "profile", "voice_model")  # Qwen3-TTS fine-tuned on your voice by train_profile.py
SPEAKER = "me"  # its speaker name (train_profile.SPEAKER)


def complete(folder):
    """The fine-tuned model comes back as weights + config; the tokenizer and speech codec are the base model's."""
    import shutil
    from huggingface_hub import snapshot_download
    base = snapshot_download(MODEL)
    for name in os.listdir(base):
        src, dst = os.path.join(base, name), os.path.join(folder, name)
        if name not in ("config.json", "model.safetensors", "model.safetensors.index.json") and not os.path.exists(dst):
            (shutil.copytree if os.path.isdir(src) else shutil.copy)(src, dst)  # copies the files, not the links


class Voice:
    """Clones the voice in a short reference clip and speaks sentences with it.
    say() returns at once; sentences play in order on a background thread, streamed as they are generated."""

    def __init__(self, ref_wav=REF_WAV, ref_text=None, model=None):
        from mlx_audio.tts.utils import load_model
        self.tuned = model is None and os.path.exists(os.path.join(TUNED, "model.safetensors"))
        if self.tuned:
            complete(TUNED)
        self.model = load_model(TUNED if self.tuned else model or MODEL)
        self.ref_wav, self.ref_text = (None, None) if self.tuned else (ref_wav, ref_text or open(REF_TXT).read().strip())
        self.queue, self.cancel = queue.Queue(), threading.Event()
        list(self.chunks("Hello."))  # warm-up: the first sentence otherwise takes ~9 s while kernels compile
        threading.Thread(target=self._run, daemon=True).start()

    def chunks(self, text):
        """Audio for `text` as float32 numpy chunks, ~0.3 s apart, starting before the sentence is fully generated."""
        voice = dict(voice=SPEAKER) if self.tuned else dict(ref_audio=self.ref_wav, ref_text=self.ref_text)
        for r in self.model.generate(text=text, stream=True, streaming_interval=0.32, **voice):
            yield np.asarray(r.audio, dtype=np.float32)

    def say(self, text):
        self.queue.put(text)

    def stop(self):
        """Drop queued sentences and cut off the one playing."""
        while not self.queue.empty():
            self.queue.get_nowait()
            self.queue.task_done()
        self.cancel.set()

    def _run(self):
        import sounddevice
        while True:
            text = self.queue.get()
            self.cancel.clear()
            # Generation runs at ~1.0-1.4x real time on an M1 Pro, so hold 0.6 s back to avoid gaps mid-sentence.
            buffered, started = [], False
            with sounddevice.OutputStream(samplerate=self.model.sample_rate, channels=1, dtype="float32") as out:
                for chunk in self.chunks(text):
                    if self.cancel.is_set():
                        break
                    buffered.append(chunk)
                    if started or sum(map(len, buffered)) >= 0.6 * self.model.sample_rate:
                        out.write(np.concatenate(buffered).reshape(-1, 1))
                        buffered, started = [], True
                if buffered and not self.cancel.is_set():
                    out.write(np.concatenate(buffered).reshape(-1, 1))
            self.queue.task_done()


def main():
    args, opts = [], {}
    it = iter(sys.argv[1:])
    for a in it:
        if a in ("--ref", "--ref-text", "--save"):
            opts[a] = next(it)
        else:
            args.append(a)
    if not args:
        sys.exit(__doc__)
    t0 = time.time()
    voice = Voice(opts.get("--ref", REF_WAV), opts.get("--ref-text"))
    print(f"model loaded in {time.time() - t0:.1f} s", flush=True)
    if "--save" not in opts:
        voice.say(args[0])
        voice.queue.join()
        return
    import wave
    t0, first, audio = time.time(), None, []
    for chunk in voice.chunks(args[0]):
        first = first or time.time() - t0
        audio.append(chunk)
    audio = np.concatenate(audio)
    took, seconds = time.time() - t0, len(audio) / voice.model.sample_rate
    with wave.open(opts["--save"], "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(voice.model.sample_rate)
        f.writeframes((np.clip(audio, -1, 1) * 32767).astype(np.int16).tobytes())
    print(f"first audio after {first:.2f} s; {seconds:.1f} s of speech generated in {took:.1f} s "
          f"(real-time factor {took / seconds:.2f}); saved {opts['--save']}", flush=True)


if __name__ == "__main__":
    main()

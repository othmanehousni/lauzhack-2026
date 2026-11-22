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
FAST = TUNED + "-8bit"  # its 8-bit copy, the one that speaks; self-contained, so it can be shipped on its own
SPEAKER = "me"  # its speaker name (train_profile.SPEAKER)


def has_tuned():
    return os.path.exists(os.path.join(TUNED, "model.safetensors")) or os.path.exists(os.path.join(FAST, "config.json"))


def complete(folder):
    """The fine-tuned model comes back as weights + config; the tokenizer and speech codec are the base model's."""
    import shutil
    from huggingface_hub import snapshot_download
    base = snapshot_download(MODEL)
    for name in os.listdir(base):
        src, dst = os.path.join(base, name), os.path.join(folder, name)
        if name not in ("config.json", "model.safetensors", "model.safetensors.index.json") and not os.path.exists(dst):
            (shutil.copytree if os.path.isdir(src) else shutil.copy)(src, dst)  # copies the files, not the links


def quantized(model, out):
    """8-bit copy of a voice model, made once (and again when the model is retrained). It generates ~1.4x faster,
    which keeps speech ahead of playback while the lip model shares the GPU, with the same closeness to your voice
    (0.992 vs 0.991) and no word errors in tests."""
    src = os.path.join(model, "model.safetensors")
    done = os.path.join(out, "config.json")
    if not os.path.exists(done) or (os.path.exists(src) and os.path.getmtime(src) > os.path.getmtime(done)):
        import shutil
        from mlx_audio.convert import convert
        print("Making a faster 8-bit copy of the voice (once)...", flush=True)
        shutil.rmtree(out, ignore_errors=True)
        convert(model, out, quantize=True, q_bits=8)
    return out


class Voice:
    """Speaks sentences in your voice. say() returns at once; sentences play in order, streamed as they are
    generated. Generation and playback run separately (the audio device pulls from a buffer), and playback starts
    once the buffer will last until the sentence is fully generated, so speech doesn't stop and start mid-word."""

    MIN_BUFFER = 0.5  # seconds buffered before speaking, even when generation is far ahead of playback

    def __init__(self, ref_wav=REF_WAV, ref_text=None, model=None):
        import sounddevice
        from mlx_audio.tts.utils import load_model
        self.tuned = model is None and has_tuned()
        if self.tuned and os.path.exists(os.path.join(TUNED, "model.safetensors")):  # (re)make the 8-bit copy
            complete(TUNED)
            model = quantized(TUNED, FAST)
        elif self.tuned:
            model = FAST
        elif model is None:
            model = quantized(MODEL, os.path.join(HERE, "models", "voice-clone-8bit"))
        self.model = load_model(model)
        self.sr = self.model.sample_rate
        self.ref_wav, self.ref_text = (None, None) if self.tuned else (ref_wav, ref_text or open(REF_TXT).read().strip())
        self.queue, self.cancel = queue.Queue(), threading.Event()
        self.lock, self.buf, self.playing, self.done = threading.Lock(), np.zeros(0, np.float32), False, True
        self.sec_per_char = 0.066  # speech length per character of text; learned from every sentence spoken
        list(self.chunks("Hello."))  # warm-up: the first sentence otherwise takes ~9 s while kernels compile
        self.out = sounddevice.OutputStream(samplerate=self.sr, channels=1, dtype="float32", callback=self._play)
        self.out.start()
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
        with self.lock:
            self.buf, self.playing = self.buf[:0], False

    def wait(self):
        """Until everything said so far has been played."""
        self.queue.join()
        while len(self.buf):
            time.sleep(0.05)
        time.sleep(self.out.latency)

    def _play(self, out, frames, time_info, status):
        """Audio device callback: the next buffered speech, or silence while (re)buffering."""
        with self.lock:
            n = min(frames, len(self.buf)) if self.playing else 0
            out[:n, 0], out[n:] = self.buf[:n], 0
            self.buf = self.buf[n:]
            if n < frames and not self.done:  # ran dry mid-sentence: pause and rebuffer, rather than stutter
                self.playing = False

    def _run(self):
        while True:
            text = self.queue.get()
            self.cancel.clear()
            expected = len(text) * self.sec_per_char  # seconds of speech this sentence will be
            t0, made = time.time(), 0
            with self.lock:  # keep playing only if the previous sentence is still being heard
                self.done, self.playing = False, len(self.buf) > 0
            for chunk in self.chunks(text):
                if self.cancel.is_set():
                    break
                made += len(chunk)
                rate = made / self.sr / (time.time() - t0)  # seconds of speech generated per second
                left = max(expected - made / self.sr, 0)  # still to generate
                with self.lock:
                    self.buf = np.concatenate([self.buf, chunk])
                    # Buffer b lasts while the rest is generated if b >= left * (1 - rate) / rate (+20% margin).
                    need = max(self.MIN_BUFFER, 1.2 * left * (1 - rate) / rate)
                    self.playing = self.playing or len(self.buf) / self.sr >= need
            with self.lock:
                self.done, self.playing = True, not self.cancel.is_set()
            if made and not self.cancel.is_set():
                self.sec_per_char = 0.7 * self.sec_per_char + 0.3 * made / self.sr / len(text)
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
        voice.wait()
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

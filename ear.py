"""What the presenter says, mouths or types, as text.

Each input yields (text, perf_counter time the presenter stopped, kind), stamped at the last voiced sound so latencies are
honest. kind is "live" (the words so far), "pause" (a short pause mid-sentence) or "final".
"""
import collections
import queue
import sys
import time
from pathlib import Path

import numpy as np

STT = "mlx-community/parakeet-tdt-0.6b-v3"
LIPREAD = Path(__file__).parent / "lipread"


class Ear:
    """Speech to text with Parakeet on MLX: streamed from the microphone, or one file at a time for evals."""

    def __init__(self):
        from parakeet_mlx import from_pretrained
        self.model = from_pretrained(STT)
        self.sr = self.model.preprocessor_config.sample_rate
        self.text(np.zeros(self.sr, np.float32))  # warm-up: the first pass compiles kernels
        import mlx.core as mx
        with self.model.transcribe_stream(context_size=(256, 16)) as warm:
            warm.add_audio(mx.zeros(self.sr // 2))

    def text(self, audio) -> str:
        import mlx.core as mx
        from parakeet_mlx.audio import get_logmel
        return self.model.generate(get_logmel(mx.array(audio), self.model.preprocessor_config))[0].text.strip()

    def file(self, path: str) -> str:
        from parakeet_mlx.audio import load_audio
        return self.text(load_audio(Path(path), self.sr))

    def microphone(self, focus: float = 0.3, early_s: float = 0.3, pause_s: float = 0.6, max_s: float = 15.0):
        """Yields (text, when you stopped, kind): "live" each time the words so far change, "pause" at the first 0.3 s
        pause, "final" after 0.6 s. Audio is transcribed as it arrives (Parakeet's streaming mode), so the text is ready
        the moment you stop. A sound counts as speech when it is at least `focus` times as loud as your recent speech,
        so voices across the room neither open the gate nor hold it open."""
        import mlx.core as mx
        import sounddevice as sd
        blocks: queue.Queue = queue.Queue()
        block = self.sr // 10
        floor, voice, before = 0.003, 0.0, collections.deque(maxlen=3)
        stream, fed, levels, n, quiet, early, text, last_loud = None, [], [], 0, 0, False, "", 0.0
        with sd.InputStream(samplerate=self.sr, channels=1, dtype="float32", blocksize=block,
                            callback=lambda d, *_: blocks.put(d[:, 0].copy())):
            print("listening... (ctrl-c to stop)")
            while True:
                b = blocks.get()
                rms = float(np.sqrt(np.mean(b ** 2)))
                loud = rms > max(3 * floor, focus * voice, 0.01)
                if stream is None:
                    # ponytail: energy gate with a slow noise floor; swap for Silero VAD if rooms get noisy
                    floor = 0.995 * floor + 0.005 * rms
                    before.append(b)
                    if not loud:
                        continue
                    stream = self.model.transcribe_stream(context_size=(256, 16)).__enter__()
                    fed, levels, n, quiet, early, text = list(before), [], 0, 0, False, ""  # with the word's quiet start
                else:
                    fed.append(b)
                n += 1
                if loud:
                    levels.append(rms)
                    last_loud, quiet, early = time.perf_counter(), 0, False
                else:
                    quiet += 1
                pause = not early and quiet * 0.1 >= early_s
                end = quiet * 0.1 >= pause_s or n * 0.1 >= max_s
                if fed and (len(fed) >= 2 or pause or end):
                    stream.add_audio(mx.array(np.concatenate(fed)))
                    fed = []
                    words = stream.result.text.strip()
                    if words != text and len(levels) > 2:
                        text = words
                        yield text, last_loud, "live"
                if pause and len(levels) > 3:
                    early = True
                    yield text, last_loud, "pause"
                if end:
                    if text:  # every utterance that yielded words ends with a final, which resets the brain's memory of it
                        yield text, last_loud, "final"
                        level = float(np.percentile(levels, 80))
                        if level >= 0.6 * voice:  # learn your level from your own speech, not from quieter voices
                            voice = level if voice == 0 else 0.8 * voice + 0.2 * level
                    stream.__exit__(None, None, None)
                    stream = None
                    before.clear()


def keys(args):
    print("type what you would say, one sentence per line (ctrl-d to stop)")
    for line in sys.stdin:
        yield line.strip(), time.perf_counter(), "final"


def lips(args):
    sys.path.insert(0, str(LIPREAD))
    import lipread
    print("mouth words at the camera (ctrl-c to stop)")
    settings = lipread.Settings() if args.sensitivity is None else lipread.Settings(sensitivity=args.sensitivity)
    for text, stopped in lipread.listen(settings, args.camera, args.preview):
        yield text, stopped, "final"


INPUTS = {"mic": lambda args: Ear().microphone(args.focus), "lips": lips, "keys": keys}

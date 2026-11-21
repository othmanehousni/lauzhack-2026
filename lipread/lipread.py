"""Silent speech-to-text: read lips from the webcam and print the words while they are being mouthed.

  uv run lipread/lipread.py live [--preview]             webcam -> text
  uv run lipread/lipread.py file VIDEO                   any talking-head video -> text, through the same streaming path
  uv run --group eval lipread/lipread.py eval VIDEO...   word error rate against the audio track, and latency
  uv run lipread/lipread.py gate VIDEO...                lip-motion gate against the audio transcript, from the caches
"""

import argparse
import json
import queue
import re
import shutil
import sys
import threading
import time
from collections import deque
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path

import cv2
import mlx.core as mx
import numpy as np

from model import LipReader, agreed_words

FPS = 25
CACHE = Path(__file__).parent / "cache"
ASR = "mlx-community/whisper-large-v3-turbo"
# Rigid points of the mean face Auto-AVSR aligns to, in its 256x256 frame: eye centres, top of the nose bridge, nose base.
MEAN_FACE = np.array([[102.07, 94.27], [156.36, 93.58], [128.73, 95.36], [129.00, 135.90]])


@dataclass(frozen=True)
class Settings:
    hop: int = 5  # frames between hypothesis refreshes (5 = 200 ms)
    final_beam: int = 10  # beam width once the utterance has ended; refreshes before that are greedy CTC
    lm_weight: float = 0.3
    pause: float = 0.7  # seconds of still lips that end an utterance
    sensitivity: float = 3.0  # lip motion must exceed this multiple of the idle jitter
    max_seconds: float = 12.0


@dataclass(frozen=True)
class Mouth:
    """One video frame after face tracking."""
    crop: np.ndarray  # (88, 88) uint8, aligned to the mean face
    lips: np.ndarray  # inner-lip gap, outer-lip height, mouth width, in units of the distance between the eyes


@dataclass(frozen=True)
class End:
    """The utterance is over; its last `still` frames showed no lip motion."""
    still: int
    closed: float = field(default_factory=time.perf_counter)  # when the gate closed


@dataclass(frozen=True)
class Update:
    committed: str  # words that will not change any more
    tentative: str  # current guess for the rest
    final: bool = False
    stopped: float = 0.0  # for a final update, when the gate closed (perf_counter)


def similarity(src: np.ndarray, dst: np.ndarray) -> np.ndarray:
    """Least-squares scale + rotation + translation taking src points onto dst, as a 2x3 matrix."""
    s, d = src - src.mean(0), dst - dst.mean(0)
    a = (s * d).sum() / (s * s).sum()
    b = (s[:, 0] * d[:, 1] - s[:, 1] * d[:, 0]).sum() / (s * s).sum()
    rotation = np.array([[a, -b], [b, a]])
    return np.hstack([rotation, (dst.mean(0) - rotation @ src.mean(0))[:, None]])


class MouthTracker:
    """Apple Vision face landmarks -> a mouth crop aligned the way the recogniser was trained."""

    def __init__(self, smooth: int = 7):
        import Quartz  # macOS only; everything else in this file also runs on Linux for cloud evaluation
        import Vision
        self.quartz, self.gray = Quartz, Quartz.CGColorSpaceCreateDeviceGray()
        self.request = Vision.VNDetectFaceLandmarksRequest.alloc().init()
        self.request.setConstellation_(Vision.VNRequestFaceLandmarksConstellation65Points)
        self.handler = Vision.VNSequenceRequestHandler.alloc().init()
        self.recent: deque[np.ndarray] = deque(maxlen=smooth)

    def landmarks(self, gray: np.ndarray) -> np.ndarray | None:
        """Four rigid anchors then 20 lip points of the largest face, in pixels."""
        h, w = gray.shape
        q = self.quartz
        image = q.CGImageCreate(w, h, 8, 8, w, self.gray, q.kCGImageAlphaNone, q.CGDataProviderCreateWithCFData(gray.tobytes()),
                                None, False, q.kCGRenderingIntentDefault)
        self.handler.performRequests_onCGImage_error_([self.request], image, None)
        faces = [f for f in self.request.results() or [] if f.landmarks()]
        if not faces:
            return None
        found = max(faces, key=lambda f: f.boundingBox().size.width).landmarks()

        def points(region) -> np.ndarray:
            p = region.pointsInImageOfSize_((w, h))
            return np.array([(p[i].x, h - p[i].y) for i in range(region.pointCount())])

        eyes = sorted((points(found.leftEye()).mean(0), points(found.rightEye()).mean(0)), key=lambda eye: eye[0])
        return np.vstack([*eyes, points(found.noseCrest())[0], points(found.nose())[2:7].mean(0), points(found.outerLips()), points(found.innerLips())])

    def track(self, frame: np.ndarray) -> Mouth | None:
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY) if frame.ndim == 3 else frame
        now = self.landmarks(gray)
        if now is None:
            self.recent.clear()
            return None
        self.recent.append(now)
        # Average the face shape over the last frames but keep this frame's head position, as the training pipeline does.
        pts = np.mean(self.recent, axis=0)
        pts += now[:4].mean(0) - pts[:4].mean(0)
        warp = similarity(pts[:4], MEAN_FACE)
        centre = warp @ np.append(pts[4:].mean(0), 1)
        warp[:, 2] -= np.round(centre) - 44
        outer, inner, eye_distance = now[4:18], now[18:], np.linalg.norm(now[1] - now[0])
        lips = np.array([np.ptp(inner[:, 1]), np.ptp(outer[:, 1]), np.ptp(outer[:, 0])]) / eye_distance
        return Mouth(cv2.warpAffine(gray, warp, (88, 88), flags=cv2.INTER_LINEAR), lips)


class Segmenter:
    """Lip-motion gate. Idle until the lips move, then speaking until they have been still for `pause` seconds."""

    def __init__(self, settings: Settings, preroll: int = 8):
        self.pause, self.limit, self.sensitivity = int(settings.pause * FPS), int(settings.max_seconds * FPS), settings.sensitivity
        self.preroll: deque[np.ndarray] = deque(maxlen=preroll)
        self.last: np.ndarray | None = None
        self.level, self.floor = 0.0, 0.01
        self.speaking, self.still, self.length = False, 0, 0

    def feed(self, mouth: Mouth | None) -> Sequence[np.ndarray | End]:
        """Crops to append to the current utterance, and an End when it is over."""
        if mouth is None:
            out = [End(self.still)] if self.speaking else []
            self.preroll.clear()
            self.last, self.speaking, self.level = None, False, 0.0
            return out
        motion = 0.0 if self.last is None else float(np.abs(mouth.lips - self.last).sum())
        self.last = mouth.lips
        self.level = 0.6 * self.level + 0.4 * motion
        # Falls fast and rises slowly, so it stays on the jitter of still lips even for a speaker who never pauses. An average
        # of the motion seen while idle climbs to speech level within seconds and keeps the gate shut.
        # ponytail: a low-tail estimate, so tracking jitter above ~0.01 per frame (synthetic noise) opens the gate on still
        # lips; raise --sensitivity, or calibrate on a few seconds of still lips, for a webcam that noisy
        self.floor += (motion - self.floor) * (0.3 if motion < self.floor else 0.005)
        moving = self.level > self.threshold
        if not self.speaking:
            self.preroll.append(mouth.crop)
            if not moving:
                return []
            self.speaking, self.still, self.length = True, 0, len(self.preroll)
            out = list(self.preroll)
            self.preroll.clear()
            return out
        self.still = 0 if moving else self.still + 1
        self.length += 1
        # The longer the utterance, the shorter the lull that ends it: past two thirds of the limit a 160 ms lull is enough.
        # ponytail: at the hard limit the cut can still split a word; cut at the last committed word if long dictation matters
        pause = self.pause if self.length < self.limit * 2 // 3 else min(self.pause, 4)
        if self.still < pause and self.length < self.limit:
            return [mouth.crop]
        self.speaking = False
        return [mouth.crop, End(self.still)]

    @property
    def threshold(self) -> float:
        return max(self.sensitivity * self.floor, 0.02)


class Utterance:
    """One spoken stretch. Front-end features are computed once per frame; the hypothesis is re-decoded on every refresh."""

    def __init__(self, reader: LipReader, settings: Settings):
        self.reader, self.settings = reader, settings
        self.crops: list[np.ndarray] = []
        self.feats = mx.zeros((0, 512))  # frames whose two future neighbours had already arrived
        self.committed: list[int] = []  # tokens two consecutive hypotheses agreed on
        self.previous: list[int] = []
        self.refreshed = 0

    def features(self) -> mx.array:
        done, n = self.feats.shape[0], len(self.crops)
        lo = max(done - 2, 0)
        new = self.reader.features(np.stack(self.crops[lo:]))[done - lo:]
        feats = mx.concatenate([self.feats.astype(new.dtype), new])
        self.feats = feats[:max(n - 2, done)]
        mx.eval(feats)
        return feats

    def partial(self) -> Update:
        reader = self.reader
        self.refreshed = len(self.crops)
        hyp, n = reader.greedy(reader.encode(self.features())), len(self.committed)
        # Greedy CTC is not forced through the committed words, so a hypothesis that rewrites them only moves the tentative tail.
        if hyp[:n] == self.committed:
            self.committed = hyp[:agreed_words(reader.tokens, hyp, self.previous, n)]
        self.previous = hyp
        committed = reader.text(self.committed)
        return Update(committed, reader.text(self.committed + hyp[len(self.committed):])[len(committed):])

    def final(self, end: End) -> Update:
        reader, s = self.reader, self.settings
        keep = max(len(self.crops) - max(end.still - 5, 0), 1)
        hyp = reader.decode(reader.encode(self.features()[:keep]), beam=s.final_beam, lm_weight=s.lm_weight)[0][1]
        return Update(reader.text(hyp), "", final=True, stopped=end.closed)


class Recogniser:
    """Turns the segmenter's event stream into text updates."""

    def __init__(self, reader: LipReader, settings: Settings):
        self.reader, self.settings = reader, settings
        self.utterance: Utterance | None = None

    def feed(self, events: Sequence[np.ndarray | End]) -> list[Update]:
        updates = []
        for event in events:
            if isinstance(event, End):
                if self.utterance:
                    updates.append(self.utterance.final(event))
                self.utterance = None
            else:
                self.utterance = self.utterance or Utterance(self.reader, self.settings)
                self.utterance.crops.append(event)
        if self.utterance and len(self.utterance.crops) - self.utterance.refreshed >= self.settings.hop:
            updates.append(self.utterance.partial())
        return updates


def frames(source: int | Path):
    """Frames from a camera or a video file, resampled to 25 fps by timestamp."""
    live = isinstance(source, int)
    cap = cv2.VideoCapture(source, cv2.CAP_AVFOUNDATION) if live else cv2.VideoCapture(str(source))
    if live:
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
    if not cap.isOpened():
        sys.exit(f"cannot open {source}" + (" (allow camera access for this terminal in System Settings > Privacy & Security > Camera)" if live else ""))
    native, due, count = cap.get(cv2.CAP_PROP_FPS) or 30.0, None, 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        t = time.perf_counter() if live else count / native
        count += 1
        due = t if due is None else due
        while t >= due:
            yield frame
            due += 1 / FPS


def render(update: Update) -> None:
    if update.final:
        print(f"\r\033[K{update.committed}" if update.committed else "\r\033[K", end="\n" if update.committed else "", flush=True)
        return
    tail = (update.committed + update.tentative)[-(shutil.get_terminal_size().columns - 1):]
    split = max(len(tail) - len(update.tentative), 0)
    print(f"\r\033[K{tail[:split]}\033[2m{tail[split:]}\033[0m", end="", flush=True)


def listen(settings: Settings, camera: int = 0, preview: bool = False):
    """Webcam -> (text, perf_counter time the gate closed) per utterance, printing the running guess meanwhile.

    Capture, tracking and any preview window stay on the calling thread (macOS wants cv2 windows on the main thread);
    recognition runs on a worker so a slow decode never drops frames.
    """
    reader, tracker, segmenter = LipReader(), MouthTracker(), Segmenter(settings)
    reader.greedy(reader.encode(reader.features(np.zeros((5, 88, 88), np.uint8))))  # warm-up: the first pass compiles kernels
    events: queue.Queue = queue.Queue()
    finals: queue.Queue = queue.Queue()

    def work() -> None:
        recogniser = Recogniser(reader, settings)
        while True:
            batch = [events.get()]
            while not events.empty():
                batch.append(events.get_nowait())
            for update in recogniser.feed(batch):
                render(update)
                if update.final and update.committed:
                    finals.put((update.committed, update.stopped))

    threading.Thread(target=work, daemon=True).start()
    for frame in frames(camera):
        mouth = tracker.track(frame)
        for event in segmenter.feed(mouth):
            events.put(event)
        while not finals.empty():
            yield finals.get_nowait()
        if preview:
            view = cv2.resize(mouth.crop, (352, 352), interpolation=cv2.INTER_NEAREST) if mouth else np.zeros((352, 352), np.uint8)
            bar = int(min(segmenter.level / (2 * segmenter.threshold), 1.0) * 352)
            cv2.rectangle(view, (0, 340), (bar, 352), 255 if segmenter.speaking else 128, -1)
            cv2.line(view, (176, 336), (176, 352), 255, 1)
            cv2.imshow("lipread: what the model sees (bar = lip motion, tick = threshold)", view)
            if cv2.waitKey(1) == 27:
                return


def run_live(args, settings: Settings) -> None:
    print("mouth words at the camera; text appears as you go (ctrl-c to stop)")
    try:
        for _ in listen(settings, args.camera, args.preview):
            pass  # the worker already printed it
    except KeyboardInterrupt:
        pass
    print()


def simulate(reader: LipReader, settings: Settings, timeline: list[tuple[float, np.ndarray | End]], show=None) -> tuple[list[str], list[float], list[float]]:
    """Replay timed events against a clock that also advances by the measured compute time, as the live worker would see them.

    Returns the final texts, the delay of each refresh behind its newest frame, and the delay of each final result.
    """
    recogniser, finals, lags, final_lags = Recogniser(reader, settings), [], [], []
    now, i = 0.0, 0
    while i < len(timeline):
        now = max(now, timeline[i][0])
        batch = [event for t, event in timeline[i:] if t <= now]
        i += len(batch)
        started = time.perf_counter()
        updates = recogniser.feed(batch)
        now += time.perf_counter() - started
        for update in updates:
            (final_lags if update.final else lags).append(now - timeline[i - 1][0])
            if update.final and update.committed:
                finals.append(update.committed)
            if show:
                show(update)
    return finals, lags, final_lags


def tracked(video: Path) -> list[Mouth | None]:
    """Mouth crops of every 25 fps frame of a video, cached on disk."""
    cached = CACHE / f"{video.stem}.npz"
    if not cached.exists():
        CACHE.mkdir(exist_ok=True)
        tracker = MouthTracker()
        mouths = [tracker.track(frame) for frame in frames(video)]
        np.savez_compressed(cached, found=np.array([m is not None for m in mouths]),
                            crops=np.stack([m.crop if m else np.zeros((88, 88), np.uint8) for m in mouths]),
                            lips=np.stack([m.lips if m else np.zeros(3) for m in mouths]))
    z = np.load(cached)
    return [Mouth(crop, lips) if found else None for crop, lips, found in zip(z["crops"], z["lips"], z["found"])]


def timeline(mouths: list[Mouth | None], settings: Settings) -> list[tuple[float, np.ndarray | End]]:
    segmenter = Segmenter(settings)
    return [(i / FPS, event) for i, mouth in enumerate(mouths) for event in segmenter.feed(mouth)]


def run_file(args, settings: Settings) -> None:
    reader = LipReader()
    _, lags, final_lags = simulate(reader, settings, timeline(tracked(args.video), settings), render)
    print(f"\nrefresh delay p50 {np.median(lags) * 1e3:.0f} ms, p95 {np.percentile(lags, 95) * 1e3:.0f} ms; final result {np.median(final_lags) * 1e3:.0f} ms after the utterance ended")


def words(text: str) -> list[str]:
    return re.sub(r"[^a-z' ]", " ", text.lower().replace("-", " ")).split()


def edits(ref: list[str], hyp: list[str]) -> int:
    """Word-level Levenshtein distance."""
    row = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        diagonal, row[0] = row[0], i
        for j, h in enumerate(hyp, 1):
            diagonal, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, diagonal + (r != h))
    return row[-1]


def reference(video: Path, asr: str) -> list[tuple[float, float, str]]:
    """What is said in the video and when, from speech recognition on its audio track."""
    cached = CACHE / f"{video.stem}.json"
    if not cached.exists():
        import mlx_whisper
        CACHE.mkdir(exist_ok=True)
        result = mlx_whisper.transcribe(str(video), path_or_hf_repo=asr, word_timestamps=True)
        cached.write_text(json.dumps([(s["start"], s["end"], s["text"]) for s in result["segments"]]))
    return [tuple(s) for s in json.loads(cached.read_text())]


DECODERS = {
    "greedy CTC": lambda reader, enc: reader.greedy(enc),
    "beam 5": lambda reader, enc: reader.decode(enc, beam=5, lm_weight=0)[0][1],
    "beam 5 + LM": lambda reader, enc: reader.decode(enc, beam=5)[0][1],
    "beam 10 + LM": lambda reader, enc: reader.decode(enc, beam=10)[0][1],
    "beam 40 + LM": lambda reader, enc: reader.decode(enc)[0][1],
}


def run_eval(args, settings: Settings) -> None:
    reader = LipReader()
    errors = {name: 0 for name in [*DECODERS, "streaming"]}
    seconds = {name: 0.0 for name in [*DECODERS, "encoder"]}
    total = used = skipped = free_errors = free_total = utterances = 0
    lags, final_lags, free_lags, free_final_lags = [], [], [], []
    for video in args.videos:
        mouths, sentences = tracked(video), reference(video, args.asr)
        spoken = words(" ".join(text for _, _, text in sentences))
        finals, video_lags, video_final_lags = simulate(reader, settings, timeline(mouths, settings))
        free_errors += edits(spoken, words(" ".join(finals)))
        free_total += len(spoken)
        utterances += len(finals)
        free_lags += video_lags
        free_final_lags += video_final_lags
        if args.verbose:
            print(f"\n[{video.stem}, cut by the lip-motion gate]" + "".join(f"\n  {text}" for text in finals))
        for start, end, text in sentences:
            ref = words(text)
            clip = mouths[max(int((start - 0.1) * FPS), 0):int((end + 0.1) * FPS) + 1]
            faces = [m.crop for m in clip if m]
            if not 1.0 <= end - start <= settings.max_seconds or len(ref) < 3 or re.search(r"\d", text) or len(faces) < len(clip):
                skipped += 1
                continue
            crops = np.stack(faces)
            t = time.perf_counter()
            enc = reader.encode(reader.features(crops))
            mx.eval(enc)
            seconds["encoder"] += time.perf_counter() - t
            hyps = {}
            for name, decode in DECODERS.items():
                t = time.perf_counter()
                hyps[name] = reader.text(decode(reader, enc))
                seconds[name] += time.perf_counter() - t
            finals, clip_lags, clip_final_lags = simulate(reader, settings, [*((i / FPS, crop) for i, crop in enumerate(crops)), (len(crops) / FPS, End(0))])
            hyps["streaming"] = " ".join(finals)
            lags += clip_lags
            final_lags += clip_final_lags
            for name, hyp in hyps.items():
                errors[name] += edits(ref, words(hyp))
            total += len(ref)
            used += 1
            if args.verbose:
                print(f"\n[{video.stem} {start:.1f}-{end:.1f}s]\n  ref       {' '.join(ref)}" + "".join(f"\n  {name:9.9} {hyp}" for name, hyp in hyps.items()))
    if not used:
        sys.exit("no usable sentences (need a visible face throughout each one)")
    print(f"\n{used} sentences, {total} words ({skipped} skipped: no face, digits, too short or too long)")
    print(f"encoder {seconds['encoder'] / used * 1e3:.0f} ms per sentence")
    for name in DECODERS:
        print(f"{name:14} WER {errors[name] / total:6.1%}   decode {seconds[name] / used * 1e3:5.0f} ms per sentence")
    delays = lambda a, b: f"refresh delay p50 {np.median(a) * 1e3:.0f} ms, p95 {np.percentile(a, 95) * 1e3:.0f} ms; final {np.median(b) * 1e3:.0f} ms after the last frame"
    print(f"{'streaming':14} WER {errors['streaming'] / total:6.1%}   {delays(lags, final_lags)}")
    print(f"\nwhole video, utterances cut by the lip-motion gate: {utterances} utterances, {free_total} words")
    print(f"{'free-running':14} WER {free_errors / free_total:6.1%}   {delays(free_lags, free_final_lags)}")


def run_gate(args, settings: Settings) -> None:
    """Score the lip-motion gate alone on cached tracking: no recogniser, no model."""
    for video in args.videos:
        mouths, sentences = tracked(video), reference(video, ASR)
        speech, gate, lengths, start = np.zeros(len(mouths), bool), np.zeros(len(mouths), bool), [], None
        for begin, end, _ in sentences:
            speech[int(begin * FPS):int(end * FPS) + 1] = True
        segmenter = Segmenter(settings)
        for i, mouth in enumerate([*mouths, None]):
            events = segmenter.feed(mouth)
            crops = sum(not isinstance(e, End) for e in events)
            start = i - crops + 1 if start is None and crops else start
            if any(isinstance(e, End) for e in events):
                gate[start:i + (crops > 0)] = True
                lengths.append((i + (crops > 0) - start) / FPS)
                start = None
        face = np.array([m is not None for m in mouths])
        recall, precision = (gate & speech & face).sum() / (speech & face).sum(), (gate & speech).sum() / max(gate.sum(), 1)
        print(f"{video.stem:16} speech frames in an open gate {recall:4.0%}, open-gate frames that are speech {precision:4.0%}; "
              f"{len(lengths)} utterances, median {np.median(lengths or [0]):.1f} s")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for field, default in Settings().__dict__.items():
        p.add_argument(f"--{field.replace('_', '-')}", type=type(default), default=default)
    sub = p.add_subparsers(dest="cmd", required=True)
    live = sub.add_parser("live")
    live.add_argument("--camera", type=int, default=0)
    live.add_argument("--preview", action="store_true", help="show the mouth crop the model sees and the lip-motion level")
    sub.add_parser("file").add_argument("video", type=Path)
    ev = sub.add_parser("eval")
    ev.add_argument("videos", type=Path, nargs="+")
    ev.add_argument("-v", "--verbose", action="store_true")
    ev.add_argument("--asr", default=ASR, help="mlx-whisper model (repo or folder) that transcribes the audio track")
    sub.add_parser("gate", help="replay the lip-motion gate over cached tracking against the audio transcript").add_argument("videos", type=Path, nargs="+")
    args = p.parse_args()
    settings = Settings(**{field: getattr(args, field) for field in Settings().__dict__})
    {"live": run_live, "file": run_file, "eval": run_eval, "gate": run_gate}[args.cmd](args, settings)


if __name__ == "__main__":
    main()

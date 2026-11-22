"""Live silent lip reading from your webcam, spoken in your own voice. Runs locally on MLX.

  .venv/bin/python lipread.py            webcam, always listening: SPACE pause/resume, Q quit
  .venv/bin/python lipread.py clip.mp4   transcribe a video file the same way
  .venv/bin/python lipread.py --record   record yourself mouthing short prompts (for --calibrate)
  .venv/bin/python lipread.py --read     same, through the long passage in passage.txt (resumable)
  .venv/bin/python lipread.py --onboard  day one, ~4 min: the paragraph aloud (voice + lips), then silently
  .venv/bin/python lipread.py --calibrate   train your lip + voice profile on Modal; inference stays local
  .venv/bin/python lipread.py --record-eval   record the rare-word test sentences (calib/eval.npz, never trained on)
"""
import difflib
import json
import os
import queue
import re
import subprocess
import sys
import textwrap
import threading
import time
import warnings

import corrector
import cv2
import numpy as np
import mlx.core as mx

warnings.filterwarnings("ignore")
os.environ.setdefault("GLOG_minloglevel", "2")  # quiet mediapipe
VSR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vsr")
sys.path.insert(0, VSR)
from pipelines.detectors.mediapipe.detector import LandmarksDetector  # noqa: E402
from pipelines.detectors.mediapipe.video_process import VideoProcess  # noqa: E402
import vsr_mlx  # noqa: E402

FPS = 25  # the model was trained on 25 fps video
WIDTH = 640  # frames are downscaled to this before storing; plenty for a face at arm's length
# Streaming knobs, in frames (25 = 1 s)
STEP = 5  # re-read the lips this often while you mouth (0.2 s)
PAUSE = 20  # this long without new words ends a sentence
PRE, POST = 12, 10  # context kept before the first and after the last word of a sentence
KEEP = 50  # history kept while nothing is being said, so a sentence's start is never lost
MAX = 10 * FPS  # force a sentence break; the model was trained on short clips
HERE = os.path.dirname(os.path.abspath(__file__))
PERSONAL = os.path.join(HERE, "profile", "lips.safetensors")  # your lip profile, from --calibrate
CALIB = os.path.join(HERE, "calib", "clips.npz")
PASSAGE = os.path.join(HERE, "passage.txt")  # ~390 sentences covering 99% of the 1,000 most common English words
RARE = os.path.join(HERE, "rare_words.txt")  # names, brands, jargon: tests words the lip model never saw
EVAL = os.path.join(HERE, "calib", "eval.npz")  # kept apart from training data
ONBOARD = os.path.join(HERE, "onboarding.txt")  # day-one paragraph: every English sound and lip shape, ~240 words
ONBOARD_ALOUD = os.path.join(HERE, "calib", "onboard_aloud.npz")
ONBOARD_SILENT = os.path.join(HERE, "calib", "onboard_silent.npz")
VOICE = os.path.join(HERE, "profile")  # voice.wav + voice.txt: the clip speak.py clones
VOICE_CLIPS = os.path.join(HERE, "profile", "voice")  # every sentence read aloud at onboarding, for voice training
WHISPER = "mlx-community/whisper-large-v3-turbo"  # local speech recognizer, only used to time words read aloud
PROMPTS = """can you send me the file when you get a chance
i will be there in about ten minutes
what time does the meeting start tomorrow
please turn off the lights before you leave
i think we should order pizza tonight
where did you put my keys
the weather is really nice today
let me know if you need any help
i am running a little late this morning
could you call me back after lunch
thank you so much for your help
how was your weekend
we need to buy milk and bread
the train leaves at half past seven
i forgot my password again
my phone battery is almost dead
do you want to grab a coffee later
she said the project is almost finished
open the window it is too hot in here
we should talk about this next week
the movie starts at eight o'clock
i have a doctor's appointment on friday
can you read that message out loud
please put the book back on the shelf
he works from home on mondays
did you remember to lock the door
i would like a glass of water please
the children are playing in the garden
we are going to visit my parents
that sounds like a great idea
my brother lives near the beach
i need to finish this report today
the store closes at nine tonight
it was nice to meet you
how much does this jacket cost
the dog is sleeping on the sofa
please write your name at the top
i can't find my glasses anywhere
we missed the bus this morning
my favourite colour is blue
turn left at the next traffic light
the soup needs a bit more salt
can we move the call to thursday
i will send you the pictures tonight
the printer is out of paper
she bought a new bike last week
could you speak a little slower
we watched a film about whales
remember to water the plants
i am really looking forward to the trip
the kitchen floor is still wet
he plays the piano every evening
let's meet at the front entrance
the price went up again this year
i'll check my calendar and get back to you
a big black bear sat on a pebble
five fine fresh fish for sale
the quick brown fox jumps over the lazy dog
these shoes are much too small for me
we should leave early to avoid traffic
i just sent you an email about the budget
my mother makes the best apple pie
what do you want for dinner tonight
the bathroom light keeps flickering
we bought fresh vegetables at the market
can you pick up the kids from school
please share your screen with the team
the flight was delayed by two hours
i need a few more minutes to think
which bus goes to the city centre
my laptop needs to be updated tonight
the baby finally fell asleep
we should book the hotel this week
she always wears a purple scarf
how long have you been waiting
put the milk back in the fridge
the volume is way too loud
our neighbours are moving to spain
i will text you when i arrive
he forgot to feed the cat again
can i borrow your phone charger
the meeting has been moved to monday
we need more chairs for the party
my feet are freezing in these boots
the bread is still warm from the oven
let's take a short break
i'm not sure that is a good idea
the photos from the wedding look amazing
could you pass me the pepper please
we ran out of coffee this morning
the bridge is closed for repairs
my sister is visiting next month
please remind me to call the bank
the shop on the corner sells flowers
which file should i send to the client
i lost my wallet at the station
we have plenty of time before the show
the windows need cleaning
how do you spell your last name
his voice sounds very familiar
please don't forget your umbrella
the room was full of balloons
i'll be working late on thursday
we should probably go to bed
the football match starts at three
my father fixed the broken fence
what's the password for the wifi
the bill comes to forty five pounds
i feel much better than yesterday
she moved to a smaller flat
please stop the music for a moment
the map shows a path through the woods
we will finish the rest tomorrow
my phone fell in the bath
the bakery opens very early
could you check the spelling for me
i miss the summer evenings
very few people came to the meeting
can you move the box over there
both of my brothers play the violin""".split("\n")  # ~120 everyday sentences, covering most lip shapes


class LipReader:
    """Finds the face and crops the mouth (MediaPipe, CPU); the lip model itself runs in MLX (vsr_mlx.LipModel):
    encode_crops, ctc, text, collapse, guesses, align and word_frames come from there."""

    def __init__(self, load_model=True):
        self.detector = LandmarksDetector()
        self.crop = VideoProcess(convert_gray=True)
        self.lip = None
        if load_model:
            self.load_model()

    def load_model(self):
        if self.lip is None:
            personal = PERSONAL if os.path.exists(PERSONAL) else None  # fitted to your lips by --calibrate
            self.lip = vsr_mlx.LipModel(personal)
            if personal:
                print("Using your personal lip profile.", flush=True)

    def __getattr__(self, name):  # model methods; only called for names LipReader doesn't define itself
        lip = self.__dict__.get("lip")
        if lip is None:
            raise AttributeError(name)
        return getattr(lip, name)

    def landmarks(self, frame):
        d = self.detector
        lm = d.detect([frame], d.full_range_detector)[0]
        return lm if lm is not None else d.detect([frame], d.short_range_detector)[0]

    def encode(self, frames, landmarks):
        """Frames -> encoder output, one row per frame. None if no face was found."""
        if all(lm is None for lm in landmarks):
            return None
        return self.encode_crops(self.crop(np.stack(frames), list(landmarks)))


class Stream:
    """Buffers a 25 fps stream, shows live text, and cuts it into sentences where the lips pause."""

    def __init__(self, reader):
        self.reader = reader
        self.frames, self.lms, self.pushed, self.dropped = [], [], 0, 0
        self.lock = threading.Lock()
        self.partial, self.draft = "", None

    def push(self, frame, lm):
        with self.lock:
            self.frames.append(frame)
            self.lms.append(lm)
            self.pushed += 1

    def drop(self, k):
        k = max(k, 0)
        with self.lock:
            del self.frames[:k], self.lms[:k]
            self.dropped += k

    def step(self, flush=False):
        """Read the buffered lips. Updates self.partial; returns a finished sentence as (frames, lms, key), or None.
        Also sets self.draft: the sentence exactly as it will be cut if the lips stay still, so it can be decoded
        while the pause is still being confirmed. key = the sentence's frame span, to match a draft to its cut."""
        with self.lock:
            frames, lms = self.frames[:], self.lms[:]
        n, self.draft = len(frames), None
        enc = self.reader.encode(frames, lms) if n >= STEP else None
        ids = self.reader.ctc(enc) if enc is not None else []
        spoken = [t for t, i in enumerate(ids) if i]
        if not spoken:
            self.drop(n if flush else n - KEEP)
            self.partial = ""
            return None
        self.partial = self.reader.collapse(ids)
        first, last = spoken[0], spoken[-1]
        start = max(0, first - PRE)
        if not (flush or n - 1 - last >= PAUSE or n >= MAX):
            if n >= last + POST:  # all of this sentence's frames are in: it ends here unless the lips move again
                end = last + POST
                self.draft = frames[start:end], lms[start:end], (self.dropped + start, self.dropped + end)
            return None
        end = n if flush else min(n, last + POST)
        key = (self.dropped + start, self.dropped + end)
        self.drop(end)
        self.partial = ""
        return frames[start:end], lms[start:end], key


def load_corrector():
    """The fine-tuned MLX corrector (train_corrector.py), if it has been trained and downloaded."""
    if os.path.isdir(corrector.MODEL):
        extra = " and your dictionary.txt" if os.path.exists(corrector.DICTIONARY) else ""
        print(f"Correcting with the MLX corrector{extra}.", flush=True)
        return corrector.Corrector()
    print("No corrector in models/corrector: showing the lip model's top guess. See train_corrector.py.", flush=True)


def load_voice():
    """Speech in your own voice once onboarding has saved a voice profile (V toggles it): the voice fine-tuned
    on Modal if --calibrate made one, else zero-shot cloning of the 15 s reference clip."""
    import speak
    if not (os.path.exists(os.path.join(speak.TUNED, "model.safetensors")) or os.path.exists(speak.REF_WAV)):
        return None
    voice = speak.Voice()
    print(f"Speaking finished sentences in your {'fine-tuned' if voice.tuned else 'cloned'} voice (V turns it off).",
          flush=True)
    return voice


class Transcript:
    """Turns finished sentences into text (beam search, then the corrector picks and fixes the best guess)
    and, with a voice profile, says them out loud in your voice."""

    def __init__(self, reader, fix=None, voice=None):
        self.reader, self.fix, self.voice, self.lines = reader, fix, voice, []
        self.speaking = voice is not None

    def add(self, enc):
        self.emit(self.decode(enc))

    def decode(self, enc):
        """A sentence's final text from its encoder output: beam search, then the corrector. None if nothing read."""
        guesses = self.reader.guesses(enc) if enc is not None else []
        if not guesses:
            return None
        text = guesses[0].capitalize()
        if self.fix:
            ctc = self.reader.collapse(self.reader.ctc(enc))
            try:  # the CTC reading is an independent second opinion (no language model): 15.3% -> 14.0% WER
                text = self.fix([g for g in dict.fromkeys(guesses[:8] + [ctc]) if g]) or text
            except Exception as e:
                print(f"\r\033[K  (corrector failed: {e})", flush=True)
        return text

    def emit(self, text):
        """Show, speak and copy a finished sentence."""
        if not text:
            return
        self.lines.append(text)
        print(f"\r\033[K> {text}", flush=True)
        if self.speaking:
            self.voice.say(text)
        if sys.platform == "darwin":  # whole transcript, ready to paste
            subprocess.run(["pbcopy"], input=" ".join(self.lines).encode())


def shrink(frame):
    h, w = frame.shape[:2]
    return frame if w <= WIDTH else cv2.resize(frame, (WIDTH, round(h * WIDTH / w)), interpolation=cv2.INTER_AREA)


def run_file(path, reader, transcript):
    cap = cv2.VideoCapture(path)
    fps = cap.get(cv2.CAP_PROP_FPS) or FPS
    frames = []
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        frames.append(cv2.cvtColor(shrink(frame), cv2.COLOR_BGR2RGB))
    frames = [frames[i] for i in np.arange(0, len(frames), fps / FPS).astype(int)]  # resample to 25 fps
    stream = Stream(reader)
    for i, frame in enumerate(frames, 1):
        stream.push(frame, reader.landmarks(frame))
        if i % STEP == 0 or i == len(frames):
            sentence = stream.step(flush=i == len(frames))
            if sentence:
                transcript.add(reader.encode(*sentence[:2]))
    if not transcript.lines:
        print("Nothing read. Is a face clearly visible?")


def put_lines(img, lines, y, color, scale=0.8):
    for line in lines:
        cv2.putText(img, line, (20, y), cv2.FONT_HERSHEY_SIMPLEX, scale, (0, 0, 0), 5)  # outline for contrast
        cv2.putText(img, line, (20, y), cv2.FONT_HERSHEY_SIMPLEX, scale, color, 2)
        y += int(38 * scale)


def run_webcam(reader, transcript):
    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        sys.exit("Cannot open the webcam. Allow camera access for your terminal app in "
                 "System Settings > Privacy & Security > Camera, then retry.")
    stream, sentences = Stream(reader), queue.Queue()
    listening, flush, quit_ = True, threading.Event(), threading.Event()

    def close(a, b):  # same sentence span, give or take a few frames of spike jitter
        return abs(a[0] - b[0]) <= 3 and abs(a[1] - b[1]) <= 3

    def live():  # reads the lips every STEP frames; encodes sentences (drafts while the pause is being confirmed)
        seen, spec = 0, None  # spec: (key, encoder output) of the sentence being decoded ahead of time
        while True:
            if flush.is_set():  # checked before quit_, so quitting still finishes the last sentence
                flush.clear()
                sentence = stream.step(flush=True)
            elif quit_.is_set():
                return
            elif stream.pushed - seen >= STEP:
                seen = stream.pushed
                sentence = stream.step()
            else:
                time.sleep(0.02)
                continue
            if sentence:
                frames, lms, key = sentence
                if spec and close(spec[0], key):  # decoded ahead already: just confirm it
                    sentences.put(("commit", *spec))
                else:
                    sentences.put(("final", key, reader.encode(frames, lms)))
                spec = None
            elif stream.draft and not (spec and close(spec[0], stream.draft[2])):
                frames, lms, key = stream.draft
                spec = (key, reader.encode(frames, lms))
                sentences.put(("draft", *spec))
            if stream.partial:
                print(f"\r\033[K… {stream.partial}", end="", flush=True)

    def finish():  # beam search + corrector, so the live text keeps updating meanwhile
        ready = {}  # draft key -> text decoded ahead of time
        while True:
            kind, key, enc = sentences.get()
            try:
                if kind == "draft":
                    if sentences.empty():  # skip drafts that newer reads have already overtaken
                        ready = {key: transcript.decode(enc)}
                else:
                    transcript.emit(ready.pop(key) if kind == "commit" and key in ready else transcript.decode(enc))
                    ready = {}
            except Exception as e:  # keep going after a bad sentence
                print(f"\r\033[K(could not read that sentence: {e})", flush=True)
            finally:
                sentences.task_done()

    reader_thread = threading.Thread(target=live, daemon=True)
    reader_thread.start()
    threading.Thread(target=finish, daemon=True).start()
    print("Listening. Mouth sentences and pause between them. SPACE pauses/resumes, Q quits.", flush=True)
    next_t, cam_times = time.monotonic(), []
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        now = time.monotonic()
        cam_times = cam_times[-29:] + [now]
        if listening and now >= next_t:
            rgb = cv2.cvtColor(shrink(frame), cv2.COLOR_BGR2RGB)
            lm = reader.landmarks(rgb)
            # Resample onto a 25 fps grid: drops extra frames from fast cameras, repeats frames from slow ones.
            while now >= next_t:
                stream.push(rgb, lm)
                next_t += 1 / FPS

        view = cv2.flip(frame, 1)
        status = "LISTENING" if listening else "PAUSED"
        if transcript.voice:
            status += "  VOICE ON (V)" if transcript.speaking else "  VOICE OFF (V)"
        if sentences.unfinished_tasks:
            status += "  (finishing...)"
        put_lines(view, [status], 40, (0, 200, 0) if listening else (0, 165, 255), 0.9)
        cam_fps = (len(cam_times) - 1) / max(cam_times[-1] - cam_times[0], 1e-6)
        if len(cam_times) == 30 and cam_fps < 22:
            # Measured: a 15 fps camera raises errors from 15% to 21-23%. Macs drop to 15 fps in dim light.
            put_lines(view, [f"Camera at {cam_fps:.0f} fps: add light for better accuracy"], 80, (0, 165, 255), 0.7)
        h = view.shape[0]
        put_lines(view, textwrap.wrap(" ".join(transcript.lines[-2:]), 48)[-3:], h - 150, (255, 255, 255))
        put_lines(view, textwrap.wrap(stream.partial, 48)[-2:], h - 50, (0, 255, 255))
        cv2.imshow("lipread", view)

        key = cv2.waitKey(1) & 0xFF
        if key in (ord("q"), 27):
            break
        if key == ord("v") and transcript.voice:
            transcript.speaking = not transcript.speaking
            if not transcript.speaking:
                transcript.voice.stop()
        if key == ord(" "):
            listening = not listening
            if not listening:
                flush.set()  # pausing ends the current sentence
            next_t = time.monotonic()
    flush.set()  # finish whatever was being said
    quit_.set()
    reader_thread.join()
    sentences.join()
    print()
    cap.release()
    cv2.destroyAllWindows()


def lines(path):
    return [s for s in open(path).read().splitlines() if s.strip()]


def norm(text):
    """Text the way the model writes it: lowercase words and apostrophes (spell numbers out)."""
    return " ".join(re.sub(r"[^a-z' ]", " ", text.lower().replace("’", "'")).split())


def load_calib(path=CALIB):
    """Recorded mouth-crop clips, their sentences, and word cut points (None = words not timed yet)."""
    if not os.path.exists(path):
        return [], [], []
    z = np.load(path)
    clips = np.split(z["frames"], np.cumsum(z["lengths"])[:-1])
    if "ncuts" not in z:
        return clips, [str(t) for t in z["texts"]], [None] * len(clips)
    cuts = np.split(z["cuts"], np.cumsum(z["ncuts"])[:-1])
    return clips, [str(t) for t in z["texts"]], [c if len(c) else None for c in cuts]


def save_calib(clips, texts, cuts, path=CALIB):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp.npz"  # write then rename, so a crash mid-save can't corrupt earlier recordings
    np.savez_compressed(tmp, frames=np.concatenate(clips), lengths=[len(c) for c in clips], texts=texts,
                        ref=" ".join(texts), ncuts=[0 if c is None else len(c) for c in cuts],
                        cuts=np.concatenate([np.zeros(0, int)] + [c for c in cuts if c is not None]).astype(int))
    os.replace(tmp, path)


def time_words(reader, clips, texts, cuts, path=CALIB):
    """Find when each word is mouthed (forced alignment with the current model) and trim the still lips around
    each sentence. --calibrate (finetune_mlx.py) can use the word times to cut sentences into shorter clips."""
    todo = [k for k, c in enumerate(cuts) if c is None]
    if not todo:
        return
    print(f"Timing the words in {len(todo)} clips...", flush=True)
    reader.load_model()
    for k in todo:
        c = reader.align(reader.encode_crops(clips[k]), texts[k])
        if c is not None:
            # keep 0.6 s around the words: a 0.25 s margin cost ~1 point of accuracy in tests
            a, b = max(0, c[0] - 15), min(len(clips[k]), c[-1] + 15)
            clips[k], cuts[k] = clips[k][a:b], c - a
    save_calib(clips, texts, cuts, path)
    print(f"Timed {sum(c is not None for c in cuts)} of {len(cuts)} clips.", flush=True)


class Microphone:
    """Records the mic in the background, noting when each block arrived so audio lines up with video frames."""
    RATE = 24000

    def __init__(self):
        import sounddevice
        self.blocks = []
        self.stream = sounddevice.InputStream(samplerate=self.RATE, channels=1, dtype="float32",
                                              callback=lambda d, n, t, s: self.blocks.append((time.monotonic(), d[:, 0].copy())))
        self.stream.start()

    def stop(self):
        """All audio, and the time.monotonic() of its first sample."""
        self.stream.stop()
        self.stream.close()
        audio = np.concatenate([b for _, b in self.blocks])
        return audio, self.blocks[-1][0] - len(audio) / self.RATE


def hear_words(audio, t0):
    """Words in a recording with their start/end times (time.monotonic()), by Whisper running locally on MLX."""
    import mlx_whisper
    from scipy.signal import resample_poly
    result = mlx_whisper.transcribe(resample_poly(audio, 2, 3).astype(np.float32), path_or_hf_repo=WHISPER,
                                    word_timestamps=True, language="en")
    return [(w["word"].strip(), t0 + w["start"], t0 + w["end"]) for s in result["segments"] for w in s.get("words", [])]


NUMBERS = ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen "
           "seventeen eighteen nineteen").split()


def spoken(word):
    """Whisper writes 'nine' as '9': compare words the way they were said."""
    w = norm(word)
    return NUMBERS[int(word.strip(".,!?"))] if not w and word.strip(".,!?").isdigit() and int(word.strip(".,!?")) < 20 else w


def time_words_from_audio(clips, texts, cuts, new, spans, heard):
    """Word times for sentences read aloud: line up Whisper's words with each sentence and map them to frames.
    Whisper's times are approximate (~0.3 s early in tests), well inside the 0.6 s margin kept around words.
    Returns the heard words that match the text, as (word, start, end), and each timed clip's speech span."""
    said, speech = [], {}
    for k, (a, b) in zip(new, spans):
        words = texts[k].split()
        mine = [(spoken(w), s, e, w) for w, s, e in heard if a - 0.5 <= (s + e) / 2 <= b + 0.5 and spoken(w)]
        start, end = np.full(len(words), np.nan), np.full(len(words), np.nan)
        m = difflib.SequenceMatcher(None, words, [x[0] for x in mine], autojunk=False)
        for blk in m.get_matching_blocks():
            for j in range(blk.size):
                start[blk.a + j], end[blk.a + j] = mine[blk.b + j][1:3]
                said.append((mine[blk.b + j][3], *mine[blk.b + j][1:3]))
        known = ~np.isnan(start)
        if known.sum() < 0.7 * len(words) or not (known[0] and known[-1]):
            continue  # misread or mumbled: leave it to lip-based timing
        idx = np.arange(len(words))
        start, end = np.interp(idx, idx[known], start[known]), np.interp(idx, idx[known], end[known])
        speech[k] = (start[0], end[-1])
        frame = lambda t: int(round((t - a) * FPS))
        c = [frame(start[0])] + [frame((end[i] + start[i + 1]) / 2) for i in range(len(words) - 1)] + [frame(end[-1])]
        c = np.maximum.accumulate(np.clip(c, 0, len(clips[k])))
        lo, hi = max(0, c[0] - 15), min(len(clips[k]), c[-1] + 15)  # same 0.6 s margin as time_words
        clips[k], cuts[k] = clips[k][lo:hi], c - lo
    return said, speech


def write_wav(path, audio):
    import wave
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(Microphone.RATE)
        f.writeframes((np.clip(audio, -1, 1) * 32767).astype(np.int16).tobytes())


def save_voice_clips(audio, t0, speech, sentences):
    """Every sentence read aloud, as its own clip + exact text (profile/voice/), for fine-tuning the voice."""
    os.makedirs(VOICE_CLIPS, exist_ok=True)
    index = os.path.join(VOICE_CLIPS, "clips.jsonl")
    n = sum(1 for _ in open(index)) if os.path.exists(index) else 0
    with open(index, "a") as f:
        for k, (start, end) in sorted(speech.items()):
            a, b = int((start - 0.2 - t0) * Microphone.RATE), int((end + 0.3 - t0) * Microphone.RATE)
            name = f"{n:03d}.wav"
            write_wav(os.path.join(VOICE_CLIPS, name), audio[max(0, a):b])
            f.write(json.dumps({"audio": name, "text": sentences[k]}) + "\n")
            n += 1


def save_voice(audio, t0, heard):
    """Keep ~10-15 s of continuous reading as the voice to clone (profile/voice.wav + voice.txt).
    `heard` must only hold words matched to the text: Whisper invents words in silences."""
    best = None
    for i in range(len(heard)):
        for j in range(i + 1, len(heard)):
            span = heard[j][2] - heard[i][1]
            if span > 15:
                break
            gaps = max(heard[n + 1][1] - heard[n][2] for n in range(i, j))
            if span >= 10 and gaps < 1.0 and (best is None or j - i > best[1] - best[0]):
                best = (i, j)  # most words in the window: fewest pauses
    if best is None:
        print("Could not find 10 s of continuous speech for the voice profile; run --onboard again.", flush=True)
        return
    i, j = best
    a, b = (int((t - t0) * Microphone.RATE) for t in (heard[i][1] - 0.15, heard[j][2] + 0.25))
    os.makedirs(VOICE, exist_ok=True)
    write_wav(os.path.join(VOICE, "voice.wav"), audio[max(0, a):b])
    open(os.path.join(VOICE, "voice.txt"), "w").write(" ".join(w for w, _, _ in heard[i:j + 1]) + "\n")
    print(f"Saved your voice profile ({heard[j][2] - heard[i][1]:.0f} s) to {VOICE}/.", flush=True)


def run_record(sentences, path=CALIB, aloud=False):
    """Teleprompter: mouth each sentence silently, then SPACE for the next one (R redo, Q save and stop).
    Keeps only mouth crops. Running it again resumes where you stopped. Returns True once all are recorded.
    aloud=True: say each sentence out loud instead; the mic is recorded to time every word exactly and to
    keep a short voice profile. The rest of the audio is discarded."""
    reader = LipReader(load_model=False)
    clips, texts, cuts = load_calib(path)
    todo = [s for s in sentences if norm(s) not in set(texts)]
    cap = cv2.VideoCapture(0) if todo else None  # nothing left to record: just time any untimed clips
    if cap and not cap.isOpened():
        sys.exit("Cannot open the webcam. Allow camera access for your terminal app in "
                 "System Settings > Privacy & Security > Camera, then retry.")
    mic = Microphone() if aloud and todo else None
    how = "say it out loud" if aloud else "mouth it silently"
    seg, next_t, i, new, spans, raw = [], time.monotonic(), 0, [], [], {}
    while i < len(todo):
        ok, frame = cap.read()
        if not ok:
            break
        now = time.monotonic()
        if now >= next_t:
            rgb = cv2.cvtColor(shrink(frame), cv2.COLOR_BGR2RGB)
            lm = reader.landmarks(rgb)
            while now >= next_t:
                seg.append((rgb, lm, next_t))
                next_t += 1 / FPS
            seg = seg[-15 * FPS:]  # a sentence never takes this long; caps RAM if you step away
        view = cv2.flip(frame, 1)
        done = len(sentences) - len(todo) + i
        put_lines(view, [f"{done}/{len(sentences)}   {how}, then SPACE.   R redo   Q save and stop"],
                  30, (255, 255, 255), 0.55)
        put_lines(view, textwrap.wrap(todo[i], 36), 75, (0, 255, 255), 0.95)  # near the top, close to the camera
        cv2.imshow("lipread", view)
        key = cv2.waitKey(1) & 0xFF
        if key in (ord("q"), 27):
            break
        if key == ord("r"):
            seg = []
        if key == ord(" "):
            frames, lms, ts = zip(*seg) if seg else ((), (), ())
            seg = []
            if len(frames) < FPS or all(lm is None for lm in lms):
                print(f"Too short ({len(frames) / FPS:.1f}s) or no face found; do that one again.", flush=True)
                continue
            clips.append(reader.crop(np.stack(frames), list(lms)))
            texts.append(norm(todo[i]))
            cuts.append(None)
            new.append(len(clips) - 1)
            raw[len(clips) - 1] = todo[i]
            spans.append((ts[0], ts[-1] + 1 / FPS))
            i += 1
            if len(new) % 10 == 0:
                save_calib(clips, texts, cuts, path)
    if cap:
        cap.release()
        cv2.destroyAllWindows()
    if mic:
        audio, t0 = mic.stop()
        if new:
            print("Timing your words from the audio...", flush=True)
            said, speech = time_words_from_audio(clips, texts, cuts, new, spans, hear_words(audio, t0))
            save_voice(audio, t0, said)
            save_voice_clips(audio, t0, speech, raw)
        del audio  # only the sentences themselves are kept, as voice training clips
    if clips:
        save_calib(clips, texts, cuts, path)
        print(f"{len(new)} new clips, {len(clips)} in total in {path}.", flush=True)
        time_words(reader, clips, texts, cuts, path)  # lip-based timing for anything the audio didn't cover
    return i == len(todo)


def run_onboard():
    """Day one, ~4 minutes: read the paragraph aloud (voice profile + exactly timed lip clips),
    then mouth the same sentences silently (how you'll actually use lipread)."""
    sentences = lines(ONBOARD)
    print("Part 1 of 2: read each sentence OUT LOUD in your normal voice, then press SPACE.", flush=True)
    if not run_record(sentences, ONBOARD_ALOUD, aloud=True):
        return print("Stopped. Run --onboard again to continue.")
    print("Part 2 of 2: now mouth the same sentences SILENTLY, then press SPACE.", flush=True)
    if run_record(sentences, ONBOARD_SILENT):
        print("Onboarding done.", flush=True)


def run_calibrate():
    """Train your profile on Modal (train_profile.py) from every recording except the rare-word test set;
    the trained weights come back for local MLX inference. ~2 min of GPU time for the day-one recordings."""
    import glob
    clips, texts, cuts = [], [], []
    for path in sorted(glob.glob(os.path.join(HERE, "calib", "*.npz"))):
        if path != EVAL and not path.endswith(".tmp.npz"):
            for got, part in zip((clips, texts, cuts), load_calib(path)):
                got.extend(part)
    if len(clips) < 20:
        sys.exit("Record yourself first: lipread.py --onboard")
    print(f"Uploading {len(clips)} clips ({sum(map(len, clips)) / FPS / 60:.1f} min of mouth crops) to Modal...", flush=True)
    os.makedirs(os.path.dirname(PERSONAL), exist_ok=True)
    upload = os.path.join(os.path.dirname(PERSONAL), "upload.tmp.npz")
    save_calib(clips, texts, cuts, upload)
    try:
        subprocess.run(["modal", "run", os.path.join(HERE, "train_profile.py"), "--data", upload, "--profile", VOICE],
                       check=True, cwd=HERE)
    finally:
        os.remove(upload)


def main():
    mx.set_cache_limit(1 << 30)  # MLX's buffer cache otherwise grows with every new clip length
    if sys.argv[1:] == ["--calibrate"]:
        run_calibrate()
        return
    if sys.argv[1:] in (["--record"], ["--read"]):
        run_record(PROMPTS if sys.argv[1] == "--record" else lines(PASSAGE))
        return
    if sys.argv[1:] == ["--onboard"]:
        run_onboard()
        return
    if sys.argv[1:] == ["--record-eval"]:  # test sentences with rare words; never used for training
        run_record(lines(RARE), EVAL)
        return
    print("Loading models...", flush=True)
    reader = LipReader()
    transcript = Transcript(reader, load_corrector(), load_voice() if len(sys.argv) == 1 else None)
    if len(sys.argv) > 1:
        run_file(sys.argv[1], reader, transcript)
    else:
        run_webcam(reader, transcript)


if __name__ == "__main__":
    main()

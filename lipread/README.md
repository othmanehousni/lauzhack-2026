# lipread

Silent lip reading from your webcam, spoken in your own voice. Mouth sentences: they appear as text while you go, and each finished sentence is said out loud in your voice, for places where you can't speak (a meeting, a library, after surgery). Reading, correcting and speaking run locally on Apple Silicon with MLX. Training runs on Modal GPUs: your profile once on day one, and the shared corrector once.

## How it works

1. **Live text.** Every 0.4 s the lip-reading model ([Auto-AVSR](https://github.com/mpc001/Visual_Speech_Recognition_for_Multiple_Languages), ported to MLX) re-reads the current sentence, and the words appear in yellow. Its fast decoding head stays silent when your lips don't move, so a 0.8 s pause ends a sentence.
2. **Final text.** Each finished sentence gets a full beam search with a language model (about 0.3 s). Then the corrector rewrites it: a Qwen3-4B fine-tuned on lip-reading confusions (`corrector.py`, about 1 s). It sees the top guesses, their lip shapes (p/b/m look alike, and so on) and your `dictionary.txt`. That lets it produce words the lip model never learned, such as names, brands and jargon. The result appears in white, and the whole transcript is copied to the clipboard.
3. **Voice.** The sentence is spoken in your voice by Qwen3-TTS (mlx-audio), streamed so playback starts before the whole sentence is generated. **V** toggles it on and off. The voice comes from the paragraph you read aloud on day one: Qwen3-TTS fine-tuned on those sentences, or, if fine-tuning didn't sound closer to you, cloned from the clearest 15 s of them.

## Setup

Needs [uv](https://github.com/astral-sh/uv).

```sh
./setup.sh
```

- Installs the environment.
- Converts the lip model to MLX once (it downloads ~1.2 GB, checks the SHA-256 hashes, and removes the PyTorch files afterwards).
- Downloads the CMU pronunciation dictionary.
- On first use, the voice model (~2.5 GB) and Whisper (~1.6 GB) download from Hugging Face.
- The corrector is trained once with `modal run train_corrector.py` (about 2 h on an H100, about $8); it writes `models/corrector`. Without it, lipread runs on the lip model alone.

## Day one: about 4 minutes of reading, then training on Modal

```sh
.venv/bin/python lipread.py --onboard      # read the paragraph aloud, then mouth it silently
.venv/bin/python lipread.py --calibrate    # train your lip and voice profiles on Modal
```

**`--onboard`**: one paragraph builds both profiles.
- Shows `onboarding.txt` (19 sentences, 237 words, every English sound and lip shape) on a teleprompter. Press **Space** after each sentence, **R** to redo, **Q** to stop. Running it again resumes.
- **Pass 1, aloud:**
  - The microphone is recorded. Whisper (local) finds when each word was said, which labels the video, accurate to about 0.3 s.
  - Each sentence is kept as its own clip with its text (`profile/voice/`): the voice training data.
  - The clearest 10–15 s also becomes the clone reference (`profile/voice.wav` + `voice.txt`).
- **Pass 2, silent:** the same sentences, mouthed the way you'll really use lipread. Mouthing silently moves the lips differently from speaking, and silent recordings are what made the lip model accurate.

**`--calibrate`** needs the [Modal CLI](https://modal.com/docs/guide) logged in (`modal setup`). Lips and voice train at the same time on two GPUs; the results come back to `profile/`.
- **Lips:** `finetune_mlx.py` itself, run on the GPU through MLX's CUDA backend, so the weights come back ready for MLX.
  - Fine-tunes the lip model's encoder on everything you've recorded (except the rare-word test set), with augmentation: random speed, lighting, blanked-out stretches, crops and flips.
  - Picks the best epoch on 15% held-out clips, and saves `profile/lips.safetensors` only if it beats the stock model.
- **Voice:** fine-tunes Qwen3-TTS-0.6B on your sentences read aloud (`train_profile.py`).
  - The last 3 sentences are held out. Every 4 epochs, the model says them, and is scored on how close it sounds to you (speaker-embedding similarity) and how clear it is (Whisper word error).
  - It keeps the best checkpoint (`profile/voice_model/`) only if it sounds closer to you than cloning from the 15 s clip, without losing clarity. Otherwise the clone stays.
  - Listen to the comparison in `profile/voice_samples/` (`real`, `zero_shot`, `fine_tuned`).

## Use

```sh
.venv/bin/python lipread.py
```

- Mouth a sentence, then pause.
- **Space** pauses or resumes, **V** turns the voice on or off, **Q** quits after the current sentence.
- The window warns you when the camera drops below 22 fps. Add light: at 15 fps, errors rose from 15% to 21–23% in tests.
- To read a video file instead: `.venv/bin/python lipread.py clip.mp4`
- Add names and terms you use to `dictionary.txt`, one per line.

More recordings make the profile better (`--record`: 120 short prompts; `--read`: a ~40 min passage covering 99% of common English words). Run `--calibrate` again after recording. `--record-eval` records the rare-word test set (`rare_words.txt`), which is never trained on.

## Measured (word error rate on held-out sentences)

| | Error |
|---|---|
| Stock lip model, your silent mouthing | 69% |
| Profile from your 4.8 min of recordings (120 prompts) | 15% |
| Profile from 30 min of recordings | 19.8% (a harder held-out set) |
| + Wispr-style corrector (that same 30-min profile) | **16.0%** (the old Ollama prompt: 18.6%) |
| Day one: 2.5 min of mouthing + `--calibrate` (simulated from your clips) | 38.1%, **34.5%** with corrector |
| Rare-word sentences (`rare_words.txt`: names, brands, jargon; 30-min profile) | 68.8% → 61.1% corrector → 59.5% + dictionary |

**Speed** (M1 Pro). Text is final a median 2.1 s after your last mouthed word. 0.8 s of that is the pause that ends the sentence, and the sentence is already decoded during the pause. The voice starts 0.2–0.5 s later (0.7 s when cloning) and is generated 1.3–1.5× faster than it plays. `--calibrate` takes about 5 minutes: lips and voice train in parallel on two L40S GPUs (lips about 4× faster than on the Mac).

**Voice** (closeness to the speaker: speaker-embedding cosine against their training sentences; tested with a stand-in speaker reading `onboarding.txt` aloud):

| | Closeness | Whisper word error |
|---|---|---|
| The speaker's own held-out recordings | 0.917 | |
| Cloned from 15 s (zero-shot) | 0.817 | 2.9% |
| Fine-tuned on the 16 other onboarding sentences (best checkpoint, epoch 8) | **0.891** | 2.9% |
| The same, run locally with MLX (clone in MLX: 0.800) | 0.839 | 0% |

Rare words remain the weak spot. On sentences full of unfamiliar words the lip model loses the whole sentence, so even the corrector with a dictionary gets only 10 of 52 rare terms right, up from 4 for the lip model alone. Lowering or removing the lip model's own language model didn't change that.

On a clean public-domain video the lip model alone gets 15.3% and the whole pipeline 12.6%; silent webcam mouthing is harder. In those tests, 3.4 min of one speaker cut errors on a separate recording from 19.1% to 16.4%.

## Files

| File | What |
|---|---|
| `lipread.py` | the app: webcam, live text, onboarding, calibration, recording |
| `vsr_mlx.py` | the lip model and beam search in MLX (ported from PyTorch; parity in `convert_weights.py --check`) |
| `finetune_mlx.py` | lip-profile training (MLX; runs on Modal's GPUs for `--calibrate`) |
| `train_profile.py` | the Modal app `--calibrate` runs: lip and voice training |
| `corrector.py` / `train_corrector.py` | the corrector at runtime / its training on Modal (simulated lip-reading errors + your real ones, text only) |
| `speak.py` | your voice: the fine-tuned model or the clone, streamed playback |
| `profile/` | your lip profile, voice model and voice clips; `calib/` your recordings (mouth crops only) |

## Privacy

- **Live reading, the corrector and the voice** run locally and make no internet calls.
- **`--calibrate`** sends Modal your 96×96 mouth crops, the sentences you read aloud (audio + text) and the 15 s reference clip, as inputs of a single run. Nothing is stored there except the fine-tuned voice, which is downloaded and then deleted from the Modal volume.
- **`train_corrector.py`** sends Modal only text: simulated errors plus the lip model's guesses on your training clips.
- **Clipboard:** with Universal Clipboard on, it syncs to your other Apple devices.
- `profile/` and `calib/` are personal data; the repository's `.gitignore` keeps them out of git.

## Credits

- **Lip model:** the model and the code in `vsr/` come from [Visual Speech Recognition for Multiple Languages / Auto-AVSR](https://github.com/mpc001/Visual_Speech_Recognition_for_Multiple_Languages) (Imperial College London). Its license allows individual research and private study; see `vsr/LICENSE`. That code is trimmed to the LRS3 video model, with these changes:
  - unused imports removed;
  - the face detector picks the largest face;
  - the `torchvision` import is lazy;
  - `unigram5000.model` was added from [auto_avsr](https://github.com/mpc001/auto_avsr).
- **Other models and data:**
  - Corrector base: Qwen3-4B-Instruct (Apache-2.0).
  - Voice: Qwen3-TTS (Apache-2.0).
  - Onboarding word timing: Whisper (MIT).
  - Pronunciations: CMUdict (BSD).

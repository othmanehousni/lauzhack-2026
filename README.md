# LauzHack 2026

A minimal Kotlin Multiplatform app for Android and desktop. Android opens a blank white screen. Desktop opens the Molière floater, based on `mole/index.html`.

## Setup

With [mise](https://mise.jdx.dev/) installed, run from the repository root:

```sh
mise trust
mise install
```

All tool requirements and SDK setup commands are in `mise.toml`:

- Temurin JDK 21.
- Android SDK command-line tools 23.0, through mise's [Android SDK backend](https://github.com/jdx/vfox-android-sdk).
- Android SDK Platform 36, Build Tools 36.0.0, and platform tools, installed automatically by the `postinstall` hook. Accept the SDK licenses when prompted.
- Optional emulator and an API 36 Google APIs system image, installed by `android:emulator:setup`.

Mise sets `JAVA_HOME`, `ANDROID_HOME`, and tool paths for the tasks below. The Gradle wrapper downloads Gradle, Kotlin, Compose, and project dependencies on first use. Android Studio is optional.

The `postinstall` hook runs `android:setup` after `mise install`, including when Java and the command-line tools are already installed. Run `mise install` again to install any missing SDK components.

## Desktop

From the repository root:

```sh
mise run desktop
```

This opens a borderless, always-on-top floater near the bottom center of the screen. Drag the bar to move it. Right-click it, or use its system tray/menu bar icon, to reset its position or quit.

The floater displays the reference's idle state only. It has no microphone, recording, or copilot behavior.

## Android

Connect an Android phone with USB debugging enabled and accept its authorization prompt, or start an emulator. Then run:

```sh
mise run android
```

This builds, installs, and opens the Android app. Keep only one device or emulator connected when using this task.

For a local emulator on macOS or Linux, install it once:

```sh
mise run android:emulator:setup
```

Start it in a separate terminal and wait for Android to finish booting before running `mise run android`:

```sh
mise run android:emulator
```

The emulator image matches the host CPU. Virtual device files are stored in the ignored `.android/avd/` directory inside this repository. SDK packages remain in mise's Android SDK install directory, which you can inspect with `mise where android-sdk`.

## Build

After SDK setup:

```sh
mise run build
```

Outputs:

- Android APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
- Desktop JAR: `composeApp/build/libs/composeApp-desktop.jar`.

## Code

- `composeApp/src/commonMain/kotlin/ch/lauzhack/app/App.kt` contains the blank screen used by Android.
- `composeApp/src/desktopMain` contains the desktop entry point, floater, and vector artwork.
- `androidApp` contains the Android launcher and application module.
- `gradle/libs.versions.toml` pins dependencies and Android build versions.
- `mise.toml` declares tools, SDK packages, and setup, run, and build tasks.

---

# Meeting copilot

The copilot follows a presenter in a video call and changes the shared screen for them. It turns speech, silent lip reading or typed lines into sentences. While each sentence is still being spoken, a local intent classifier works out whether to keep the screen, show a slide from your decks, open a website or an app, act on the page on screen, or ask a vision model for something else. What the audience sees is a stage: Chrome in kiosk mode, driven over its DevTools protocol (`stage.py`). Slides are rendered to images once and swap in about 10 to 50 ms. Websites and files open in tabs of their own in about 0.2 s. Scrolling, zooming, play, pause, full screen and clicking a link run as JavaScript in the tab on screen. Anything harder goes to a screen agent. The shared screen is a macOS sandbox VM shown in a host window titled "Copilot screen". Nothing acts on your own desktop, and every model runs locally.

## Setup

1. Install the Python environment: `uv sync`.
2. Pull the Ollama models: `ollama pull embeddinggemma-2:270m` and `ollama pull qwen3.5:4b-q4_K_M`.
3. Install llama.cpp for the d1 decision model: `brew install llama.cpp`. Download the model once with `llama-server -hf LiquidAI/d1-2B-GGUF:Q8_0`, then stop it with Ctrl-C. The copilot starts its own server offline from that cache.
4. Put your PDF decks in `library/`. For sample decks, run `uv run --group dev fixtures/make_decks.py library`.
5. For lip reading, convert the weights once: `uv run --with torch lipread/convert.py`. The checkpoints are for non-commercial research use only.
6. Allow your terminal to use the microphone and the camera in System Settings > Privacy & Security.

## One-time VM setup

1. Run `agent-desktop sandbox install macos`.
2. Get a macOS 26.x image. Apple's catalog now offers only macOS 27.x, which a macOS 26.4 host cannot run. The 26.4 image is at `https://updates.cdn-apple.com/2026WinterFCS/fullrestores/122-00766/062A6121-2ABE-45D7-BCB1-72B666B6D2C2/UniversalMac_26.4_25E246_Restore.ipsw` (19.7 GB, SHA-1 `177baf85518c6e9cebf83990e11e59259b5c97dd`).
3. Run `agent-desktop sandbox create base --provider macos --set ipsw=<path to the .ipsw>`. Repeat `agent-desktop sandbox status base` until it stops answering `working`. The install writes about 21 GB. Delete the image afterwards.
4. Optional, to save RAM: in `~/.agent-desktop/sandboxes/base/vm/config.json`, lower `memorySize` from 8 GB (8589934592) to 4 GB (4294967296).
5. Run `agent-desktop sandbox start base --window --set title="Copilot screen"`.
6. In the guest, finish Setup Assistant. Name the account with the same short name as your Mac account (`cat ~/.agent-desktop/sandboxes/base/guest_user`). Turn on automatic login in System Settings > Users & Groups.
7. In the guest, open Terminal and run `sh "/Volumes/My Shared Files/guest-setup.sh"`.
8. In the guest, turn on Remote Login in System Settings > General > Sharing.
9. In the guest, allow Accessibility for `sshd-keygen-wrapper` when macOS asks, or switch it on in System Settings > Privacy & Security > Accessibility. Do the same under Screen & System Audio Recording, which the screen agent needs for screenshots.
10. On the host, `agent-desktop sandbox status base` answers `running` when setup is complete.
11. On the host, run `uv run vm.py`. It mounts your Desktop, Documents and Downloads read-only in the VM at the same paths, gives the VM your screen's size and density, copies your wallpaper, dark mode and Dock settings, installs Google Chrome for the stage, and restarts the VM. It patches agent-desktop's VM host to share those folders, so rerun it after updating agent-desktop. If you rerun step 7 later, the script is at `/Volumes/My Shared Files/agent-desktop/guest-setup.sh`.

After this, `copilot.py live` starts the VM by itself when it is stopped.

## Run

- `uv run copilot.py live` listens to the microphone through Apple's on-device speech recognizer (macOS 26). The first run builds `hear.swift` and may download Apple's English model.
- `uv run copilot.py live --input say --script eval/tour.jsonl` speaks a script with `say` and feeds it through the same recognizer, at the pace it would be spoken. A script is one sentence per line, or an eval `.jsonl`.
- `uv run copilot.py live --input keys` takes typed lines.
- `uv run copilot.py live --input lips` reads your lips from the webcam, without sound. Add `--camera N` to pick another webcam and `--preview` to see the mouth crop and the lip-motion bar. If the gate opens while your lips are still, or stays shut while you mouth words, tune it alone with `uv run lipread/lipread.py live --preview --sensitivity 4`, then pass the same `--sensitivity` to `copilot.py live`.
- `uv run copilot.py live --input parakeet` uses the Parakeet model instead of Apple's recognizer.

Add `--router none` to run without the d1 decision model and llama-server. Add `--dry` to print the VM commands instead of running them. A dry run needs no VM.

Before the VM is set up, add `--host` to drive this Mac directly, for example `uv run copilot.py --host live --input keys`. The stage is then a separate Chrome window with its own profile (`~/.copilot-chrome`), so share that window. The screen agent clicks and types on your desktop in this mode.

## Try every feature

`eval/tour.jsonl` walks through every feature on the sample decks in 26 sentences. Each line has what the screen should show after it (`want`). Read the lines aloud in order with `uv run copilot.py --router none live`, or let `say` speak them with `--input say --script eval/tour.jsonl`. `uv run copilot.py eval eval/tour.jsonl` scores them as typed text.

The log prints one line per transcript. `~` marks a live transcript and `>` the end of an utterance. Then come how long the words had been unchanged (`text`), how long the brain took (`decision`), the three likeliest readings, and what the brain made of the sentence. `->` is an action, timed from your last voiced sound. `?` is an offer or a suggestion that waits for you.

## How a sentence reaches the screen

1. **Hearing.** `hear.swift` runs Apple's SpeechTranscriber and expects the names of your apps, websites, decks and slide titles. It sends the words so far as you speak, about a second behind the audio. An utterance ends when the microphone has been quiet for 1.2 s and no new word came for 0.6 s. The recognizer times every word, so a word that arrives late still joins the sentence it was said in.
2. **Reading.** Every new transcript goes to the brain (`brain.py`). EmbeddingGemma 2, served by Ollama, embeds the sentence twice in one call: once to classify it and once as a search query. A softmax-regression classifier reads the classification vector together with the sentence's words and word pairs. It is trained at start-up, in under a second, on the 1200 example sentences in `intents.py`. It reads the whole sentence and each of its clauses, and acts on the last clause that is surely a request, unless a later clause puts it off ("I wanted to show you the robot, but we're short on time"). Besides the requests, its readings are a remark to the audience, talk about the slide on air, a request cut off before its object ("can you pull up, um"), a plan for later, and the choice of something offered.
3. **What the screen allows.** What was on screen when the sentence began rules out requests that cannot apply, such as "next slide" before any slide was shown, or scrolling a slide.
4. **Finding the target.** The target is looked up, never generated. A slide number counts within the current deck. A slide or a file is found by meaning: how much closer the sentence is to it than a request that names nothing ("can you pull up the, um") is. Below 0.09, the sentence names nothing. Slide titles and deck names also count word by word. A website or app is found by name, loosely by spelling ("git hub") and for brand names by sound ("laws hack" is LauzHack). A spoken address opens as it is. "Back to the slides" returns to the last slide shown unless the sentence names a better one. Talk about the slides follows you up to four slides ahead when it fits a later slide clearly better, and suggests the slide when it fits only a little better.
5. **Deciding.** The screen changes when the likeliest reading is at least `--threshold` (0.5) likely at the end of an utterance. Before the end, it must be 0.9 likely, the previous transcript must agree, and an address that matches no known website waits for the end. When several files fit about as well, the copilot offers them, and "the second one" or "the revised one" picks. A request with no target found (a search, a link to click, a website without a bookmark) goes to the vision model only when the classifier is 0.95 sure. The vision model answers with a URL, a file to find with Spotlight, a control, or a job for the screen agent. With the d1 router on, a sentence the classifier is unsure of (0.25 to 0.5) goes to Liquid's d1 in llama-server, which picks among the three likeliest readings or keeps the screen.
6. **Showing.** The stage (`stage.py`) is Chrome in kiosk mode in the VM, driven over its DevTools protocol. Slides are rendered once and swap in 10 to 50 ms. Websites and files open in tabs of their own. Page controls run as JavaScript in the tab on screen. If something closes the stage tab, the next action opens it again.

Side talk in another language does nothing, since a transcript must read as English. Pass `--any-language` to present in another language. With `--input parakeet`, a sound must also be at least `--focus` (default 0.3, about 10 dB) times as loud as your own recent speech.

`uv run stage.py` brings up the stage and times slide jumps, a website and controls, with no model loaded.

In the call, share the window titled "Copilot screen".

## Evaluate

- `uv run copilot.py eval eval/*.jsonl` replays scripted talks through the same `Brain` the live copilot runs, feeding each line two words at a time as the microphone would (`--step`), and scores what is on screen after each line. Add `--tts` to speak each line with `say` and transcribe it back instead.
- `modal run eval/modal_eval.py` runs the same eval on a Modal GPU, so no model loads on the Mac.
- `sh eval/compare.sh LiquidAI/d1-2B-GGUF:Q8_0 h2oai/h2o-lightning-4b-gguf:Q4_K_M` compares decision models on every script. Any model that llama-server answers `/v1/systemone` for works.
- `web_holdout.jsonl` and `talk_holdout.jsonl` are held out. Do not tune the intent examples (`intents.py`) or the constants in `brain.py` on them.
- `uv run --group eval lipread/lipread.py eval VIDEO...` measures the lip-reading word error rate against the audio track.
- `uv run lipread/lipread.py gate lipread/cache/B1_norman.npz` scores the lip-motion gate from cached tracking, without a model.

## Known limits

- The lip-reading word error rate of this live setup is not measured yet.
- The VM sees your folders read-only, so it cannot save into them, including Safari downloads.
- Only the bookmarked websites (`SITES` in `brain.py`), the apps in the VM and spoken web addresses open directly. Any other website, or "our website", goes to the vision model, which takes a second or more.
- Apple's recognizer delivers words about a second behind the audio. An action fires 0.1 to 1.5 s after you stop, and a decision takes about 40 ms. A misheard word that is not a known name still misleads the brain.
- The voice gate compares loudness, not voices. Someone as close to the microphone as you gets through. Telling voices apart needs a speaker-embedding model.
- File lookups use a Spotlight list of the images, videos, PDFs, presentations, spreadsheets and documents in your three folders, built in the background in about 13 s. A lookup then takes about 40 ms. Before the list is ready, and for files newer than it, Spotlight answers in about 0.8 s.
- The decision model's prompt cache is off in llama-server, so each call reads the whole prompt again (about 0.5 s for 650 tokens on this Mac).
- The VM uses 4 CPUs and the RAM set in its config.json, 8 GB by default and 4 GB here.
- The lip-motion gate assumes steady face tracking. A noisy webcam image can open it while your lips are still.

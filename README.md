# Molière

Molière listens to you during a call and puts the right slide, file, website or app on the screen you share. You talk to your audience as usual. Molière works out when you are asking for something and drives the shared screen for you.

It is one Kotlin Compose Multiplatform app: a desktop app on your Mac and an Android companion on the phone in your hand. The Mac recognizes speech from either microphone. Both presentation models run on your Mac. The models run inside the app, and there is no model server.

## What the presenter sees

A small bar at the bottom of your screen shows what Molière heard and what it is doing. When you ask for something by meaning, such as "So how big is this market really?", the bar first says what it is about to show, for example "Showing slide 5", next to a Stop button that drains for 2.5 seconds. To stop it, say "no", or press Stop on the bar or on the phone. Nothing reaches the shared screen. If you let the wait run out, it is shown. Up to 20 seconds after something is shown, "no, go back" puts the previous screen back.

Commands on what is already on screen act at once: "Next slide please.", "Go to slide 7.", "Scroll down a bit.", "Close that." Chatter and mentions do nothing. "I sent the PDF to Marc this morning." is not a request.

The phone shows the same mole and the same line as the bar, with the same buttons. It buzzes when something needs you: a light tick for an announce, a firm double buzz for a choice between files, and two soft pulses for a failure.

Outside a call, Molière is asleep: no microphone, no shared screen, no phone link. It wakes when a call app takes the microphone, or when you choose **Start a meeting** from the mole in the menu bar.

## How a sentence reaches the screen

1. **Hearing.** Apple's speech recognizer (SpeechTranscriber, macOS 26) turns your voice into words on your Mac as you speak. `Ear.kt` builds and runs it from `hear.swift` and tells it which names to expect: your decks, slide titles, websites and apps.
2. **The session.** `Session.kt` passes each new version of the utterance to the agent, with what the audience sees now. `Contract.kt` is the contract between the ear, the agent and the screen: `Heard` and `OnScreen` go in, a `Move` comes out.
3. **Retrieval.** EmbeddingGemma 2 embeds the utterance and takes eight candidates from the catalog in `Catalog.kt`. The catalog holds the slides of the meeting's decks, the bookmarks, the apps, and things you saw recently: files you used, pages from your browser history and decks elsewhere on your Mac.
4. **Decision.** `Prompt.kt` writes what is on screen, the last utterances and the numbered candidates into a short prompt. A Gemma 3 270M that we fine tuned for this task, `decider.litertlm`, answers with one line from a small grammar, such as `show 3`, `next`, `control scroll down`, `undo` or `nothing`. At the end of an utterance, the answer is constrained to the grammar of what the screen allows.
5. **Action.** `ModelAgent.kt` turns the answer into a move. A pick by meaning (`show`, `open` or `search`) is announced first, so that a "no" can stop it. Commands such as `next`, `slide 7` or `close` act at once. While you are still speaking, Molière decides every two words and acts early only when two decisions in a row agree. The session carries out one move at a time and tells the bar and the phone what happens.
6. **The shared screen.** The audience sees Chrome in kiosk mode in a macOS VM, driven over its DevTools protocol by `Stage.kt`. Molière draws each page of your PDFs once, then swaps the slides in. Websites and files open in tabs in front of the deck. Apps open in the VM, and `agent-desktop` makes their window fill the screen. `Vm.kt` runs the VM through `agent-desktop`. In the call, you share the VM window, titled "Molière screen".

Both models run in the app's process through LiteRT-LM. The model alone decides what to show. No word list or pattern does. `tune/CONTRACT.md` defines the state, the prompt, the grammar and what each answer does, and both the training side and the app follow it. The `--rules` flag brings back the hand written rules of the earlier prototype.

## Run it

You need:

- A Mac with macOS 26 or newer and [mise](https://mise.jdx.dev/) 2026.11.8 or newer.
- Xcode Command Line Tools with a macOS 26+ SDK. Setup opens Apple's installer if the tools are missing. Finish that installer and rerun `mise install`; an outdated SDK must be updated through Apple's Software Update.
- For the optional VM, a macOS restore image and the guest setup described below. The app falls back to a Chrome window on your Mac when no VM is configured.

### Install and run

From the repository root:

```sh
mise trust
mise install
```

`mise.toml` declares Temurin JDK 21, Android SDK command-line tools 23.0, Node.js 24, Python 3.12, uv, and `agent-desktop`. The `agent-desktop` installation enables its own native-binary installer. The postinstall hook installs missing Android SDK Platform 36, Build Tools 36.0.0 and platform tools, installs Google Chrome through mise's built-in cask manager, and generates the three sample decks in `library/`. Accept Android licenses and any native installer prompts when asked. An existing Chrome installation is kept. Homebrew is not required.

Run the desktop app:

```sh
mise run desktop
```

This builds `Moliere.app`, including its native runtime and macOS permission declarations, and runs it in the terminal with `library/` as the decks folder. Press Ctrl+C or choose **Quit Molière** in the menu bar to stop. Extra arguments reach the app, for example `mise run desktop --host` to always use Chrome on this Mac.

Run the Android companion on a connected, authorized phone or a booted emulator:

```sh
mise run android
```

This checks the device connection, builds, installs, and opens the app. Keep one device connected, or select it with `ANDROID_SERIAL`. The Android SDK task skips installation when all required packages are present.

Other commands:

| Command | What it does |
| --- | --- |
| `mise run desktop:demo` | Scripted floater demo, without microphone, models, or a VM. |
| `mise run desktop:typed` | Type commands instead of speaking, using Chrome on this Mac. |
| `mise run desktop:build` | Build `composeApp/build/compose/binaries/main/app/Moliere.app`. |
| `mise run build` | Build the Mac app and Android debug APK. |
| `mise run fixtures` | Generate missing or outdated sample decks. |

The Gradle wrapper downloads Gradle, Kotlin, Compose, LiteRT-LM, and other application dependencies on first use. Android Studio and a separate Gradle installation are optional.

On its first start, Molière downloads EmbeddingGemma 2 to `~/Library/Application Support/Moliere/models/`. It does not download the decider. Put `decider.litertlm` in the same folder. It comes from the fine tune in `tune/`, which "How it is tested" describes. Without it, the hand written rules decide.

### Try it without a microphone

To see the bar without models or a VM, play the scripted story:

```sh
mise run desktop:demo
```

To check the whole chain except the microphone, type the lines instead of saying them. This task uses Chrome on your Mac. To use a configured VM instead, run `mise run desktop --typed --no-history`.

```sh
mise run desktop:typed
```

Wait for "Hi. I'm listening.", then type each line and press Return:

```text
Hi everyone, can you all hear me okay?
Okay, let's open up the company slides.
Next slide please.
Go to slide 7.
So how big is this market really?
```

The first line does nothing. The next three act at once. The bar announces the last one, then slide 5 is shown. Press `Ctrl-C` to stop. The log is `~/Library/Logs/Moliere/moliere.log`, and what the audience saw after each change is in `~/Library/Logs/Moliere/screens/`.

### Present with your voice

```sh
mise run desktop
```

Molière has no Dock icon. Its mole is in the menu bar. The mise launcher uses the sample decks in `library/`; choose **Choose decks folder…** to use your own. Opening the built app directly defaults to `~/Documents/Moliere`. Choose **Start a meeting**, allow each permission macOS asks for, and share the "Molière screen" window in your call.

### Add the phone

To install the phone app on a connected Android phone, run `mise run android`. For an emulator, run `mise run android:emulator:setup` once, then `mise run android:emulator` in another terminal before `mise run android`. Put the phone and the Mac on the same network, for example the phone's hotspot. The phone finds the laptop once a meeting runs. It shows a full-width preview of the shared slide or browser tab.

Allow microphone access and keep the companion open. Speak near the Mac for a few seconds so Molière can compare the two microphones. As you walk away with the phone, the mic indicator switches to **Phone** when the phone hears you better. It returns to **Laptop** when you come back or leave the companion. During a meeting the phone streams microphone audio to the Mac over the local, unencrypted WebSocket connection. The Mac transcribes it locally. Leaving the Android app stops the stream. Your call app manages its own microphone.

`DEMO.txt` is the full walkthrough: the 17 test lines with what each must do, permissions, the phone, what to do when something goes wrong, and a three minute run for the jury. The comment above `fun main` in `composeApp/src/desktopMain/kotlin/ch/lauzhack/app/Main.kt` lists every flag.

## One-time VM setup

The shared screen is a macOS VM that `agent-desktop` runs. Set it up once:

1. Run `mise run vm:setup`. The VM provider is optional and is not installed by the postinstall hook.
2. Get a macOS 26.x image. Apple's catalog now offers only macOS 27.x, which a macOS 26.4 host cannot run. The 26.4 image is at `https://updates.cdn-apple.com/2026WinterFCS/fullrestores/122-00766/062A6121-2ABE-45D7-BCB1-72B666B6D2C2/UniversalMac_26.4_25E246_Restore.ipsw`. It is 19.7 GB, and `shasum` prints `177baf85518c6e9cebf83990e11e59259b5c97dd` for it.
3. Run `mise run vm:create /absolute/path/to/restore.ipsw`. Repeat `mise exec -- agent-desktop sandbox status base` until it stops answering `working`. The install writes about 21 GB. Delete the image afterwards.
4. Optional, to save RAM: in `~/.agent-desktop/sandboxes/base/vm/config.json`, lower `memorySize` from 8 GB (8589934592) to 4 GB (4294967296).
5. Run `mise run vm:start`.
6. In the guest, finish Setup Assistant. Give the account the same short name as your Mac account (`cat ~/.agent-desktop/sandboxes/base/guest_user`). Turn on automatic login in System Settings > Users & Groups.
7. In the guest, open Terminal and run `sh "/Volumes/My Shared Files/guest-setup.sh"`.
8. In the guest, turn on Remote Login in System Settings > General > Sharing.
9. In the guest, allow Accessibility for `sshd-keygen-wrapper` when macOS asks, or turn it on in System Settings > Privacy & Security > Accessibility. Molière drives apps in the VM over SSH, which needs it. The Python prototype's screen agent also needs Screen & System Audio Recording.
10. On the host, `agent-desktop sandbox status base` answers `running` when setup is complete.

Molière does the rest itself at the start of a meeting, whenever the VM cannot read the app's files or has no Chrome. `Vm.setup` in `Vm.kt` shares your Desktop, Documents and Downloads with the VM, read only and at the same paths. It gives the VM your screen's size and density, copies your wallpaper, dark mode and Dock settings, installs Google Chrome and restarts the VM. To share the folders, it patches the VM host of `agent-desktop`, so it runs again after you update `agent-desktop`. From then on, Molière starts the VM by itself when it is stopped. If you rerun step 7 later, the script is at `/Volumes/My Shared Files/agent-desktop/guest-setup.sh`.

## How it is tested

The desktop tests cover the session loop against a fake screen, the catalog, the ear's output, the VM setup, and the prompt builder against the 243 cases in `tune/golden.jsonl`, byte for byte. They also check the app's colours and type against `mole/`. The last run recorded in `DEMO.txt` had 30 suites, 102 tests and 0 failures.

```sh
mise exec -- ./gradlew :composeApp:desktopTest
```

`rehearsal/run.sh` feeds a scenario to the packaged app through a named pipe, against the real VM, with browser history off. Arguments after the gap in seconds go to the app, for example `--host`. `rehearsal/check.py` scores each line against its expectation and names the cause of each miss: not retrieved, decided otherwise, model wording or stage. A scenario line is `words || expectation`. `rehearsal/demo.txt` holds the demo lines, `rehearsal/model-1.txt` a harder set, and `rehearsal/held-1.txt` a set that nothing was tuned on.

```sh
rehearsal/run.sh rehearsal/demo.txt .stage/rehearsal/demo 6
python3 rehearsal/check.py .stage/rehearsal/demo
```

`androidApp/check.sh` checks the phone app end to end. It boots a headless emulator unless a device is attached, installs the debug APK, walks every state against a scripted laptop and saves screenshots to `.stage/phone`. It boots the AVD named in `AVD`, `Pixel_8_API34` by default. With the emulator from `mise run android:emulator:setup`:

```sh
AVD=lauzhack-2026 mise exec -- androidApp/check.sh
```

The decider is trained and scored on Modal, so no model loads on the Mac. `tune/build.py` composes the training data, and `tune/modal_tune.py` fine tunes, converts and scores the model. `derisk` checks the pipeline on ten training steps and downloads the base model to the `decider` volume. The other steps take the name of the run, here `r5`. Use your own Modal profile.

```sh
export MODAL_IMAGE_BUILDER_VERSION=2025.06
modal run tune/modal_tune.py::derisk
modal run tune/modal_tune.py::train --name r5
modal run tune/modal_tune.py::convert_run --name r5
modal run tune/modal_tune.py::evaluate --name r5
modal volume get decider r5/decider_q8_ekv1024.litertlm "$HOME/Library/Application Support/Moliere/models/decider.litertlm"
```

`evaluate` replays every script in `eval/` and the dev split through the converted model, the way the app runs it. `mise exec -- modal run eval/modal_decider.py` replays the scripts through the Kotlin `ModelAgent` itself. `tune/report.py` prints a run's scores beside the Python prototype's.

## Where things are

- `composeApp/src/commonMain/kotlin/ch/lauzhack/app/`: the code the laptop and the phone share. `Contract.kt` is the contract between the ear, the agent and the screen. `session/Session.kt` is the loop. `decider/` holds `ModelAgent.kt`, the catalog and retrieval in `Catalog.kt`, and the prompt and grammar in `Prompt.kt`. `mole/` draws the bar and the phone screen, and `link/` connects the laptop and the phone. `agent/` holds the hand written rules for `--rules`.
- `composeApp/src/desktopMain/kotlin/ch/lauzhack/app/`: the Mac app. `Main.kt` has the flags and the menu bar. `session/Laptop.kt` wakes everything for a call. `stage/` has the ear in `Ear.kt`, the shared screen in `AgentDesktop.kt`, `Stage.kt` and `Vm.kt`, the decks, and recent files and browser history. `meeting/` detects calls.
- `composeApp/src/litert/`: the model runtime, LiteRT-LM in the app's process. Only the desktop app includes it. The phone loads no model.
- `composeApp/src/androidMain/` and `androidApp/`: the phone app.
- `composeApp/src/commonTest/` and `composeApp/src/desktopTest/`: the tests.
- `tune/`: the fine tune of the decider. `tune/CONTRACT.md` is the contract between training and the app, and `tune/prompt.py` is its reference implementation.
- `rehearsal/`: scored scenarios driven through the packaged app.
- `eval/`: the scripts the models are scored on, and the Modal runners. Each line of a script is something said and what the screen should show after it.
- `fixtures/make_decks.py`: draws the three sample decks into `library/`, which git ignores. They are a company overview, Q3 financials and a product roadmap.
- `mole/`: the design page the app's look mirrors, `mole/index.html`.
- `DEMO.txt`: setup and a list of things to try, written from commands that were run.

## What it still gets wrong

Section 8 of `DEMO.txt` lists what the scored rehearsals got wrong. These are measured results:

- Some requests by meaning miss. "Let me show you who we are up against." went to the Team slide instead of Competition. "And what does it cost?" and "How much cash do we have left?" did nothing.
- "Can you open example.com for me?" opened google.com. "Open get hub.", a mishearing of GitHub, announced a slide.
- A slide number beyond the deck is cut short: "Go to slide 99." went to slide 9.
- Of 12 requests for things that do not exist, 7 did nothing and 5 announced an unrelated slide or app. Say "no", or it is shown when the wait runs out.

The scores were 17 of 17 on the demo lines and 37 of 45 on the harder set. The old hand written rules scored 28 of 41 on the lines both can do. The set that nothing was tuned on scored 27 of 45. From the end of your voice, a command reaches the shared screen in 1.5 to 1.8 seconds, and an announce appears after about 2 seconds.

`DEMO.txt` also marks what no person has tried yet: the permission prompts, the menu items and the Stop button on the bar, the meeting starting by itself when a call begins, and a real phone. On a phone, that means hearing through it, finding the laptop over a hotspot and how the buzz feels. The browser on the shared screen has none of your accounts signed in, so a page that needs a login shows a login page.

## The earlier Python prototype

Before Molière was one Kotlin app, it was a Python prototype: an intent classifier trained on the example sentences in `intents.py`, rules around it, and models served by Ollama. Its files are `copilot.py`, `brain.py`, `intents.py`, `ear.py`, `hear.swift`, `stage.py`, `stage.html`, `vm.py`, `lipread/` and `pyproject.toml`. It is not the product. It stays in the repo as the baseline that the evals compare against, and `--rules` runs a Kotlin port of its rules. To score it on every script in `eval/` with the sample decks in `library/`, pull its embedding model into Ollama and run its eval. `modal run eval/modal_eval.py` runs the same eval on Modal.

```sh
ollama pull embeddinggemma-2:270m
uv run copilot.py --router none eval eval/*.jsonl
```

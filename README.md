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

- `uv run copilot.py live` listens to the microphone.
- `uv run copilot.py live --input lips` reads your lips from the webcam, without sound. Add `--camera N` to pick another webcam and `--preview` to see the mouth crop and the lip-motion bar. If the gate opens while your lips are still, or stays shut while you mouth words, tune it alone with `uv run lipread/lipread.py live --preview --sensitivity 4`, then pass the same `--sensitivity` to `copilot.py live`.
- `uv run copilot.py live --input keys` takes typed lines, for testing.

Add `--dry` to print the VM commands instead of running them. A dry run needs no VM.

Before the VM is set up, add `--host` to drive this Mac directly, for example `uv run copilot.py --host live --input keys`. The stage is then a separate Chrome window with its own profile (`~/.copilot-chrome`), so share that window. The screen agent clicks and types on your desktop in this mode.

How a sentence reaches the screen:

1. The microphone transcribes while you speak (Parakeet's streaming mode), so the words are ready when you stop.
2. Every new transcript of the sentence goes to the brain (`brain.py`), so a request acts as soon as its words are heard, mid-sentence included. EmbeddingGemma embeds the sentence, and an intent classifier reads that vector together with the sentence's words and word pairs. The classifier is a small softmax regression, trained in under a second at start-up on the example sentences in `intents.py`. It tells remarks to the audience and talk about the slides apart from requests: a slide by name or number, the next or previous slide, back to the slides, a website or app to open, a search, a link to click, a control of the page on screen (scroll, zoom, play, pause, full screen, back), or closing what is on screen.
3. What was on screen when the sentence began rules out requests that cannot apply, such as "next slide" before any slide was shown, or scrolling a slide. The target is then looked up, not guessed. A slide is found by its number in the current deck, or else by meaning across every deck. A website or app is found by name among about 40 bookmarks and the apps in the VM, loosely enough that "git hub" finds GitHub. A spoken address such as "lauzhack dot com" opens as it is. "Back to the slides" returns to the last slide shown unless you name another. While you talk about the slide on air, the copilot follows you up to four slides ahead in its deck.
4. The screen changes when the likeliest kind of request is at least `--threshold` (default 0.5) likely. Before the sentence ends, it must be 0.9 likely and the previous transcript must agree. When the classifier is unsure (from 0.25 up to the threshold), Liquid's d1 in llama-server picks among the three likeliest readings or keeps the screen, in one forward pass with no generated text to parse. `--router none` turns this off and skips llama-server. A search, a file, a website without a bookmark or a link to click waits for the end of the sentence and goes to the vision model, which answers with a URL, a file name to find with Spotlight, a control, or a job for the screen agent.

The log marks live transcripts `~`, pauses `…` and ends of utterances `>`. Every "after you stopped" time counts from your last voiced sound.

Voices around you are left out in two ways. A sound must be at least `--focus` (default 0.3, about 10 dB) times as loud as your own recent speech, and a transcript must read as English, so side talk in French does nothing. Pass `--any-language` to present in another language, and lower `--focus` if your own words get cut.

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
- The embedding model runs on every new transcript while you speak, and the decision model on the sentences the classifier is unsure of. Their latency next to Parakeet on this Mac is not measured yet.
- The voice gate compares loudness, not voices. Someone as close to the microphone as you gets through. Telling voices apart needs a speaker-embedding model.
- File lookups use a Spotlight list of the images, videos, PDFs, presentations, spreadsheets and documents in your three folders, built in the background in about 13 s. A lookup then takes about 40 ms. Before the list is ready, and for files newer than it, Spotlight answers in about 0.8 s.
- The decision model's prompt cache is off in llama-server, so each call reads the whole prompt again (about 0.5 s for 650 tokens on this Mac).
- The VM uses 4 CPUs and the RAM set in its config.json, 8 GB by default and 4 GB here.
- The lip-motion gate assumes steady face tracking. A noisy webcam image can open it while your lips are still.

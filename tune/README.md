# tune: the decider's fine tune

The decider is a Gemma 3 270M fine tuned to answer one grammar line per prompt. It is trained and scored on Modal; nothing here loads a model on the Mac. `CONTRACT.md` is the contract with the app: the state, the prompt, the grammar and what each answer does. `prompt.py` is its reference implementation, and the app reproduces `golden.jsonl` byte for byte.

- `build.py` composes the training data from `intents.py` (what presenters say), `world.py` (decks, files, sites, apps, mishearings), `refs.py` and `decks.py`.
- `modal_tune.py` fine tunes, converts to `.litertlm` and scores on Modal.
- `score.py` replays the eval scripts in `eval/` and the dev split through a converted model, the way the app runs it; `report.py` prints a run's scores.
- `retrieval.py` calibrates the number of candidates and the deck bonus on saved vectors, with numpy only.

```sh
export MODAL_IMAGE_BUILDER_VERSION=2025.06                 # and your own MODAL_PROFILE
modal run tune/modal_tune.py::derisk                       # ten steps from the base, convert, compare; fetches the base model
modal run tune/modal_tune.py::train --name mine
modal run tune/modal_tune.py::convert_run --name mine
modal run tune/modal_tune.py::evaluate --name mine
modal volume get decider mine/decider_q8_ekv1024.litertlm "$HOME/Library/Application Support/Moliere/models/decider.litertlm"
```

The installed model is round 4, `r4/decider_q8_ekv1024.litertlm` on the `decider` volume. `mise exec -- modal run eval/modal_decider.py` replays the eval scripts through the Kotlin `ModelAgent` itself, with r4 unless `--model` names another.

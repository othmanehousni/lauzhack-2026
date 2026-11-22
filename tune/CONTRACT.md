# Decider contract

The app builds one prompt per decision, sends it as the user turn of a fresh conversation to `decider.litertlm` (LiteRT-LM 0.19.1, CPU, `SamplerConfig(topK=1)`, at most 16 output tokens) and applies the answer. At `done` the conversation is created with the LL_GUIDANCE constraint provider and the message sent with `ResponseFormat.regex(grammar(state))` in which every space is written as `▁` (U+2581): LiteRT-LM's LLGuidance matches SentencePiece's space mark, and a literal space fails with `Parser Error: token "▁" doesn't satisfy the grammar`. Creating a constrained conversation costs about 0.9 s on CPU (sending is 0.1 s), so create the next one ahead of time. Partial decisions run unconstrained and count as `nothing` unless the answer matches `grammar(state)`. In the JVM API that is `ConversationConfig(samplerConfig = SamplerConfig(topK = 1, ...), enableResponseFormat = true)` and `sendMessage(prompt, responseFormat = ResponseFormat.regex(...))`; `litertlm-jvm-0.19.1` on Linux behaves the same (`modal_tune.py::jvm_check`: a literal space throws `LiteRtLmJniException ... Parser Error`, `show▁1|nothing` answers `show 1`, the constrained conversation takes 0.90 s to create). The model's stop tokens are `<end_of_turn>` (106) then `<eos>` (1); the order matters, since under a constraint LiteRT-LM lets only the first one end an answer, and with `<eos>` first every answer that could go on (`slide 5`, `ask 1 3`, any words) ran on to 16 tokens. `tune/prompt.py` is the reference; `tune/golden.jsonl` has 243 cases of `{"input": state, "prompt": ..., "grammar": ...}` that a Kotlin port must reproduce byte for byte.

## State

| field | value |
|---|---|
| `screen` | the item in front of the audience, or null for the desktop |
| `behind` | the slide under `screen` when a file, site or app covers the deck, else null |
| `next` | the slide after `screen` in its deck when `screen` is a slide, else null |
| `offered` | 1-based candidate numbers the last `ask` offered (they are candidates 1..m), else `[]` |
| `shown` | the item the last action put on screen, for the two utterances after it, else null |
| `heard` | earlier finished utterances, oldest first (only the last two are used) |
| `now`, `done` | the utterance so far, and whether the recognizer finished it |
| `candidates` | the items below, in order |

Items: slide `{kind, deck, page, pages, title, text}` (text = bullets joined by `"; "`), file `{kind, name, folder}` (folder = last two parent folders, `" / "`-joined), site `{kind, name, url}` (url without scheme), app `{kind, name}`.

Candidates: the offered items first, then catalog items by descending score until there are 8 (no duplicates). EmbeddingGemma 2 (`embeddinggemma-2-text-270m.litertlm`) embeds each catalog item once as its main document and again by name: a slide as `title: {title} | text: {text}` (a deck's first slide as `title: {title} | text: {deck}; {text}`) and `title: {title} | text: none`, plus `title: {deck} | text: presentation slides` for a deck's first slide; a file as `title: {name} | text: {folder}` and `title: {name} | text: none`; a site as `title: {name} | text: {url}` and `title: {name} | text: {name} website, {url}`; an app as `title: {name} | text: app` and `title: {name} | text: {name} app`. Each decision embeds up to three queries, `task: search result | query: {now}`, the same with `{heard[-1]} {now}` when there is a heard line, and the same with the last six words of `now`. An item's score is its best cosine over its documents and the queries, plus 0.04 for a slide of the deck on screen or behind (the deck of the last slide shown). K = 8 and the 0.04 are calibrated on the dev scripts by `tune/retrieval.py` (recall 78/95 against 71/95 for two queries, main documents and no bonus).

## Prompt

`clean(s)` turns each run of spaces, tabs, CR and LF into one space and trims spaces. `head(s, n)` keeps `s` if `len(s) <= n`, else cuts at `n` characters, drops the last partial word (everything after the last space) and appends `…`. `tail(s, n)` keeps `s` if short, else takes the last `n` characters and drops everything up to and including the first space. Lengths are in UTF-16 code units in Kotlin and code points in Python; the golden cases are ASCII where they truncate.

```
screen: {item, or "desktop"}[ over {behind item without text}]
next: {item}                             only when next is set
offered: {offered numbers joined by " ", or "none"}
shown: {item without text, or "none"}
heard: {tail(clean(h), 200)}            one line for each of the last two
now ({done ? "done" : "speaking"}): {tail(clean(now), 300)}
{i} {item}                               one line per candidate, i from 1
```

Item text: slide `slide {page} of {pages} "{clean(title)}" in {clean(deck)}` then, with text and when `clean(text)` is not empty, `: {head(clean(text), 90)}`. File `file "{clean(name)}"` then ` in {clean(folder)}` if not empty. Site `site {clean(name)} ({url})`. App `app {clean(name)}`. Lines are joined with `\n`, no trailing newline.

## Grammar

`n` = candidate count, `on` = screen kind or null, `deck` = `on == slide` or `behind != null`. Alternatives, joined with `|` in this order:

1. always: `nothing`, `wait`, `unsure`, `slide [1-9][0-9]?`, `open [a-z0-9][a-z0-9 .\-/']{0,40}`, `search [a-z0-9][a-z0-9 .\-']{0,60}`
2. `n >= 1`: `show [1-n]`; `n >= 2`: `ask [1-n] [1-n]( [1-n])?`
3. `deck`: `next`, `previous`; `on != slide`: `slides`
4. `on` is site or file: `click [a-z0-9][a-z0-9 .\-']{0,40}`, `control (scroll down|scroll up|scroll to the top|scroll to the bottom|zoom in|zoom out|reset the zoom|go back a page|go forward a page|play the video|pause the video|full screen|exit full screen)`
5. `on != null`: `close`; `shown != null`: `undo`

## Answers

| answer | the app |
|---|---|
| `nothing` | does nothing |
| `wait` | does nothing; a request is unfinished, its text stays in `heard` for the next utterance |
| `unsure` | does nothing on screen; may tell the presenter it did not find what was asked for |
| `show N` | puts candidate N on screen: a slide goes to that slide; a file, site or app opens in front of the deck |
| `ask N M [K]` | offers those candidates to the presenter; they become `offered` for the next utterance |
| `slide N`, `next`, `previous` | moves in the deck on screen or behind (or the last one shown); out of range does nothing |
| `slides` | closes what covers the deck, or shows the last slide shown |
| `open words` | opens an address (words with a dot) in the browser, or the app or bookmark whose name, lowercased with every character outside `a-z0-9 .-'` as a space and spaces collapsed, is `words` |
| `search words` | web search for the words |
| `click words` | clicks the link or button with that text on the page on screen |
| `control X` | that control on the page or file on screen |
| `close` | closes the front item, or the deck if nothing covers it |
| `undo` | restores the screen from before the last action |

While the utterance is being spoken the app decides every two words. It acts on a partial answer only when it is `show`, `slide`, `next`, `previous`, `slides`, `control` or `close` and the previous partial gave the same answer; every answer acts at `done`, unless that same action was already taken during the utterance. The screen at the start of the utterance is the state's `screen` for all of its decisions. `offered` is cleared by any action and after three finished utterances.

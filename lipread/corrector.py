"""Open-vocabulary corrector: turns the lip model's guesses into the sentence that was mouthed.

Wispr-Flow-style: a small transformer (Qwen3, LoRA-fine-tuned by train_corrector.py on lip-reading confusions)
rewrites the recognizer's output. Besides the guesses it sees their lip shapes and the user's personal dictionary,
so it can produce words the lip model never learned (names, brands, jargon). Runs locally with mlx-lm.

This module stays importable without MLX (train_corrector.py uses its prompt format on Linux GPUs).
"""
import difflib
import functools
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(HERE, "models", "corrector")  # MLX 4-bit model written by train_corrector.py
DICTIONARY = os.path.join(HERE, "dictionary.txt")  # your names and terms, one per line
SHOWN = 15  # dictionary entries shown per sentence: the ones whose lip shapes best match the guesses

# ARPAbet sound -> lip shape. Sounds in one class look the same on the lips (p/b/m, f/v, t/d/n/l, ...).
VISEME = {**dict.fromkeys("P B M".split(), "P"), **dict.fromkeys("F V".split(), "F"),
          **dict.fromkeys("TH DH".split(), "T"), **dict.fromkeys("S Z".split(), "S"),
          **dict.fromkeys("SH ZH CH JH".split(), "J"), **dict.fromkeys("T D N L".split(), "D"),
          **dict.fromkeys("K G NG".split(), "K"), "R": "R", "ER": "R", "W": "W", "HH": "",
          **dict.fromkeys("Y IY IH EY EH".split(), "E"), **dict.fromkeys("AA AE AH AY AW".split(), "A"),
          **dict.fromkeys("AO OW OY UH UW".split(), "O")}
# Fallback for words missing from CMUdict (many names and brands): spelling -> rough lip shape
LETTERS = [("sh", "J"), ("ch", "J"), ("th", "T"), ("ph", "F"), ("ck", "K"), ("qu", "KW"), ("ng", "K"), ("x", "KS"),
           *[(c, "P") for c in "bpm"], *[(c, "F") for c in "fv"], *[(c, "S") for c in "sz"], ("j", "J"),
           *[(c, "K") for c in "cgkq"], *[(c, "D") for c in "tdnl"], ("r", "R"), ("w", "W"), ("h", ""),
           *[(c, "E") for c in "eiy"], ("a", "A"), *[(c, "O") for c in "ou"]]
SYSTEM = ("You turn a lip-reading model's guesses into the sentence the person silently mouthed. Guesses mix up "
          "words that look alike on the lips; brackets give each guess's lip shapes (P=p/b/m F=f/v T=th S=s/z "
          "J=sh/ch/j D=t/d/n/l K=k/g R=r W=w, vowels E/A/O). Names and rare words often come out as several short "
          "look-alike words. Use a dictionary word when it fits the lip shapes. Reply with the sentence only.")

_pron = {}


def pronunciations():
    if not _pron:
        for line in open(os.path.join(HERE, "data", "cmudict.dict")):
            word, *phones = line.split("#")[0].split()
            _pron.setdefault(re.sub(r"\(\d+\)$", "", word), phones)  # first pronunciation
    return _pron


@functools.lru_cache(maxsize=200_000)
def lip_shape(word):
    """'Kyoto' -> 'KEODO'. Repeats merge: two sounds with the same lip shape look like one."""
    word = re.sub(r"[^a-z']", "", word.lower())
    phones = pronunciations().get(word)
    if phones:
        shape = "".join(VISEME.get(re.sub(r"\d", "", p), "") for p in phones)
    else:
        shape, i = "", 0
        while i < len(word):
            for letters, s in LETTERS:
                if word.startswith(letters, i):
                    shape, i = shape + s, i + len(letters)
                    break
            else:
                i += 1
    return re.sub(r"(.)\1+", r"\1", shape) or "-"


def relevant(dictionary, guesses, k=SHOWN):
    """The k dictionary terms whose lip shapes best match a run of 1-5 consecutive words in the top 3 guesses."""
    runs = set()
    for g in guesses[:3]:
        shapes = [lip_shape(w) for w in g.split()]
        for i in range(len(shapes)):
            for j in range(i + 1, min(len(shapes), i + 5) + 1):
                runs.add("".join(shapes[i:j]))

    def score(term):
        shape = "".join(lip_shape(w) for w in term.split())
        return max((difflib.SequenceMatcher(None, shape, r).ratio() for r in runs
                    if abs(len(r) - len(shape)) <= 3), default=0)
    return sorted(dictionary, key=score, reverse=True)[:k]


def build_prompt(guesses, dictionary=()):
    lines = [f"dictionary: {', '.join(dictionary)}"] if dictionary else []
    lines += [f"{i}. {g} [{' '.join(lip_shape(w) for w in g.split())}]" for i, g in enumerate(guesses, 1)]
    return "\n".join(lines)


def messages(guesses, dictionary=()):
    return [{"role": "system", "content": SYSTEM}, {"role": "user", "content": build_prompt(guesses, dictionary)}]


class Corrector:
    def __init__(self, path=MODEL):
        import mlx.core as mx
        from mlx_lm import load
        from mlx_lm.models.cache import make_prompt_cache
        self.model, self.tok = load(path)
        self.dictionary = [t.strip() for t in open(DICTIONARY)] if os.path.exists(DICTIONARY) else []
        self.dictionary = [t for t in self.dictionary if t and not t.startswith("#")]
        # The system prompt is the same every time: process it once and keep its attention cache (~0.4 s saved).
        text = self.tok.apply_chat_template(messages(["\x00"]), add_generation_prompt=True, tokenize=False)
        self.prefix = self.tok.encode(text[:text.index("1. \x00")], add_special_tokens=False)
        self.cache = make_prompt_cache(self.model)
        self.model(mx.array(self.prefix)[None], cache=self.cache)
        mx.eval([c.state for c in self.cache])

    def __call__(self, guesses):
        from mlx_lm import generate
        from mlx_lm.models.cache import trim_prompt_cache
        shown = relevant(self.dictionary, guesses) if self.dictionary else []
        prompt = self.tok.apply_chat_template(messages(guesses, shown), add_generation_prompt=True, tokenize=False)
        ids = self.tok.encode(prompt, add_special_tokens=False)
        if ids[:len(self.prefix)] != self.prefix:  # tokenizer merged across the boundary: no cache this time
            return generate(self.model, self.tok, prompt, max_tokens=60).strip()
        try:
            return generate(self.model, self.tok, ids[len(self.prefix):], max_tokens=60, prompt_cache=self.cache).strip()
        finally:  # back to just the system prompt for the next sentence
            trim_prompt_cache(self.cache, self.cache[0].offset - len(self.prefix))


if __name__ == "__main__":  # self-check of the lip-shape and dictionary helpers
    assert lip_shape("bomb") == lip_shape("mop") == "PAP", (lip_shape("bomb"), lip_shape("mop"))
    assert lip_shape("fan") == lip_shape("van")
    assert lip_shape("Kubernetes") != "-"  # not in CMUdict: spelling fallback
    terms = ["Kubernetes", "Priya", "Reykjavik", "Siobhan", "quinoa", "Tesla"]
    print(relevant(terms, ["the cooper net ease migration"], k=2))
    print(build_prompt(["call with pria about the cooper net ease migration"], ["Priya", "Kubernetes"]))

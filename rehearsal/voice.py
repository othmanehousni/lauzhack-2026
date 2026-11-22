#!/usr/bin/env python3
"""rehearsal/voice.py <scenario file> <out dir> [gap seconds] [extra app flags...]: run.sh's scenario, spoken. Each line's
words are synthesized with `say -o` (never played aloud), in each voice of VOICES (Samantha,Daniel), at a laptop
microphone's level over room noise (LEVEL and NOISE, RMS of full scale), and heard by the packaged app with --audio,
the library's decks and browser history off; then check.py scores each voice's <out dir>/<voice> against the same
expectations as a typed run. Inside the words, say's own commands shape the speech: [[slnc 1300]] is a pause of
1300 ms, [[volm 0.3]] says what follows at 0.3 of the volume. "@N" sets the pause after the following lines, as in
run.sh (the helper adds 2 s of silence after each file). For each line it also prints what the ear finished hearing
while it played. BUNDLE names another folder holding Moliere.app, LIBRARY another folder of decks."""
import array, math, os, random, re, subprocess, sys, time, wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(os.environ.get('BUNDLE', os.path.join(ROOT, 'composeApp/build/compose/binaries/main/app')), 'Moliere.app/Contents/MacOS/Moliere')
LIB = os.environ.get('LIBRARY', os.path.join(ROOT, 'library'))
VOICES = os.environ.get('VOICES', 'Samantha,Daniel').split(',')
LEVEL, NOISE = float(os.environ.get('LEVEL', '0.03')), float(os.environ.get('NOISE', '0.002'))
HELPER_GAP = 2.0  # hear.swift's silence after each file
RATE = 22050


def plain(words): return re.sub(r'\s*\[\[.*?\]\]\s*', ' ', words).strip()


def lines(scenario, gap):
    """(category, words, expectation, pause after) for each scenario line."""
    out, cat = [], ''
    for raw in open(scenario):
        line = raw.rstrip('\n')
        if not line.strip(): continue
        if line.startswith('#'): cat = line[1:].strip(); continue
        if line.startswith('@'): gap = float(line[1:]); continue
        words, _, want = line.partition(' || ')
        out.append((cat, words.strip(), want.strip(), gap))
    return out


def synthesize(items, voice, dest):
    os.makedirs(dest, exist_ok=True)
    for f in os.listdir(dest): os.remove(os.path.join(dest, f))
    rng, said = random.Random(7), os.path.join(dest, 'said.wav')
    for k, (_, words, _, gap) in enumerate(items):
        subprocess.run(['say', '-v', voice, '-o', said, f'--data-format=LEI16@{RATE}', words], check=True)
        with wave.open(said) as w: speech = array.array('h', w.readframes(w.getnframes()))
        voiced = [x for x in speech if abs(x) > 300]
        gain = LEVEL / (math.sqrt(sum(x * x for x in voiced) / max(1, len(voiced))) / 32768)
        lead = int((1.5 if k == 0 else 0.2) * RATE)  # the first file waits for the session to listen
        tail = int(max(0.0, gap - HELPER_GAP) * RATE)
        samples = [0] * lead + [x * gain for x in speech] + [0] * tail
        noise = NOISE * 32768
        out = array.array('h', (max(-32767, min(32767, round(x + rng.gauss(0, noise)))) for x in samples))
        with wave.open(os.path.join(dest, f'{k + 1:02d}.wav'), 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(out.tobytes())
    os.remove(said)


def run(audio, log, flags):
    while subprocess.run(['pgrep', '-x', 'Moliere'], capture_output=True).stdout.strip(): time.sleep(5)
    with open(log, 'w') as f:
        p = subprocess.Popen([APP, '--audio', audio, '--library', LIB, '--no-history'] + flags, stdout=f, stderr=subprocess.STDOUT)
    start = time.time()
    try:
        while p.poll() is None and time.time() - start < 1200:
            if 'ear: heard every file' in open(log, errors='ignore').read(): time.sleep(8); break
            time.sleep(1)
    finally:
        p.terminate()
        try: p.wait(20)
        except subprocess.TimeoutExpired: p.kill()


def scored(items, log, out):
    """run.sh's out.txt from the app's log: each line's ">>> words || expectation" where its file starts playing."""
    cats = set()
    with open(out, 'w') as o:
        for raw in open(log, errors='ignore'):
            m = re.search(r'  ear: playing (\d+)$', raw.rstrip('\n'))
            if m and int(m[1]) < len(items):
                cat, words, want, _ = items[int(m[1])]
                if cat and cat not in cats: cats.add(cat); o.write(f'### {cat}\n')
                o.write(f'>>> {plain(words)}{" || " + want if want else ""}\n')
            o.write(raw)


def heard(items, log):
    """What the ear finished hearing while each line played."""
    per, k = [[] for _ in items], -1
    for raw in open(log, errors='ignore'):
        m = re.search(r'  ear: playing (\d+)$', raw)
        if m: k = int(m[1]); continue
        f = re.search(r'  ear     final  "(.*)"  unchanged', raw)
        if f and 0 <= k < len(items): per[k].append(f[1])
    return per


if __name__ == '__main__':
    scenario, dest = sys.argv[1], os.path.abspath(sys.argv[2])
    gap = float(sys.argv[3]) if len(sys.argv) > 3 else 6.0
    flags = sys.argv[4:]
    if not os.access(APP, os.X_OK) or not os.path.isdir(LIB): sys.exit(f'no app at {APP} or no decks in {LIB}')
    items = lines(scenario, gap)
    for voice in VOICES:
        out = os.path.join(dest, voice.lower())
        os.makedirs(out, exist_ok=True)
        synthesize(items, voice, os.path.join(out, 'in'))
        with open(os.path.join(out, 'public.txt'), 'w') as f:
            f.writelines(n[:-4] + '\n' for n in sorted(os.listdir(LIB)) if n.endswith('.pdf'))
        log = os.path.join(out, 'app.txt')
        run(os.path.join(out, 'in'), log, flags)
        scored(items, log, os.path.join(out, 'out.txt'))
        print(f'\n{voice}: heard')
        for k, ((_, words, _, _), finals) in enumerate(zip(items, heard(items, log)), 1):
            print(f'{k:3d}  {plain(words)[:56]:56s}  {" | ".join(finals)}')
        print(f'\n{voice}: scored')
        sys.stdout.flush()
        subprocess.run([sys.executable, os.path.join(ROOT, 'rehearsal', 'check.py'), out])

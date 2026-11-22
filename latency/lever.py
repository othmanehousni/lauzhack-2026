#!/usr/bin/env python3
"""latency/lever.py <out dir> [log]: the time from the end of the presenter's voice to what the audience sees, through
the whole app: the real recognizer, the model and the VM. It synthesizes SENTENCES to audio files with `say -o` (no
sound is played), waits until no other Molière runs, runs the bundle on them with --audio, the library's decks and
browser history off, and prints for each sentence the time from the end of the voice to the last live word, to the
recognizer's final text, to the decision that acted, and to the screen call (to the announce for a pick); then the
medians and worst cases. Given a log it only reads it. BUNDLE names another folder holding Moliere.app, LIBRARY
another folder of decks, VOICE another voice for `say`."""
import os, re, statistics, subprocess, sys, time, wave

# (kind, words, what the audience should see after it). Kinds: command, pick (announced, then shown), no (stops the
# announce in play before it is shown), early (words that go on after a command-like start: nothing may act before the
# rest of them is heard). [[slnc N]] is N ms of silence; a pick is followed by silence for its announce to land. A
# "no" follows its pick in one file, [[after N]] ms after its voice, as a presenter who sees the wrong announce says
# it, not after the silence the helper leaves after each file. Sooner than about 1.5 s, the recognizer may hear both as
# one utterance: it ends one only 0.6 s after its last words arrive, which can be 1 s after the voice.
SENTENCES = [
    ('command', "Okay, let's open up the company slides.", 'slide 1'),
    ('command', 'Next slide, please.', 'slide 2'),
    ('early', 'Next slide is about pricing.', 'keep'),
    ('pick', 'So how big is this market, really?', 'announce slide 5'),
    ('no', '[[after 1400]] No not that.', 'keep'),
    ('command', 'Go to slide 7.', 'slide 7'),
    ('early', 'Close to forty percent of our customers renew every year.', 'keep'),
    ('early', 'Go to slide [[slnc 1000]] three.', 'slide 3'),
    ('command', 'Previous slide.', 'slide 2'),
    ('pick', 'Open GitHub. [[slnc 3000]]', 'github'),
    ('command', 'Close that.', 'slide 2'),
    ('pick', 'Tell me about the cold storage robot. [[slnc 3000]]', 'slide 3 (roadmap)'),
    ('command', 'Next slide.', 'slide 4'),
    ('command', 'Next slide.', 'slide 5'),
    ('command', 'Previous slide.', 'slide 4'),
    ('command', 'Go to slide 2.', 'slide 2'),
    ('pick', 'So how big is this market, really?', 'announce slide 5'),
    ('no', '[[after 1800]] No not that.', 'keep'),
]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(os.environ.get('BUNDLE', os.path.join(ROOT, 'composeApp/build/compose/binaries/main/app')), 'Moliere.app/Contents/MacOS/Moliere')
LIB = os.environ.get('LIBRARY', os.path.join(ROOT, 'library'))
T = re.compile(r'^(\d\d):(\d\d):(\d\d)\.(\d\d\d)  (.*)$')
GAP = 2.0  # hear.swift's silence after each file
WINDOW = 2.5  # Session.kt's ANNOUNCE: how long an announce can be stopped before it is shown


def words(s): return re.findall(r'[a-z0-9]+', s.lower())


def synthesize(dest):
    """The sentences as WAV files, one each but for those that start with [[after N]]: they follow the one before in its
    file, N ms after its voice ends. For each sentence its file and where in it the voice ends (the last sample above 1%
    of full scale); and each file's length."""
    os.makedirs(dest, exist_ok=True)
    for f in os.listdir(dest): os.remove(os.path.join(dest, f))
    said, files, ends = os.path.join(os.path.dirname(dest), 'said.wav'), [], []
    for _, text, _ in SENTENCES:
        after = re.match(r'\[\[after (\d+)\]\]\s*', text)
        subprocess.run(['say', '-v', os.environ.get('VOICE', 'Samantha'), '-o', said, '--file-format=WAVE', '--data-format=LEI16@22050',
                        text[after.end():] if after else text], check=True)
        with wave.open(said) as w:
            rate, raw = w.getframerate(), w.readframes(w.getnframes())
        samples = memoryview(raw).cast('h')
        last = max((k for k in range(len(samples)) if abs(samples[k]) > 328), default=0) + 1
        if after:
            start = round(ends[-1][1] * rate) + int(after[1]) * rate // 1000
            files[-1] = files[-1][:round(ends[-1][1] * rate) * 2].ljust(start * 2, b'\0') + raw
        else:
            start = 0
            files.append(raw)
        ends.append((len(files) - 1, (start + last) / rate))
    for k, raw in enumerate(files):
        with wave.open(os.path.join(dest, f'{k + 1:02d}.wav'), 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(raw)
    os.remove(said)
    return ends, [len(raw) / 2 / rate for raw in files]


def run(out, audio):
    while subprocess.run(['pgrep', '-f', 'Moliere.app/Contents/MacOS'], capture_output=True).stdout.strip(): time.sleep(5)
    log = os.path.join(out, 'out.txt')
    with open(log, 'w') as f:
        p = subprocess.Popen([APP, '--audio', audio, '--library', LIB, '--no-history'], stdout=f, stderr=subprocess.STDOUT)
    start = time.time()
    try:
        while p.poll() is None and time.time() - start < 400:
            if 'ear: heard every file' in open(log, errors='ignore').read(): time.sleep(6); break
            time.sleep(1)
    finally:
        p.terminate()
        try: p.wait(20)
        except subprocess.TimeoutExpired: p.kill()
    return log


def ms(m): return ((int(m[1]) * 60 + int(m[2])) * 60 + int(m[3])) * 1000 + int(m[4])


def parse(log):
    lines = []
    for raw in open(log, errors='ignore'):
        m = T.match(raw.rstrip('\n'))
        if m: lines.append((ms(m), m[5]))
    return lines


def target(s):
    m = re.search(r'Slide\(deck=(.*?), page=(\d+)', s)
    if m: return f'slide {m[2]}'
    m = re.search(r'Site\(url=https?://(?:www\.)?([^/,)]*)', s)
    if m: return m[1].split('.')[0]
    return 'desktop' if 'the desktop' in s else s


def measure(lines, ends, lengths):
    """Per sentence: its utterance's ear lines (up to its final), its decisions (up to the one at its end), and what
    they led to, each time in seconds from the end of the voice."""
    plays = [t for t, b in lines if re.match(r'ear: playing \d+$', b)]
    over = next((t for t, b in lines if b == 'ear: heard every file'), lines[-1][0])
    plays.append(over)
    voice = []
    for f, end in ends:  # the helper plays a little slower than real time: scale by its own pace
        pace = (plays[f + 1] - plays[f]) / 1000 / (lengths[f] + GAP) if f + 1 < len(plays) else 1.0
        voice.append(plays[f] + end * pace * 1000 if f < len(plays) else None)
    ear = []
    for t, b in lines:
        m = re.match(r'ear     (live |final|quiet)  "(.*)"  unchanged', b)
        if m: ear.append((t, m[1].strip(), m[2]))
    decides, quiet = [], set()  # the words the voice went quiet on in this utterance, which a decision "still talking" may be about
    for idx, (t, b) in enumerate(lines):
        e = re.match(r'ear     (live |final|quiet)  "(.*)"  unchanged', b)
        if e and e[1] == 'quiet': quiet.add(e[2])
        m = re.match(r'decide  (\d+) ms  "(.*)"( \(still talking\))?  ->  (.*?)(?: \((.*)\))?$', b)
        if not m: continue
        how = 'end' if not m[3] else 'voice stopped' if m[2] in quiet else 'still talking'
        if how == 'end': quiet = set()
        decides.append(dict(t=t, ms=int(m[1]), text=m[2], how=how, move=m[4], answer=m[5] or '', at=idx))
    # utterances: the ear's lines up to each final, the decisions up to each one at an end
    utts, cur = [], []
    for e in ear:
        cur.append(e)
        if e[1] == 'final': utts.append(cur); cur = []
    groups, cur = [], []
    for d in decides:
        cur.append(d)
        if d['how'] == 'end': groups.append(cur); cur = []
    rows = []
    k = 0
    for i, (kind, text, want) in enumerate(SENTENCES):
        clean = re.sub(r'\[\[.*?\]\]', '', text).strip()
        row = dict(n=i + 1, kind=kind, text=clean, want=want, v=voice[i])
        # the next utterance whose final shares most words with the sentence
        best = None
        for j in range(k, min(k + 2, len(utts))):
            fw = words(utts[j][-1][2])
            if len(set(fw) & set(words(clean))) >= max(1, len(set(words(clean))) // 2): best = j; break
        if best is None or row['v'] is None: rows.append(row); continue
        k = best + 1
        u, g = utts[best], groups[best] if best < len(groups) else []
        final = words(u[-1][2])
        row['last'] = next((t for t, kk, txt in u if words(txt) == final), u[-1][0]) - row['v']
        row['final'] = u[-1][0] - row['v']
        q = next((t for t, kk, _ in u if kk == 'quiet'), None)
        row['quiet'] = q - row['v'] if q else None
        row['decides'] = [(d['how'], d['answer'], d['ms']) for d in g if d['ms'] > 0]
        acted = [d for d in g if d['move'] != 'Nothing']
        if acted:
            d = acted[0]
            row['acted'] = d['t'] - row['v']
            row['how'] = d['how']
            said = words(d['text'])  # acted before all its words were heard: fewer, or the last one cut
            row['part'] = d['text'] if len(said) < len(final) or said and said[-1] != final[len(said) - 1] and final[len(said) - 1].startswith(said[-1]) else None
            after = lines[d['at'] + 1:d['at'] + 12]
            call = next((t for t, b in after if b.startswith('call    ') and t - d['t'] < 100), None)
            ann = next((t for t, b in after if b.startswith('mole    retrieving') and t - d['t'] < 100), None)
            stop = next((t for t, b in after if 'not shown' in b and t - d['t'] < 100), None)
            row['call'] = call - row['v'] if call else None
            row['announce'] = ann - row['v'] if ann else None
            row['stopped'] = stop - row['v'] if stop else None
            row['moves'] = [x['move'] for x in acted]
        rows.append(row)
    # where the screen is after each sentence, and whether an announce in play was shown
    for r in rows:
        nxt = next((x['v'] for x in rows if x['n'] == r['n'] + 1 and x.get('v')), over + 5000)
        shown = [(t, target(b[8:])) for t, b in lines if b.startswith('screen  ') and r.get('v') and r['v'] - 300 < t <= nxt + 200]
        r['screen'] = shown[-1][1] if shown else 'keep'
    return rows, decides


def fmt(x): return '     -' if x is None else f'{x / 1000:+6.2f}'


def report(rows, decides):
    print(f"{'':3s} {'kind':7s} {'sentence':42s} {'words':>6s} {'quiet':>6s} {'final':>6s} {'acted':>6s} {'how':14s} {'screen':>6s} {'annnc':>6s}  "
          f"{'after':12s} {'want':16s} decisions (answer ms)")
    for r in rows:
        dec = ' '.join(f"{a or '-'}{'*' if h == 'end' else '^' if h == 'voice stopped' else ''} {m}" for h, a, m in r.get('decides', []))
        how = r.get('how', '')
        print(f"{r['n']:3d} {r['kind']:7s} {r['text'][:42]:42s} {fmt(r.get('last'))} {fmt(r.get('quiet'))} {fmt(r.get('final'))} {fmt(r.get('acted'))} "
              f"{how:14s} {fmt(r.get('call'))} {fmt(r.get('announce'))}  {r['screen'][:12]:12s} {r['want']:16s} {dec}")
    print('\n(seconds after the end of the voice; decisions: ^ when the voice had stopped, * at the end of the utterance)')

    def stat(name, xs):
        xs = [x / 1000 for x in xs if x is not None]
        if xs: print(f'  {name:44s} median {statistics.median(xs):5.2f} s   worst {max(xs):5.2f} s   ({len(xs)})')
    cmds = [r for r in rows if r['kind'] == 'command']
    picks = [r for r in rows if r['kind'] == 'pick']
    print('\ncommands')
    stat('voice end to the last live word', [r.get('last') for r in cmds])
    stat('voice end to the decision that acted', [r.get('acted') for r in cmds])
    stat('voice end to the screen call', [r.get('call') for r in cmds])
    print('picks')
    stat('voice end to the last live word', [r.get('last') for r in picks])
    stat('voice end to the announce', [r.get('announce') for r in picks])
    print('a "no" inside an announce\'s window')
    for r in rows:
        if r['kind'] != 'no': continue
        prev = rows[r['n'] - 2]
        if prev.get('announce') is None or r.get('v') is None:
            print(f"  {r['text']}: no announce to stop"); continue
        voice = (r['v'] - prev['v'] - prev['announce']) / 1000  # from the announce
        if r.get('stopped') is not None:
            print(f"  {r['text']}: its voice ended {voice:.2f} s into the announce's {WINDOW} s, stopped {fmt(r['stopped']).strip()} s after it, "
                  f"{WINDOW - voice - r['stopped'] / 1000:.2f} s before the screen call")
        else:
            print(f"  {r['text']}: its voice ended {voice:.2f} s into the announce's {WINDOW} s, not stopped ({', '.join(r.get('moves', [])) or 'nothing acted'}), "
                  f"screen {r['screen']}")
    print('words that go on after a command-like start')
    for r in rows:
        if r['kind'] != 'early': continue
        part = r.get('part')
        what = f'ACTED on a part, "{part}"' if part else 'nothing acted on a part of the words'
        moves = ', then ' + ', '.join(r['moves']) if r.get('moves') else ', nothing acted'
        print(f"  {r['text']}: {what}{moves}; screen {r['screen']}")
    model = [d['ms'] for d in decides if d['ms'] > 0]
    if model: print(f"decisions that called the model: {len(model)}, median {statistics.median(model)} ms, worst {max(model)} ms")


if __name__ == '__main__':
    out = os.path.abspath(sys.argv[1])
    if len(sys.argv) > 2:
        log = sys.argv[2]
        ends = synthesize(os.path.join(out, 'in'))
    else:
        if not os.access(APP, os.X_OK) or not os.path.isdir(LIB): sys.exit(f'no app at {APP} or no decks in {LIB}')
        ends = synthesize(os.path.join(out, 'in'))
        log = run(out, os.path.join(out, 'in'))
    report(*measure(parse(log), *ends))

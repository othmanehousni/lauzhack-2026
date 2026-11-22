"""replay.py: what an ear heard, decided again by the packaged app (--replay: no VM, Chrome, phone or window, and no
file or browser page of this Mac in the catalog, so it runs beside a running app), one replay at a time.

  replay.py run <session log> <models dir> <out dir> [--at HH:MM:SS] [--gap S] [--stretch F] [--finals]
      the ear's pieces (live, quiet, final) of the meeting at or after --at, in the log's order and rhythm, a silence
      longer than --gap seconds (5) cut to it; the app's log goes to <out dir>/out.txt. On a loaded Mac, where a
      decision takes longer than in the session (score prints both), --stretch slows the replay and the wait before
      showing by F (at most 1.6: past 4 s, ModelAgent takes an announce for stopped). --finals: only each utterance's
      end reaches the app, as when nothing is decided before the sentence is whole
  replay.py run <scenario.txt> <models dir> <out dir>
      a scenario of run.sh's, each line spoken as --typed --paced speaks it, scored by check.py
  replay.py show <out dir or session log> [labels.tsv] [--at HH:MM:SS]
      each utterance: the screen before it, its candidates, the model's answers and what was done
  replay.py score <labels.tsv> <out dir or session log>[@HH:MM:SS]... [--rule GAP,FLOOR]
      one row per run: requests done right, missed, wrong, and acts on talk that asked for nothing; with --rule, as
      if an announce whose pick trails the best candidate by GAP or more, or scores under FLOOR, were not shown
  replay.py differ <labels.tsv> <out dir or session log>[@HH:MM:SS]...
      the utterances on which the runs did not do the same, with each run's verdict, final answer and moves
  replay.py margins [labels.tsv] <run>... [labels.tsv] <run>...
      each announce: its pick's score against the best candidate's, wanted or not (by the labels before a replay's
      run, by the expectation of a run.sh run), and what a rule on those scores keeps

BUNDLE names the folder holding Moliere.app (default the build's), LIBRARY the decks (default the repo's library/). In
a copy of the bundle, Contents/MacOS/Moliere-replay and Contents/app/Moliere-replay.cfg, copies of Moliere and its .cfg,
run it under a name that run.sh and the latency lever do not wait for.
Labels, one utterance a line: n <tab> want <tab> sure (y or ?) <tab> its words. want: nothing, next, previous,
slide N, slides, close, undo, site|app|file <name part>, search <words>, click <words>, or several joined by " / ".
"""
import fcntl, json, os, re, signal, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
T = re.compile(r'^(\d\d):(\d\d):(\d\d)\.(\d\d\d)  (.*)$')
EAR = re.compile(r'ear     (live |quiet|final)  "(.*)"  unchanged (\d+) ms$')
DECIDE = re.compile(r'decide  \d+ ms  "(.*)"( \(still talking\))?  ->  (.*?)(?: \((.*)\))?$')
CANDS = re.compile(r'candidates  "(.*)"( \(still talking\))?  (.*)$')


def secs(m): return (int(m[1]) * 60 + int(m[2])) * 60 + int(m[3]) + int(m[4]) / 1000


def meeting(path, at=None):
    """The timestamped lines of the meeting that starts at or after [at], up to its end."""
    out, on = [], False
    for raw in open(path, errors='ignore'):
        m = T.match(raw.rstrip('\n'))
        if not m: continue
        if m[5].startswith('meeting "'):
            if on: break
            on = at is None or m[0][:8] >= at
            continue
        if on and m[5] == 'meeting over': break
        if on: out.append((secs(m), m[5]))
    return out


def pieces(path, at=None):
    return [(t, e[1].strip(), e[2], int(e[3])) for t, b in meeting(path, at) if (e := EAR.match(b))]


def app(models, out, window=2500):
    bundle = os.environ.get('BUNDLE', os.path.join(ROOT, 'composeApp/build/compose/binaries/main/app'))
    exe = os.path.join(bundle, 'Moliere.app/Contents/MacOS/Moliere')
    if os.path.exists(exe + '-replay'): exe += '-replay'  # a launcher and .cfg copied under another name: no run.sh waits for it
    lib = os.environ.get('LIBRARY', os.path.join(ROOT, 'library'))
    log = open(out, 'a')
    p = subprocess.Popen([exe, '--replay', '--library', lib, '--models', os.path.abspath(models), '--no-history', '--window', str(window)],
                         stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True, bufsize=1)
    for _ in range(240):
        if 'mole    hello' in open(out, errors='ignore').read() or p.poll() is not None: break
        time.sleep(1)
    if p.poll() is not None or 'mole    hello' not in open(out, errors='ignore').read():
        p.kill(); sys.exit(f'the app did not start a meeting, see {out}')
    time.sleep(2)
    return p


def run(source, models, outdir, at=None, gap=5.0, stretch=1.0, finals=False):
    os.makedirs(outdir, exist_ok=True)
    out = os.path.join(outdir, 'out.txt')
    open(out, 'w').close()
    lock = open('/tmp/moliere-replay.lock', 'w')
    fcntl.flock(lock, fcntl.LOCK_EX)  # one replay at a time: each holds about 1.2 GB
    scenario = not T.match(open(source, errors='ignore').readline())
    if scenario:
        decks = sorted(f[:-4] for f in os.listdir(os.environ.get('LIBRARY', os.path.join(ROOT, 'library'))) if f.endswith('.pdf'))
        open(os.path.join(outdir, 'public.txt'), 'w').write('\n'.join(decks) + '\n')
    p = app(models, out, round(2500 * stretch))

    def say(kind, text, ago=0.0, quiet=False):  # a line of hear.swift's
        p.stdin.write(json.dumps(dict(text=text, kind=kind, ago=ago, quiet=quiet)) + '\n')
        p.stdin.flush()
    try:
        if scenario:
            note, pause = open(out, 'a', buffering=1), 6.0
            for line in open(source):
                line = line.strip()
                if not line: continue
                if line.startswith('#'): note.write(f'##{line}\n'); continue
                if line.startswith('@'): pause = float(line[1:]); continue
                note.write(f'>>> {line}\n')
                words = line.split(' || ')[0].split()
                for k in range(1, len(words) + 1):  # as Ear.kt's typed(paced = true)
                    time.sleep(0.4); say('live', ' '.join(words[:k]))
                time.sleep(0.5); say('live', ' '.join(words), 0.5, True)
                time.sleep(0.2); say('final', ' '.join(words))
                time.sleep(pause)
        else:
            ps = [p for p in pieces(source, at) if not finals or p[1] == 'final']
            start, last, due = time.monotonic(), ps[0][0], 0.0
            for t, kind, text, ago in ps:
                due += min(t - last, gap) * stretch
                last = t
                time.sleep(max(0.0, start + due - time.monotonic()))
                say('final' if kind == 'final' else 'live', text, ago / 1000, kind == 'quiet')
        time.sleep(8)
    finally:
        p.send_signal(signal.SIGTERM)
        try: p.wait(20)
        except subprocess.TimeoutExpired: p.kill()


def target(s):
    """A target as the log prints it, the way labels name it: slide <deck> <page>, site <url>, app <name>, file <path>."""
    if m := re.match(r'Slide\(deck=(.*?), page=(\d+)', s): return f'slide {m[1]} {m[2]}'
    if m := re.match(r'Site\(url=(.*?), name=(.*)\)$', s): return f'site {m[1]} {m[2]}'
    if m := re.match(r'App\(name=(.*)\)$', s): return f'app {m[1]}'
    if m := re.match(r'File\(path=(.*)\)$', s): return f'file {m[1]}'
    return 'desktop' if s.startswith('the desktop') else s


def utterances(path, at=None):
    """The utterances of a run or of a session log: words, screen before, candidates and answers of each decision,
    announces, whether they were stopped, and the calls made for them."""
    us, pend, last = [], None, None  # pend: the announce in play (its utterance and target); last: the utterance last decided

    def find(text, partial):
        for u in reversed(us):
            if not u['done'] and (text in u['partials'] if partial else text == u['words']): return u
        return us[-1] if us else None

    screen, slide = 'desktop', None
    for t, b in meeting(path, at):
        if e := EAR.match(b):
            kind, text = e[1].strip(), e[2]
            if not us or us[-1]['ended']: us.append(dict(n=len(us) + 1, words='', partials=set(), ended=False, done=False, before=screen, slide=slide,
                                                         t=t, decisions=[], cands=[], picks=[], announced=[], stopped=[], calls=[]))
            u = us[-1]
            u['partials'].add(text)
            if kind == 'final': u['words'], u['ended'] = text, True
        elif c := CANDS.match(b):
            if u := find(c[1], bool(c[2])):
                u['cands'].append((not c[2], [(x.rsplit(' ', 1)[0].split(' ', 1)[1], float(x.rsplit(' ', 1)[1])) for x in c[3].split(' | ')]))
        elif d := DECIDE.match(b):
            if (u := find(d[1], bool(d[2]))) is None: continue
            u['decisions'].append((not d[2], d[3], d[4]))
            if not d[2]: u['done'] = True
            if d[3] != 'Nothing': last = u
            a = re.match(r'Ask\(options=\[(.*)\]\)$', d[3])
            if a and len(re.findall(r'(?:Slide|Site|File|App)\(', a[1])) == 1:  # one option: an announce; more are a choice
                if pend: pend[0]['stopped'].append(pend[1])
                pend = (u, target(a[1]))
                u['announced'].append(pend[1])
                k = re.match(r'show (\d+)$', d[4] or '')
                if k and u['cands']:  # the pick's score and the best one's, in the candidates of this decision
                    cs = u['cands'][-1][1]
                    u['picks'].append((pend[1], cs[int(k[1]) - 1][1], max(s for _, s in cs), int(k[1])))
        elif b.startswith('mole    back  Okay, not shown') and pend:
            pend[0]['stopped'].append(pend[1]); pend = None
        elif b.startswith('call    '):
            what = b[8:]
            what = 'show ' + target(what[5:]) if what.startswith('show ') else what
            if pend and what == 'show ' + pend[1]: pend[0]['calls'].append(what)
            else:
                if pend: pend[0]['stopped'].append(pend[1])  # any change of the screen drops the announce in play
                if last is not None: last['calls'].append(what)
            pend = None
        elif b.startswith('screen  '):
            screen = target(b[8:].split('  ')[0])
            if screen.startswith('slide '): slide = screen
    return us


def page(screen):
    m = re.match(r'slide (\S+) (\d+)$', screen)
    return (m[1], int(m[2])) if m else (None, None)


def fits(want, call, slide):
    """Whether [call] does what [want] asks, with [slide] the last slide shown before it."""
    deck, p = page(slide or '')
    k, _, arg = want.partition(' ')
    if k in ('next', 'previous', 'slide'):
        if not call.startswith('show slide ') or k != 'slide' and p is None: return False
        d, q = page(call[5:])
        n, _, part = arg.partition(' ')
        n = p + 1 if k == 'next' else p - 1 if k == 'previous' else int(n)
        return q == n and (part.lower() in d.lower() if part else deck is None or d == deck)
    if k == 'slides': return call.startswith('show slide ')
    if k in ('close', 'undo'): return call == 'close' or k == 'undo' and call.startswith('show ')
    if k == 'search': return call.startswith('show site https://www.google.com/search') and all(w in call.lower() for w in arg.lower().split())
    if k == 'click': return call == f'click:{arg}'
    if k == 'control': return call == arg
    return call.startswith(f'show {k} ') and arg.lower() in call.lower()


def verdict(u, want):
    """right, missed, wrong, acted (on talk), announced (on talk, then stopped), or ok (talk, nothing done)."""
    calls = u['calls']
    if want == 'nothing':
        return 'acted' if calls else 'announced' if u['announced'] else 'ok'
    wants = want.split(' / ')
    if any(fits(w, c, u['slide']) for w in wants for c in calls): return 'right'
    if not calls and any(w.split(' ')[0] in ('slide', 'slides', 'site', 'app', 'file') and fits(w, 'show ' + u['before'], u['slide']) for w in wants):
        return 'right'  # already on screen
    return 'wrong' if calls else 'missed'


def labels(path):
    out = {}
    for line in open(path):
        if not line.strip() or line.startswith('#'): continue
        n, want, sure, words = line.rstrip('\n').split('\t')
        out[int(n)] = (want, sure, words)
    return out


def short(s): return re.sub(r'Acme_(\w+?)_(?:2026|Overview_2026|Q3_2026_Financials|Roadmap)', lambda m: m[1], s)


def show(path, labelfile=None, at=None):
    ls = labels(labelfile) if labelfile else {}
    for u in utterances(path, at):
        want, sure, _ = ls.get(u['n'], ('', '', ''))
        final = next((c for done, c in reversed(u['cands']) if done), u['cands'][-1][1] if u['cands'] else [])
        answers = ' '.join(('' if done else '~') + (a or '-') for done, _, a in u['decisions'] if a or done)
        print(f"{u['n']:3d} {u['words'][:90]!r}  [{short(u['before'])}]")
        if final: print('      cands  ' + ' | '.join(f'{i + 1} {short(n)} {s:.3f}' for i, (n, s) in enumerate(final[:8])))
        print(f'      answers  {answers}')
        picks = {a: f' (pick {s:.3f}, best {b:.3f})' for a, s, b, _ in u['picks']}
        done = ' ; '.join([f'announce {short(a)}' + picks.get(a, '') + (' (stopped)' if a in u['stopped'] else '') for a in u['announced']] + [short(c) for c in u['calls']])
        if done: print(f'      did  {done}')
        if want: print(f"      want  {want}{' (unsure)' if sure != 'y' else ''}  ->  {verdict(u, want)}")


def resolve(arg):
    """<out dir> or <session log>, then @HH:MM:SS for the meeting at or after that time."""
    path, _, at = arg.partition('@')
    return (os.path.join(path, 'out.txt') if os.path.isdir(path) else path), at or None


def score(labelfile, runs, rule=None):
    ls = labels(labelfile)
    print(f"{'run':32s} {'right':>5s} {'missed':>6s} {'wrong':>5s} {'acted':>5s} {'announced':>9s}   of {sum(w != 'nothing' for w, _, _ in ls.values())} requests, {sum(w == 'nothing' for w, _, _ in ls.values())} talk")
    for r in runs:
        path, at = resolve(r)
        us = {u['n']: u for u in utterances(path, at)}
        for u in us.values() if rule else []:  # a pick the rule holds back is not shown (what followed it is not replayed again)
            for a, s, b, _ in u['picks']:
                if b - s >= rule[0] or s < rule[1]:
                    u['calls'], u['announced'] = [c for c in u['calls'] if c != 'show ' + a], [x for x in u['announced'] if x != a]
        bad = [n for n, (_, _, words) in ls.items() if n in us and us[n]['words'] != words]
        if bad: print(f'  {r}: utterances {bad} differ from the labels\' words')
        v = [verdict(us[n], w) for n, (w, _, _) in ls.items() if n in us]
        sure = [verdict(us[n], w) for n, (w, s, _) in ls.items() if n in us and s == 'y']
        ms = sorted(int(m[1]) for _, b in meeting(path, at) if (m := re.match(r'decide  (\d+) ms  .*\)$', b)))  # those the model answered
        print(f"{r[-32:]:32s} " + ' '.join(f'{v.count(k):>{len(k)}d}' for k in ('right', 'missed', 'wrong', 'acted', 'announced')) +
              '   sure only ' + '/'.join(str(sure.count(k)) for k in ('right', 'missed', 'wrong', 'acted', 'announced')) +
              f'   decisions {ms[len(ms) // 2] if ms else 0} ms median, {ms[len(ms) * 9 // 10] if ms else 0} ms p90' +
              (f'   ({len(ls) - len(v)} not replayed)' if len(v) < len(ls) else ''))


def differ(labelfile, runs):
    """The utterances on which the runs did not all do the same: the label, then each run's verdict and what it did."""
    ls = labels(labelfile)
    us = [{u['n']: u for u in utterances(*resolve(r))} for r in runs]
    did = lambda u: ' ; '.join([f'announce {short(a)}' + (' (stopped)' if a in u['stopped'] else '') for a in u['announced'] if a not in u['stopped'] and 'show ' + a in u['calls']] +
                              [f'announce {short(a)} (stopped)' for a in u['stopped']] + [short(c) for c in u['calls'] if c[5:] not in u['announced']]) or '-'
    for n, (want, sure, words) in ls.items():
        rows = [(verdict(u[n], want), did(u[n]), ' '.join(a or '-' for done, _, a in u[n]['decisions'] if done)) if n in u else ('-', '-', '-') for u in us]
        if len({(v, d) for v, d, _ in rows}) > 1:
            print(f"{n:3d} {words[:80]!r}  want {want}{'' if sure == 'y' else ' (unsure)'}")
            for r, (v, d, a) in zip(runs, rows): print(f"      {r[-20:]:20s} {v:9s} [{a}]  {d}")


def announces(path, ls=None, at=None):
    """Each announce of a pick by meaning in a run: (run's line, its words, the pick, its score, the best candidate's
    score, whether the line wanted it). Lines and wants come from [ls], labels of a replay, or from run.sh's ">>> " lines."""
    import check
    if ls is not None:
        for u in utterances(path, at):
            want = ls.get(u['n'], ('nothing',))[0]
            for a, s, b, _ in u['picks']:
                yield u['n'], u['words'], a, s, b, want != 'nothing' and any(fits(w, 'show ' + a, u['slide']) for w in want.split(' / '))
        return
    for n, r in enumerate(check.parse(os.path.dirname(path)), 1):
        if len(r['cands']) != len(r['moves']): continue  # each decision follows its candidates
        for mv, answer, cs in zip(r['moves'], r['answers'], r['cands']):
            k, ts = re.match(r'show (\d+)$', answer), check.targets(mv)
            if k and mv.lstrip('~').startswith('Ask(') and len(ts) == 1:
                wanted = 'announce' in r['flags'] if r['want'] == '' else r['want'] not in ('keep', 'desktop') and check.ok(r['want'], ts[0])  # "|| announce": any pick
                yield n, r['say'], ts[0], cs[int(k[1]) - 1][1], max(s for _, s in cs), wanted


def margins(args):
    """Every announce of the runs, then how many wanted and unwanted ones a rule on the scores would keep: shown only
    when the pick trails the best candidate by less than a gap, or scores at least a floor."""
    out, ls = [], None
    for arg in args:
        if arg.endswith('.tsv'): ls = labels(arg); continue
        path, at = resolve(arg)
        for n, words, a, s, b, wanted in dict.fromkeys(announces(path, ls, at)):  # a partial's and the end's announce of the same pick count once
            out.append((arg, n, words, a, s, b, wanted))
            print(f"{arg[-24:]:24s} {n:3d} {'WANTED' if wanted else 'unwanted':8s} pick {s:.3f} best {b:.3f} gap {b - s:.3f}  {short(a)[:40]:40s} {words[:60]!r}")
    for label, keep in [(f'gap < {g:g}', lambda s, b, g=g: b - s < g) for g in (0.001, 0.01, 0.02, 0.03, 0.04, 0.05)] + \
                       [(f'pick >= {f:.2f}', lambda s, b, f=f: s >= f) for f in (0.70, 0.72, 0.74, 0.76)] + \
                       [(f'gap < {g:.2f}, pick >= {f:.2f}', lambda s, b, g=g, f=f: b - s < g and s >= f) for g in (0.03, 0.04) for f in (0.70, 0.72)]:
        kw = sum(1 for *_, s, b, w in out if w and keep(s, b))
        ku = sum(1 for *_, s, b, w in out if not w and keep(s, b))
        print(f"{label:26s}  wanted kept {kw}/{sum(1 for *_, w in out if w)}   unwanted kept {ku}/{sum(1 for *_, w in out if not w)}")


if __name__ == '__main__':
    a = sys.argv[1:]
    opt = lambda k, d=None: a[a.index(k) + 1] if k in a else d
    pos = [x for i, x in enumerate(a) if not x.startswith('--') and (i == 0 or a[i - 1] not in ('--at', '--gap', '--stretch', '--rule'))]
    if pos[0] == 'run': run(pos[1], pos[2], pos[3], opt('--at'), float(opt('--gap', 5)), float(opt('--stretch', 1)), '--finals' in a)
    elif pos[0] == 'show':
        path, at = resolve(pos[1])
        show(path, pos[2] if len(pos) > 2 else None, at)
    elif pos[0] == 'score': score(pos[1], pos[2:], tuple(map(float, opt('--rule').split(','))) if opt('--rule') else None)
    elif pos[0] == 'differ': differ(pos[1], pos[2:])
    elif pos[0] == 'margins': margins(pos[1:])
    else: sys.exit(__doc__)

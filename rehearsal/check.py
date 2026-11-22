"""check.py <out dir>: one row per scenario line of run.sh's out.txt, pass or fail against its expectation, with the
decision time, the time to screen and, for a failure, its cause; then the score of each category.
Expectations: keep | desktop | slide <deck part> <page> | site <address part> | file <name part> | app <name part>,
optionally followed by flags: announce (the bar announced it first), stopped (an announce was stopped, nothing shown).
Causes: not retrieved (the wanted item was among no decision's candidates; its rank in its tier when the pool has it),
retrieved, decided otherwise (it was, the model answered something else), decided otherwise (no retrieval needed: a
command, or a line that wants nothing done), model wording (an open or search whose words were wrong), stage (the move
was right and the screen did not follow, or the line was never decided); "after N" when an earlier miss left the
screen other than the scenario expects. Names not in run.sh's public.txt are printed as private."""
import os, re, sys
T = re.compile(r'^(\d\d):(\d\d):(\d\d)\.(\d\d\d)  (.*)$')
PUBLIC = set()

def ms(m): return ((int(m[1]) * 60 + int(m[2])) * 60 + int(m[3])) * 1000 + int(m[4])
def stem(path): return os.path.splitext(os.path.basename(path))[0].lower()
def norm(target):
    m = re.match(r'Slide\(deck=(.*?), page=(\d+)', target)
    if m: return f'slide {m[1]} {m[2]}'
    m = re.match(r'Site\(url=(.*?), name=', target)
    if m: return f'site {m[1]}'
    m = re.match(r'File\(path=(.*?)[,)]', target)
    if m: return f'file {m[1]}'
    m = re.match(r'App\(name=(.*?)[,)]', target)
    if m: return f'app {m[1]}'
    return 'desktop' if target.startswith('the desktop') else target
def ok(want, got):
    if want == 'desktop': return got == 'desktop'
    k, *rest = want.split(' ')
    if not got.startswith(k + ' '): return False
    if k == 'slide': return rest[0].lower() in got.lower() and got.endswith(' ' + rest[1])
    return ' '.join(rest).lower() in got.lower()
def private(s):
    decks = {p.replace('_', ' ') for p in PUBLIC}
    s = re.sub(r'/Users/[^,)\]]*', lambda m: m[0] if stem(m[0]) in PUBLIC else 'a private file', s)
    s = re.sub(r'(deck=|^slide )(.*?)(, page=| \d+$)', lambda m: m[0] if m[2].lower().replace('_', ' ') in decks else m[1] + 'a private deck' + m[3], s)
    return re.sub(r'^file (?!/)(.*)$', lambda m: m[0] if stem(m[1]) in PUBLIC else 'file a private file', s)
def entries(text, numbered):
    out = []
    for e in text.split(' | '):
        w = e.split(' ')
        if numbered: w = w[1:]
        if len(w) >= 3: out.append((f'{w[0]} {" ".join(w[1:-1])}', float(w[-1])))
    return out
def targets(move): return [norm(move[m.start():]) for m in re.finditer(r'(?:Slide|Site|File|App)\(', move)]
def seen(want, screen): return screen == 'desktop' if want == 'desktop' else ok(want, screen)

def parse(out):
    """The rows of [out]/out.txt, each with what was decided, offered and shown, pass or fail, and whether the line
    picks something the screen is not in front of (a pick, for which retrieval matters)."""
    PUBLIC.clear()
    if os.path.exists(os.path.join(out, 'public.txt')): PUBLIC.update(l.strip().lower() for l in open(os.path.join(out, 'public.txt')))
    rows, cur, screen, cat = [], None, 'desktop', ''
    for raw in open(os.path.join(out, 'out.txt'), errors='ignore'):
        raw = raw.rstrip('\n')
        if raw.startswith('### '): cat = raw[4:].strip(); continue
        if raw.startswith('>>> '):
            say, _, exp = raw[4:].partition(' || ')
            words = exp.split()
            cur = dict(say=say, exp=exp.strip(), cat=cat, before=screen, t0=None, decide=[], moves=[], answers=[], screens=[], tscreen=None,
                       announce=False, stopped=False, cands=[], pool={}, flags=[w for w in words if w in ('announce', 'stopped')],
                       want=' '.join(w for w in words if w not in ('announce', 'stopped')))
            rows.append(cur); continue
        m = T.match(raw)
        if not m: continue
        body = m[5]
        if body.startswith('screen  '):
            screen = norm(body[8:].strip())
            if cur is not None:
                cur['screens'].append(screen)
                if cur['tscreen'] is None: cur['tscreen'] = ms(m)
            continue
        if cur is None: continue
        if cur['t0'] is None: cur['t0'] = ms(m)
        d = re.match(r'decide  (\d+) ms  ".*"( \(still talking\))?  ->  (.*?)(?: \((.*)\))?$', body)
        if d: cur['decide'].append(int(d[1])); cur['moves'].append(('~' if d[2] else '') + d[3]); cur['answers'].append(d[4] or '')
        c = re.match(r'candidates  ".*"(?: \(still talking\))?  (.*)$', body)
        if c: cur['cands'].append(entries(c[1], True))
        p = re.match(r'pool    (\w+)  (.*)$', body)
        if p: cur['pool'][p[1]] = entries(p[2], False)
        if body.startswith('mole    retrieving'): cur['announce'] = True
        if 'not shown' in body: cur['stopped'] = True

    front, deck, last_slide, broke, broke_before = 'desktop', None, None, None, None
    for i, r in enumerate(rows, 1):
        want, flags = r['want'], r['flags']
        r['after'] = r['screens'][-1] if r['screens'] else r['before']
        good = None
        if want:
            good = (r['after'] == r['before'] and not r['screens']) if want == 'keep' else ok(want, r['after'])
            if 'announce' in flags: good = good and r['announce']
            if 'stopped' in flags: good = good and r['stopped']
        elif 'announce' in flags: good = r['announce']
        r['good'] = good
        if seen(front, r['before']) and (deck is None or last_slide is not None and ok(deck, last_slide)): broke = None
        r['pick'] = (want.split(' ')[0] in ('site', 'file', 'app') and want != front or
                     want.startswith('slide ') and (deck is None or want.split(' ')[1] != deck.split(' ')[1]) or 'announce' in flags and bool(want))
        place = r['before'].startswith('slide ') == front.startswith('slide ') and (not front.startswith('slide ') or front.split(' ')[1].lower() in r['before'].lower())
        cascaded = broke is not None and not (want in ('keep', '') and not flags or r['pick'] and ('announce' not in flags or not want.startswith('slide ') or place))
        undo = any(mv == 'Undo' for mv in r['moves'])
        r['why'] = '' if good is not False else cause(r, broke if cascaded else broke_before if undo else None, last_slide)
        broke_before = broke
        if good is False and broke is None: broke = i
        if want and want != 'keep':
            front = want
            if want.startswith('slide '): deck = want
        for x in [r['before']] + r['screens']:
            if x.startswith('slide '): last_slide = x
    return rows

def cause(r, broke, last_slide):
    """Why a line failed; [broke] is the earlier line whose miss left the screen other than this one expects."""
    want, flags = r['want'], r['flags']
    if not r['decide']: return 'stage (never decided)'
    if broke is not None and not want.startswith('site '): return f'after {broke}'
    after = f'after {broke}, ' if broke is not None else ''
    if want == 'keep': return 'decided otherwise (acted on it)'
    if 'stopped' in flags: return 'stage' if r['announce'] else 'decided otherwise (nothing announced to stop)'
    moved = any(ok(want, t) for mv in r['moves'] for t in targets(mv))
    if moved and 'announce' in flags and not r['announce']: return after + 'decided otherwise (shown without announce)'
    if moved: return after + 'stage'
    if want.startswith('slide ') and not r['screens'] and any('Control(name=close)' in mv or mv == 'Undo' for mv in r['moves']): return 'stage (no change)'
    if want.startswith('site ') and any(a.startswith(('open ', 'search ')) for a in r['answers']): return after + 'model wording'
    if not r['cands']: return after + 'unknown (no candidates in the log)'
    if any(ok(want, n) for cs in r['cands'] for n, _ in cs): return after + 'retrieved, decided otherwise'
    if not r['pick']: return after + 'decided otherwise (a command)'
    for tier, es in r['pool'].items():
        for k, (n, s) in enumerate(es, 1):
            if ok(want, n): return after + f'not retrieved ({tier} rank {k}, {s:.2f})'
    return after + 'not retrieved'

if __name__ == '__main__':
    rows = parse(sys.argv[1])
    by_cat = {}
    for i, r in enumerate(rows, 1):
        if r['good'] is not None: by_cat.setdefault(r['cat'], [0, 0])[0 if r['good'] else 1] += 1
        mark = {True: 'ok  ', False: 'FAIL', None: '    '}[r['good']]
        lat = f"{r['tscreen'] - r['t0']:5d}" if r['tscreen'] and r['t0'] else '    -'
        dec = f"{max(r['decide']):4d}" if r['decide'] else '   -'
        got = private(r['after']) if r['after'] != r['before'] or r['screens'] else 'keep'
        print(f"{i:3d} {mark} dec {dec} ms  scr {lat} ms  {'A' if r['announce'] else ' '}{'S' if r['stopped'] else ' '}  {r['say'][:58]:58s} "
              f"want {r['exp'] or '-':34s} got {got[:44]:44s} [{private('; '.join(r['moves']))[:70]}]{'  <- ' + r['why'] if r['why'] else ''}")
    bad = sum(r['good'] is False for r in rows)
    checked = sum(1 for r in rows if r['exp'])
    for c, (g, b) in by_cat.items(): print(f"  {g}/{g + b}  {c}")
    print(f"{checked - bad}/{checked} as expected, {bad} failed, {len(rows)} lines")

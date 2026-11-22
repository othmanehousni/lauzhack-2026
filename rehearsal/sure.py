"""sure.py <out dir>... [labels.tsv <session log or replay out dir>...]: how sure each pick of the logged runs was when
the app decided it, and whether it was right, to choose the thresholds of doubt() in ModelAgent.kt. A pick is the first
suggestion (an Ask of one item) of a scored line, or of a labeled utterance of a session (replay.py's labels); it is
right when it is the line's expectation, or what the label wants. Its measure, from the candidates the app logged for
that decision: its lead, its score minus the best score of the other candidates, and its score itself (unrelated items
score up to about 0.73). An address or a search has no score. Its kind: show (a candidate, by meaning), name (an app or
bookmark the answer named exactly, Catalog.exactly), nearly (named by the letters of a misheard one, Catalog.near),
address or search. For thresholds of both, the right and the wrong picks at or above them; then the waits of the rule
before and after, at WINDOW ms (2500 by default). Before: at once when the pick leads by SURE and scores MATCH, half the
window (at least FLOOR) when it is the best candidate and scores MATCH, the whole window otherwise. After: a candidate
picked by meaning that scores under BACKED is not shown (hidden); at once when it leads by SURE and scores MATCH, or is
named exactly and scores MATCH; half the window when it is within CLOSE of the best candidate and scores LIKELY; the
whole window otherwise, and for an address or a search. Then each `next` that acted: the rank of the slide it went to
among the candidates, to tell a command from talk."""
import os
import re
import sys
from check import parse, ok, private, targets
import replay

SURE, MATCH, CLOSE, LIKELY, BACKED, FLOOR = 0.04, 0.75, 0.03, 0.72, 0.70, 1000  # ModelAgent.kt, Session.kt
WINDOW = int(os.environ.get('WINDOW', 2500))
WAITS = ('at once', 'short', 'whole', 'hidden')


def same(label, target):
    """Whether a candidate label of the log is the target of a move, both as check.py normalizes them."""
    kind, name = label.split(' ', 1)
    if not target.startswith(kind + ' '): return False
    t = target[len(kind) + 1:]
    if kind == 'site': return t.split('://')[-1].removeprefix('www.').rstrip('/') == name
    return t.endswith(name) if kind == 'file' else t == name


def spelled(name): return ' '.join(re.sub(r"[^a-z0-9 .\-']", ' ', name.lower()).split())  # Catalog.kt


def kind(answer, move):
    """How [answer] picked the one target of [move] (a log's Ask)."""
    if answer.startswith('search '): return 'search'
    if not answer.startswith('open '): return 'show'
    arg, url, name = answer[5:], re.search(r'url=(.*?), name=', move), re.search(r'name=(.*?)\)', move)
    if url and url[1] == 'https://' + arg.replace(' ', '') and name[1] == arg: return 'address'
    if name and spelled(name[1]) == arg or url and re.sub(r'^https?://(www\.)?|/$', '', url[1]) == arg: return 'name'
    return 'nearly'


def measure(r, k):
    """The pick of decision [k] of row [r]: (its score, its lead), or None when nothing logged scores it."""
    cs = r['cands'][r['of'][k]] if r['of'][k] >= 0 else []
    a = r['answers'][k]
    i = int(a.split()[1]) - 1 if a.startswith('show ') else next((j for j, (n, _) in enumerate(cs) if same(n, r['asked'])), None)
    if i is not None and i < len(cs):
        s, others = cs[i][1], [v for j, (_, v) in enumerate(cs) if j != i]
    else:
        s = next((v for es in r['pool'].values() for n, v in es if same(n, r['asked'])), None)
        others = [v for _, v in cs]
    return (s, s - max(others)) if s is not None and others else None


def session(labelfile, arg):
    """The picks of a labeled session log or replay, with the measure and the kind of the run.sh runs' picks."""
    ls = replay.labels(labelfile)
    for u in replay.utterances(*replay.resolve(arg)):
        if u['n'] not in ls or not u['asks']: continue
        want, a, (answer, move) = ls[u['n']][0], u['announced'][0], u['asks'][0]
        # ponytail: only a show's score is in replay.py's picks; an open by name of a session waits as unscored
        m = next(((s, lead) for t, s, _, lead in u['picks'] if t == a), None) if answer.startswith('show ') else None
        yield dict(say=u['words'], asked=a, kind=kind(answer, move), s=m[0] if m else None, lead=m[1] if m else None,
                   right=want != 'nothing' and any(replay.fits(w, 'show ' + a, u['slide']) for w in want.split(' / ')))


picks, nexts, labelfile = [], [], None
for out in sys.argv[1:]:
    if out.endswith('.tsv'): labelfile = out; continue
    if labelfile: picks += session(labelfile, out); continue
    for r in parse(out):
        if not r['want']: continue
        right = r['want'] != 'keep'
        for k, mv in enumerate(r['moves']):
            if mv.lstrip('~').startswith('Ask(') and r['asked'] is not None:
                m = measure(r, k)
                picks.append(dict(say=r['say'], asked=r['asked'], kind=kind(r['answers'][k], mv), right=right and ok(r['want'], r['asked']),
                                  s=m[0] if m else None, lead=m[1] if m else None))
                break
        for k, (mv, a) in enumerate(zip(r['moves'], r['answers'])):
            if a == 'next' and mv.lstrip('~').startswith('Show('):
                t = targets(mv)[0]
                cs = r['cands'][r['of'][k]] if r['of'][k] >= 0 else []
                rank = next((j + 1 for j, (n, _) in enumerate(cs) if n == t), None)
                nexts.append(dict(r=r, right=right and ok(r['want'], t), rank=rank, early=mv.startswith('~')))

R = [p for p in picks if p['right']]
W = [p for p in picks if not p['right']]
print(f'{len(picks)} picks: {len(R)} right, {len(W)} wrong; right of each kind: ' +
      ', '.join(f"{k} {sum(p['right'] for p in ks)}/{len(ks)}" for k in ('show', 'name', 'nearly', 'address', 'search') if (ks := [p for p in picks if p['kind'] == k])))
print('right and wrong picks that lead the others by at least L and score at least M (no score: below all)')
leads, floors = (-.03, 0, .01, .02, .03, .04, .05), (0, .70, .72, .74, .75, .76)
print('  lead L   ' + ''.join(f'M {m:<12}' for m in floors))
for l in leads:
    def at(ps, m): return sum(p['lead'] is not None and p['lead'] >= l and p['s'] >= m for p in ps)
    print(f'  {l:<8} ' + ''.join(f'{at(R, m):2d}/{len(R)} {at(W, m):2d}/{len(W)}    ' for m in floors))


def before(p):
    if p['s'] is None or p['s'] < MATCH or p['lead'] <= 0: return 'whole'
    return 'at once' if p['lead'] >= SURE else 'short'


def after(p):
    s, lead = p['s'], p['lead']
    if s is None: return 'whole'
    if p['kind'] == 'show' and s < BACKED: return 'hidden'
    if s >= MATCH and (lead >= SURE or p['kind'] == 'name'): return 'at once'
    return 'short' if s >= LIKELY and lead > -CLOSE else 'whole'


def wait(w): return {'at once': 0, 'short': min(WINDOW, max(FLOOR, WINDOW / 2)), 'whole': WINDOW}.get(w)


print(f'\nthe rule before and after, the longest wait {WINDOW} ms, the short one {wait("short"):.0f} ms:')
print(f"  {'':8s} {f'{len(R)} right:':12s}" + ''.join(f'{w:>8s}' for w in WAITS) + f"   {f'{len(W)} wrong:':12s}" + ''.join(f'{w:>8s}' for w in WAITS) + '   average wait before a right one shown')
for name, rule in (('before', before), ('after', after)):
    shown = [wait(rule(p)) for p in R if rule(p) != 'hidden']
    print(f'  {name:8s} {"":12s}' + ''.join(f'{sum(rule(p) == w for p in R):8d}' for w in WAITS) + f'   {"":12s}' +
          ''.join(f'{sum(rule(p) == w for p in W):8d}' for w in WAITS) + f'   {sum(shown) / max(len(shown), 1):.0f} ms')
for p in sorted(picks, key=lambda p: -(p['lead'] if p['lead'] is not None else -1)):
    print(f"  {'right' if p['right'] else 'WRONG':5s} {p['kind']:7s} lead {p['lead'] if p['lead'] is None else round(p['lead'], 3)!s:>6} score {p['s'] if p['s'] is None else round(p['s'], 3)!s:>5}  "
          f"{before(p):>7s} -> {after(p):7s}  {p['say'][:50]:50s} {os.path.basename(private(p['asked']))[:40]}")

print(f'\n{len(nexts)} nexts that acted; the rank of the slide they went to among the candidates (- for none):')
for p in nexts:
    print(f"  {'right' if p['right'] else 'WRONG':5s} rank {p['rank'] or '-'!s:2s} {'early' if p['early'] else 'at the end':10s} {p['r']['say'][:60]}")

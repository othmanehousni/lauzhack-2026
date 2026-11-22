"""sure.py <out dir>...: how sure each pick of the logged runs was when the app decided it, and whether it was right, to
choose the thresholds of doubt() in ModelAgent.kt. A pick is the first suggestion (an Ask of one item) of a scored line;
it is right when it is the line's expectation. Its measure, from the candidates the app logged for that decision: its
lead, its score minus the best score of the other candidates, and its score itself (unrelated items score up to about
0.73). An address or a search has no score. For thresholds of both, the right and the wrong picks at or above them; then
the waits of the rule the app keeps, at WINDOW ms (2500 by default): at once when the pick leads by SURE and scores
MATCH, half the window (at least FLOOR) when it is the best candidate and scores MATCH, the whole window otherwise.
Then each `next` that acted: the rank of the slide it went to among the candidates, to tell a command from talk."""
import os
import sys
from check import parse, ok, private, targets

SURE, MATCH, FLOOR = 0.04, 0.75, 1000  # ModelAgent.kt, Session.kt
WINDOW = int(os.environ.get('WINDOW', 2500))


def same(label, target):
    """Whether a candidate label of the log is the target of a move, both as check.py normalizes them."""
    kind, name = label.split(' ', 1)
    if not target.startswith(kind + ' '): return False
    t = target[len(kind) + 1:]
    if kind == 'site': return t.split('://')[-1].removeprefix('www.').rstrip('/') == name
    return t.endswith(name) if kind == 'file' else t == name


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


picks, nexts = [], []
for out in sys.argv[1:]:
    for r in parse(out):
        if not r['want']: continue
        right = r['want'] != 'keep'
        for k, mv in enumerate(r['moves']):
            if mv.lstrip('~').startswith('Ask(') and r['asked'] is not None:
                m = measure(r, k)
                picks.append(dict(r=r, right=right and ok(r['want'], r['asked']), s=m[0] if m else None, lead=m[1] if m else None))
                break
        for k, (mv, a) in enumerate(zip(r['moves'], r['answers'])):
            if a == 'next' and mv.lstrip('~').startswith('Show('):
                t = targets(mv)[0]
                cs = r['cands'][r['of'][k]] if r['of'][k] >= 0 else []
                rank = next((j + 1 for j, (n, _) in enumerate(cs) if n == t), None)
                nexts.append(dict(r=r, right=right and ok(r['want'], t), rank=rank, early=mv.startswith('~')))

R = [p for p in picks if p['right']]
W = [p for p in picks if not p['right']]
print(f'{len(picks)} picks: {len(R)} right, {len(W)} wrong')
print('right and wrong picks that lead the others by at least L and score at least M (no score: below all)')
leads, floors = (0, .01, .02, .03, .04, .05), (0, .74, .75, .76)
print('  lead L   ' + ''.join(f'M {m:<12}' for m in floors))
for l in leads:
    def at(ps, m): return sum(p['lead'] is not None and p['lead'] >= l and p['s'] >= m for p in ps)
    print(f'  {l:<8} ' + ''.join(f'{at(R, m):2d}/{len(R)} {at(W, m):2d}/{len(W)}    ' for m in floors))


def doubt(p):
    if p['s'] is None or p['s'] < MATCH or p['lead'] <= 0: return 1.0
    return 0.0 if p['lead'] >= SURE else 0.5


def wait(d): return 0 if d <= 0 else min(WINDOW, max(FLOOR, WINDOW * d))


print(f'\nthe rule (lead {SURE}, score {MATCH}), the longest wait {WINDOW} ms:')
for d, what in ((0.0, 'at once'), (0.5, f'{wait(.5):.0f} ms'), (1.0, f'{wait(1):.0f} ms')):
    print(f'  {what:8s} right {sum(doubt(p) == d for p in R):2d}/{len(R)}  wrong {sum(doubt(p) == d for p in W):2d}/{len(W)}')
print(f'  average wait before a right pick {sum(wait(doubt(p)) for p in R) / max(len(R), 1):.0f} ms; {WINDOW} ms when every pick waited the longest')
for p in sorted(picks, key=lambda p: -(p['lead'] if p['lead'] is not None else -1)):
    r = p['r']
    print(f"  {'right' if p['right'] else 'WRONG':5s} lead {p['lead'] if p['lead'] is None else round(p['lead'], 3)!s:>6} score {p['s']!s:>5}  {wait(doubt(p)):4.0f} ms  "
          f"{r['say'][:50]:50s} {os.path.basename(private(r['asked']))[:40]}")

print(f'\n{len(nexts)} nexts that acted; the rank of the slide they went to among the candidates (- for none):')
for p in nexts:
    print(f"  {'right' if p['right'] else 'WRONG':5s} rank {p['rank'] or '-'!s:2s} {'early' if p['early'] else 'at the end':10s} {p['r']['say'][:60]}")

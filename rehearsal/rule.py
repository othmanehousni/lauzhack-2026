"""rule.py <out dir>...: candidate rules replayed on the pools the app logged at the end of each line (the best 8 of
each tier by cosine), to choose those of tune/CONTRACT.md, Candidates at real scale. A rule keeps `reserve` places for
the meeting's slides, offers bookmarks and apps at any score, and offers what the presenter saw lately (recent files,
the first slides of recent decks, and the other pages and older files when those are in) only when it scores `margin`
above the eighth best of them. For each rule: picks whose wanted item is among the 6 candidates, and lines that want
nothing done that were offered something seen lately (seen). Then each pick's rank, and each tier's lead over its
eighth best on every line, which the margin is set above."""
import sys
from itertools import product
from check import parse, ok

OWN = ('recent', 'firsts', 'files', 'decks')

def candidates(pool, reserve, margin, files, pages, k=6):
    own = sorted(pool.get('meeting', []), key=lambda e: -e[1])
    seen = sorted(((n, s, t) for t in OWN if (files or t != 'files') and (pages or t != 'decks') for n, s in pool.get(t, [])), key=lambda e: -e[1])
    bar = seen[min(7, len(seen) - 1)][1] + margin if seen and margin is not None else -1
    rest = [(n, s, t) for t in ('sites', 'apps') for n, s in pool.get(t, [])] + [e for e in seen if e[1] >= bar]
    picked = [(n, s, 'meeting') for n, s in own[:reserve]]
    for e in sorted([(n, s, 'meeting') for n, s in own[reserve:]] + rest, key=lambda e: -e[1]):
        if len(picked) >= k: break
        picked.append(e)
    return picked

rows = [r for out in sys.argv[1:] for r in parse(out) if r['pool']]
picks = [r for r in rows if r['pick'] and r['want'] and r['want'] != 'keep']
keeps = [r for r in rows if r['want'] == 'keep']
results = []
for reserve, margin, files, pages in product(range(0, 7), [None] + [x / 100 for x in range(0, 13)], (True, False), (True, False)):
    hit = sum(any(ok(r['want'], n) for n, _, _ in candidates(r['pool'], reserve, margin, files, pages)) for r in picks)
    seen = sum(any(t in OWN for _, _, t in candidates(r['pool'], reserve, margin, files, pages)) for r in rows if not any(ok(r['want'], n) for t in OWN for n, _ in r['pool'].get(t, [])))
    results.append((hit, -seen, reserve, margin if margin is not None else -1, files, pages))
print(f'{len(picks)} picks, {len(rows)} lines')
for hit, seen, reserve, margin, files, pages in sorted(results, reverse=True)[:30]:
    print(f'reserve {reserve} margin {margin:5.2f} older files {files!s:5} recent deck pages {pages!s:5}: picks {hit}/{len(picks)}  seen lately offered without being wanted {-seen}/{len(rows)}')
print()
for r in picks:
    where = [(t, k, s) for t, es in r['pool'].items() for k, (n, s) in enumerate(es, 1) if ok(r['want'], n)]
    print(f"pick  {r['say'][:50]:50s} {r['want']:28s} {where[0] if where else 'not in the pool'}")
print()
for r in rows:
    lead = {t: es[0][1] - es[min(7, len(es) - 1)][1] for t, es in r['pool'].items() if len(es) > 1}
    seen = sorted((s for t in ('recent', 'firsts') for _, s in r['pool'].get(t, [])), reverse=True)
    print(f"{r['say'][:44]:44s} {r['want'][:24]:24s} seen lately {seen[0] - seen[min(7, len(seen) - 1)]:.3f}  " +
          ' '.join(f'{t} {v:.3f}' for t, v in lead.items()))

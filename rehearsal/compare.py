"""compare.py <before dir> <after dir>: one row per scenario line with what each run showed and why it failed, private
names removed as check.py removes them; then the score of each on the whole scenario and without the lines seen recently."""
import sys
from check import parse, private


def got(r):
    return private(r['after']) if r['after'] != r['before'] or r['screens'] else 'keep'


def cell(r):
    dec = max(r['decide']) if r['decide'] else '-'
    scr = r['tscreen'] - r['t0'] if r['tscreen'] and r['t0'] else '-'
    mark = {True: 'ok', False: 'FAIL', None: ''}[r['good']]
    return f"{mark} {got(r)[:34]}{' (announced)' if r['announce'] else ''}, {dec} ms, {scr} ms{', ' + r['why'] if r['why'] else ''}"


runs = [parse(d) for d in sys.argv[1:3]]
for i, (b, a) in enumerate(zip(*runs), 1):
    print(f"{i} | {b['say'][:48]} | {b['exp'] or '-'} | {cell(b)} | {cell(a)}")
for d, rows in zip(sys.argv[1:3], runs):
    early = [r for r in rows if r['cat'] != 'seen recently']
    score = lambda rs: f"{sum(r['good'] is True for r in rs)}/{sum(1 for r in rs if r['exp'])}"
    print(f"{d}: {score(rows)}, {score(early)} without the lines seen recently")

"""The documents a founder has open beside the Acme decks of make_decks.py, as sheets the shared screen shows in a tab:
python3 -I fixtures/make_documents.py OUT_DIR. One HTML page each, a table and a chart, the decks' numbers."""
import re
import sys
from html import escape
from pathlib import Path

ACCENT, INK, MUTED, LINE, HEAD = "#1f5bd8", "#16181d", "#6b7280", "#d9dde3", "#f3f5f8"


def money(v, unit=""): return f"${v:,.1f}{unit}" if isinstance(v, float) else f"${v:,}{unit}"


def pct(v): return f"{v:.0%}"


def table(head, rows, first=1, bold=("Total",)):
    """A sheet: column letters, row numbers from `first`, a header row, then the rows, those named in `bold` as totals."""
    right = [' class="r"' if all(re.match(r"[$(+-]?\d|List$|Quoted$|$", str(r[j])) for r in rows) else "" for j in range(len(head))]
    letters = "".join(f"<th>{chr(65 + i)}</th>" for i in range(len(head)))
    out = [f'<table><tr class="letters"><th></th>{letters}</tr>',
           f'<tr class="head"><th class="n">{first}</th>' + "".join(f"<th{a}>{escape(h)}</th>" for a, h in zip(right, head)) + "</tr>"]
    for i, r in enumerate(rows, first + 1):
        cls = ' class="total"' if r[0] in bold else ""
        out.append(f'<tr{cls}><th class="n">{i}</th>' + "".join(f"<td{a}>{escape(str(c))}</td>" for a, c in zip(right, r)) + "</tr>")
    return "\n".join(out + ["</table>"])


def bars(title, labels, values, shown, faint=()):
    """A bar chart in the accent colour; the bars named in `faint` lighter (a forecast)."""
    w, h, top, base = 600, 420, 40, 360
    step = w / len(values)
    hi = max(values) * 1.12
    out = [f'<figure><figcaption>{escape(title)}</figcaption><svg viewBox="0 0 {w} {h}" role="img" aria-label="{escape(title)}">',
           f'<line x1="0" y1="{base}" x2="{w}" y2="{base}" stroke="{LINE}" stroke-width="2"/>']
    for i, (label, v, s) in enumerate(zip(labels, values, shown)):
        bh = (base - top) * v / hi
        x = i * step + step * 0.18
        fill = ACCENT + ("55" if label in faint else "")
        out += [f'<rect x="{x:.1f}" y="{base - bh:.1f}" width="{step * 0.64:.1f}" height="{bh:.1f}" rx="3" fill="{fill}"/>',
                f'<text x="{x + step * 0.32:.1f}" y="{base - bh - 10:.1f}" class="v">{escape(s)}</text>',
                f'<text x="{x + step * 0.32:.1f}" y="{base + 36}" class="l">{escape(label)}</text>']
    return "\n".join(out + ["</svg></figure>"])


def hbars(title, labels, values, shown):
    """Horizontal bars, for labels too long to sit under a bar."""
    w, row, left = 700, 60, 300
    h = row * len(values) + 20
    out = [f'<figure><figcaption>{escape(title)}</figcaption><svg viewBox="0 0 {w} {h}" role="img" aria-label="{escape(title)}">']
    for i, (label, v, s) in enumerate(zip(labels, values, shown)):
        y = 10 + i * row
        bw = (w - left - 70) * v / max(values)
        out += [f'<text x="{left - 14}" y="{y + 30}" class="l end">{escape(label)}</text>',
                f'<rect x="{left}" y="{y + 8}" width="{bw:.1f}" height="{row - 22}" rx="3" fill="{ACCENT}"/>',
                f'<text x="{left + bw + 10:.1f}" y="{y + 30}" class="v start">{escape(s)}</text>']
    return "\n".join(out + ["</svg></figure>"])


CSS = f"""
html {{ font-size: min(1.06vw, 1.62vh); }}
body {{ margin: 0; background: #fff; color: {INK}; font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", Helvetica, Arial, sans-serif;
  font-variant-numeric: tabular-nums; -webkit-font-smoothing: antialiased; }}
main {{ padding: 2.4rem 3rem 5rem; }}
.kicker {{ color: {ACCENT}; font-weight: 600; font-size: 1.1rem; }}
h1 {{ font-size: 2.6rem; font-weight: 700; margin: .25rem 0 .35rem; letter-spacing: -.01em; }}
.sub {{ color: {MUTED}; font-size: 1.2rem; margin: 0 0 1.6rem; }}
.grid {{ display: flex; gap: 3rem; align-items: flex-start; }}
.grid > div {{ display: flex; flex-direction: column; align-items: flex-start; gap: 1.6rem; }}
.grid > .chart {{ flex: 1; min-width: 0; max-width: 36rem; align-items: stretch; }}
table {{ border-collapse: collapse; font-size: 1.3rem; }}
th, td {{ border: 1px solid {LINE}; padding: .48rem .9rem; text-align: left; white-space: nowrap; }}
.r {{ text-align: right; }}
tr.letters th, th.n {{ background: {HEAD}; color: {MUTED}; font-weight: 400; font-size: .95rem; text-align: center; padding: .25rem .6rem; }}
tr.head th:not(.n) {{ background: {HEAD}; font-weight: 600; }}
tr.total td {{ font-weight: 700; border-top: 2px solid {INK}; }}
figure {{ margin: 0; }}
figcaption {{ font-size: 1.3rem; font-weight: 600; margin-bottom: .6rem; }}
svg {{ width: 100%; height: auto; font-family: inherit; }}
svg .v {{ font-size: 26px; font-weight: 600; fill: {INK}; text-anchor: middle; }}
svg .l {{ font-size: 23px; fill: {MUTED}; text-anchor: middle; }}
svg .end {{ text-anchor: end; }}
svg .start {{ text-anchor: start; }}
.notes {{ color: {MUTED}; font-size: 1.15rem; line-height: 1.5; margin: 0; padding: 0; list-style: none; }}
.notes b {{ color: {INK}; }}
footer {{ position: fixed; left: 0; right: 0; bottom: 0; background: {HEAD}; border-top: 1px solid {LINE}; padding: 0 3rem; font-size: 1.05rem; }}
footer span {{ display: inline-block; background: #fff; color: {ACCENT}; font-weight: 600; border: 1px solid {LINE}; border-top: 0; padding: .45rem 1.4rem; margin-top: -1px; }}
"""


def page(kicker, title, sub, tab, tables, chart="", notes=()):
    note = f'<ul class="notes">{"".join(f"<li>{n}</li>" for n in notes)}</ul>' if notes else ""
    right = f'<div class="chart">{chart}{note}</div>' if chart else ""
    left = "".join(tables) + ("" if chart else note)
    return f"""<!doctype html>
<html lang="en">
<meta charset="utf-8">
<title>{escape(title)}</title>
<style>{CSS}</style>
<main>
<div class="kicker">{escape(kicker)}</div>
<h1>{escape(title)}</h1>
<p class="sub">{escape(sub)}</p>
<section class="grid"><div>{left}</div>{right}</section>
</main>
<footer><span>{escape(tab)}</span></footer>
</html>
"""


def market():
    years = [2026, 2027, 2028, 2029, 2030]
    segments = {"Storage and retrieval systems": (11.2, 17.0), "Mobile robots for transport": (5.6, 11.5), "Robotic picking": (3.1, 9.0),
                "Sortation": (4.0, 6.0), "Warehouse software": (2.6, 4.5)}
    rows, totals = [], [0.0] * len(years)
    for name, (a, b) in segments.items():
        g = (b / a) ** (1 / 4)
        vals = [round(a * g ** t, 1) for t in range(5)]
        totals = [x + y for x, y in zip(totals, vals)]
        rows.append([name] + [f"{v:.1f}" for v in vals] + [f"{g - 1:.0%}"])
    cagr = (totals[-1] / totals[0]) ** (1 / 4) - 1
    regions = {"North America": 16.8, "Europe": 13.4, "Asia Pacific": 14.4, "Rest of the world": 3.4}
    total = sum(regions.values())
    return page("Acme Robotics", "Warehouse automation market", "Global market in $ billions, 2026 to 2030. Robotic picking grows fastest.",
                "Market by segment", [
                    table(["Segment"] + [str(y) for y in years] + ["Growth a year"], rows + [["Total"] + [f"{t:.1f}" for t in totals] + [f"{cagr:.0%}"]]),
                    table(["Region, 2030", "$ billions", "Share"], [[r, f"{v:.1f}", pct(v / total)] for r, v in regions.items()] +
                          [["Total", f"{total:.1f}", "100%"]], first=9)],
                bars("Total market, $ billions", [str(y) for y in years], totals, [f"{t:.1f}" for t in totals]),
                ["<b>$9B</b> serviceable: mid-size 3PLs in the EU and the US", "<b>$600M</b> Acme revenue target by year five"])


TIERS = [("1 to 20", 1900, 10), ("21 to 50", 1750, 30), ("51 to 100", 1600, 80), ("101 to 200", 1450, 150)]


def price(robots): return next(p for (span, p, _) in TIERS if robots <= int(span.split()[-1]))


def pricing():
    rows = [[f"{span} robots", money(p), "List" if p == 1900 else f"{1 - p / 1900:.0%} off", f"{n} robots", money(p * n)] for span, p, n in TIERS]
    rows.append(["Above 200 robots", "Quoted", "", "", ""])
    return page("Acme Robotics", "Pricing sheet 2026", "Robots as a service, in US dollars per robot per month. No upfront hardware cost.",
                "Volume tiers", [
                    table(["Fleet size", "Per robot a month", "Discount", "Example fleet", "Monthly bill"], rows),
                    table(["Also in the contract", "Terms"], [
                        ["Included", "Robot, gripper, maintenance, fleet dashboard"], ["Term", "36 months, then monthly"],
                        ["Site integration", "$25,000 once per site"], ["Cold-storage robot", "From Q4 2027, $2,350 a month"]], first=8)],
                bars("Price per robot a month, by fleet size", [s for s, _, _ in TIERS], [p for _, p, _ in TIERS], [money(p) for _, p, _ in TIERS]))


STAGES = {"Qualified": .1, "Proposal": .3, "Pilot": .5, "Negotiation": .7, "Signed": 1.0}


def pipeline():
    deals = [("Calder Row Distribution", "US, Ohio", "Negotiation", 120, "Dec 2026"), ("Brekka Logistics", "Germany", "Negotiation", 60, "Nov 2026"),
             ("Nimbal Grocery", "US, Texas", "Qualified", 80, "Feb 2027"), ("Ostwind 3PL", "Netherlands", "Proposal", 40, "Dec 2026"),
             ("Harwick Supply", "United Kingdom", "Proposal", 35, "Jan 2027"), ("Tessaro Home Retail", "Italy", "Pilot", 30, "Jan 2027"),
             ("Pellan Medical Logistics", "US, New Jersey", "Qualified", 25, "Mar 2027"), ("Lunetta Fulfilment", "France", "Pilot", 24, "Dec 2026"),
             ("Feldmark Parcel", "Switzerland", "Signed", 18, "Oct 2026"), ("Orlo Frozen Foods", "Denmark", "Pilot", 12, "Q2 2027")]
    rows = [[c, r, s, n, money(n * price(n)), d] for c, r, s, n, d in deals]
    robots, value = sum(d[3] for d in deals), sum(d[3] * price(d[3]) for d in deals)
    weighted = sum(n * price(n) * STAGES[s] for _, _, s, n, _ in deals)
    return page("Acme Robotics", "Customer pipeline, Q4 2026", f"{len(deals)} open deals, {robots} robots, {money(value)} a month at list tiers.",
                "Open deals", [table(["Customer", "Region", "Stage", "Robots", "Monthly value", "Expected close"], rows +
                                     [["Total", "", "", robots, money(value), ""]])],
                notes=[f"<b>{money(round(weighted / 1000))}k</b> a month weighted by stage", "Weights: qualified 10%, proposal 30%, pilot 50%, negotiation 70%"])


def competitors():
    rows = [["Picks items from shelves", "Yes", "Yes, goods to person", "No", "Yes"],
            ["Moves totes between zones", "Yes", "Yes", "Yes", "With carts"],
            ["Works in existing shelving", "Yes", "No, needs a rebuild", "Yes", "Yes"],
            ["Time to go live", "6 weeks", "12 to 18 months", "8 weeks", "A hiring season"],
            ["Upfront cost, 50 robots", "$0", "$8M to $15M", "$0.6M", "$0"],
            ["Cost a month, 50 robots", money(50 * 1750), "$40,000 upkeep", "$45,000", "$310,000 wages"],
            ["Picks per hour, each", "320", "600", "None", "120"],
            ["Pick errors at peak", "0.1%", "0.05%", "None", "2.4%"],
            ["Adding capacity for peak", "Robots in a week", "Fixed", "Robots in a week", "Temporary staff"]]
    return page("Acme Robotics", "Competitor comparison", "Acme against the other ways a mid-size warehouse picks orders today.",
                "Comparison", [table(["", "Acme Robotics", "Legacy AS/RS", "Transport-only robots", "Manual picking"], rows)],
                notes=["<b>Acme</b> is the only option that picks and moves items without rebuilding the warehouse."])


def profit():
    q = ["Q2 2026", "Q3 2026"]
    lines = [("Recurring revenue", 10.5, 12.9), ("One-time revenue", 1.1, 1.3), ("Revenue", 11.6, 14.2), ("Cost of revenue", -5.3, -5.5),
             ("Gross profit", 6.3, 8.7), ("Research and development", -5.6, -6.1), ("Sales and marketing", -3.9, -4.3),
             ("General and administrative", -1.8, -1.9), ("Operating loss", -5.0, -3.6), ("Robot fleet capex", -0.7, -1.5), ("Cash used", -5.7, -5.1)]
    fmt = lambda v: f"{v:.1f}" if v >= 0 else f"({-v:.1f})"
    rows = [[n, fmt(a), fmt(b), f"{b / a - 1:+.0%}"] for n, a, b in lines]
    rows.insert(5, ["Gross margin", "54%", "61%", "+7 pts"])
    rows += [["Cash at end of quarter", "43.1", "38.0", ""], ["Monthly burn", "1.9", "1.7", ""], ["Runway, months", "23", "22", ""]]
    quarters = ["Q3 25", "Q4 25", "Q1 26", "Q2 26", "Q3 26", "Q4 26"]
    revenue = [4.6, 6.2, 8.7, 11.6, 14.2, 16.5]
    return page("Acme Robotics", "Profit and loss, Q3 2026", "In $ millions, unaudited. Losses in brackets.", "P&L by quarter",
                [table(["", *q, "Change"], rows, bold=("Revenue", "Gross profit", "Operating loss", "Cash used"))],
                bars("Revenue by quarter, $ millions", quarters, revenue, [f"{v:.1f}" for v in revenue[:-1]] + ["16 to 17"], faint={"Q4 26"}),
                ["<b>Q4 26</b> is guidance", "Break-even targeted for Q3 2027"])


def survey():
    areas = [("Pick accuracy", .92), ("Robot uptime", .89), ("Support response", .84), ("Time to go live", .81), ("Fleet dashboard", .76),
             ("Connection to their WMS", .68)]
    asks = [("A cold-storage robot", 17), ("Picking bags and pouches", 15), ("More WMS connectors", 12), ("One view across sites", 9)]
    return page("Acme Robotics", "Customer survey 2026", "October 2026, 38 of our 42 customers answered.", "Survey results", [
        table(["Satisfied with", "Share"], [[a, pct(v)] for a, v in areas]),
        table(["What they ask for next", "Customers"], [[a, n] for a, n in asks], first=9),
        table(["Headline", "Result"], [["Net promoter score", "61"], ["Would renew", "36 of 38"]], first=15)],
        hbars("Satisfied or very satisfied", [a for a, _ in areas], [v for _, v in areas], [pct(v) for _, v in areas]))


DOCUMENTS = {"Acme market figures 2026": market, "Acme pricing sheet 2026": pricing, "Acme customer pipeline Q4 2026": pipeline,
             "Acme competitor comparison": competitors, "Acme profit and loss Q3 2026": profit, "Acme customer survey 2026": survey}

if __name__ == "__main__":
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "library")
    out.mkdir(parents=True, exist_ok=True)
    for name, make in DOCUMENTS.items():
        (out / f"{name}.html").write_text(make())
        print(out / f"{name}.html")

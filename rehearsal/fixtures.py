"""fixtures.py DIR: what a presenter saw lately, for the "seen recently" lines: two slide decks and two one-page
documents, written to DIR, which run.sh makes and removes around a run. Their names are printed, one per line.
uv run --no-project --with reportlab python rehearsal/fixtures.py DIR"""
import sys
from pathlib import Path

from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen.canvas import Canvas

DECKS = {
    "Alpine hut renovation": [
        ("Alpine hut renovation", ["Cabane du Mont Fort section", "Committee meeting, spring 2026"]),
        ("Why renovate", ["Roof leaks after every storm", "Kitchen fails the hygiene inspection", "Beds for 38, demand for 60"]),
        ("The plan", ["New larch roof and solar panels", "Kitchen moved to the ground floor", "Second dormitory in the old barn"]),
        ("Budget", ["CHF 1.8M in total", "Canton grant CHF 600k", "Members' loan CHF 400k"]),
        ("Timeline", ["Permit by June", "Works from July to October", "Reopening for the winter season"]),
        ("Volunteers", ["Work weekends every Saturday in August", "Sign up with the hut warden"]),
        ("Questions", ["Write to the section president"]),
    ],
    "Community orchard survey": [
        ("Community orchard survey", ["Results from 212 households", "Neighbourhood association, October 2026"]),
        ("Who answered", ["61% families with children", "Most live within 500 m of the park"]),
        ("What people want", ["Apple and pear trees first", "A cider press day in autumn", "Benches and shade"]),
        ("Concerns", ["Wasps near the playground", "Who picks up fallen fruit"]),
        ("Costs", ["CHF 18,000 for 40 trees", "The city pays half"]),
        ("Next steps", ["Planting day on 14 March", "Volunteers to water in summer"]),
    ],
}

DOCUMENTS = {
    "Ferry timetable winter 2026": ["Lake ferry timetable, winter 2026", "Lausanne-Ouchy to Evian", "07:10  08:40  12:15  17:30  19:05",
                                    "Evian to Lausanne-Ouchy", "07:55  09:25  13:00  18:15  19:50", "No sailings on 25 December"],
    "Choir rehearsal schedule": ["Choir rehearsal schedule", "Tuesdays 19:30 in the parish hall", "4 Nov: Faure Requiem, sopranos and altos",
                                 "11 Nov: Faure Requiem, full choir", "18 Nov: dress rehearsal in the church", "22 Nov: concert at 17:00"],
}


def deck(path: Path, slides):
    w, h = 960, 540
    c = Canvas(str(path), pagesize=(w, h))
    for n, (title, bullets) in enumerate(slides, 1):
        c.setFillColor(HexColor("#ffffff"))
        c.rect(0, 0, w, h, fill=1, stroke=0)
        c.setFillColor(HexColor("#1b1f24"))
        c.setFont("Helvetica-Bold", 44)
        c.drawString(64, h - 110, title)
        c.setFont("Helvetica", 26)
        for i, b in enumerate(bullets):
            c.circle(90, h - 181 - i * 52, 4, stroke=0, fill=1)
            c.drawString(108, h - 190 - i * 52, b)
        c.setFont("Helvetica", 14)
        c.drawRightString(w - 40, 28, f"{n} / {len(slides)}")
        c.showPage()
    c.save()


def document(path: Path, lines):
    w, h = A4
    c = Canvas(str(path), pagesize=A4)
    c.setFont("Helvetica-Bold", 22)
    c.drawString(64, h - 90, lines[0])
    c.setFont("Helvetica", 13)
    for i, line in enumerate(lines[1:]):
        c.drawString(64, h - 140 - i * 24, line)
    c.showPage()
    c.save()


if __name__ == "__main__":
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for name, slides in DECKS.items():
        deck(out / f"{name}.pdf", slides)
        print(f"{name}.pdf")
    for name, lines in DOCUMENTS.items():
        document(out / f"{name}.pdf", lines)
        print(f"{name}.pdf")

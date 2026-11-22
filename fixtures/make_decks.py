"""Generate sample decks for the copilot eval: uv run --no-project --with reportlab python fixtures/make_decks.py OUT_DIR"""
import sys
from pathlib import Path

from reportlab.lib.colors import HexColor
from reportlab.pdfgen.canvas import Canvas

DECKS = {
    "Acme_Company_Overview_2026": [
        ("Acme Robotics", ["Company overview 2026", "Autonomous warehouse picking"]),
        ("The problem", ["Warehouses can't hire enough pickers", "Labour is 55% of fulfilment cost", "Peak season error rates triple"]),
        ("Our solution", ["Mobile picking robots with a suction gripper", "Drops into existing shelving, no retrofit", "Learns new items from five photos"]),
        ("Product demo", ["Live fleet dashboard", "Picker robot handling mixed-SKU totes", "Human-in-the-loop for hard picks"]),
        ("Market size", ["TAM $48B warehouse automation by 2030", "SAM $9B mid-size 3PLs in EU and US", "SOM $600M by year five"]),
        ("Business model and pricing", ["Robots-as-a-service: $1,900 per robot per month", "No upfront hardware cost", "Volume tiers above 20 robots"]),
        ("Traction", ["42 customers, 610 robots deployed", "ARR grew 3.1x year over year", "Net revenue retention 148%"]),
        ("Competition", ["Legacy AS/RS: expensive, needs rebuild", "Other AMR vendors: transport only, no picking", "Acme: picking plus transport, no retrofit"]),
        ("Team", ["CEO Lina Ortega, ex-Amazon Robotics", "CTO Marc Weber, ETH Zurich PhD in grasping", "85 people across Lausanne and Boston"]),
        ("Roadmap and the ask", ["Raising $40M Series B", "Cold-storage robot in 2027", "Expand to 12 new countries"]),
    ],
    "Acme_Q3_2026_Financials": [
        ("Q3 2026 financial review", ["Acme Robotics finance team", "Board meeting, October 2026"]),
        ("Revenue", ["Q3 revenue $14.2M, up 22% quarter on quarter", "Recurring revenue is 91% of total"]),
        ("Gross margin", ["Gross margin 61%, up from 54%", "Driven by cheaper gripper hardware"]),
        ("Operating expenses", ["R&D $6.1M", "Sales and marketing $4.3M", "G&A $1.9M"]),
        ("Cash and runway", ["Cash on hand $38M", "Monthly burn $1.7M", "Runway 22 months"]),
        ("Customer churn", ["Logo churn 2.4% annualised", "Two customers lost to in-house builds"]),
        ("Hiring plan", ["Add 30 engineers in Q4", "Open a sales office in Munich"]),
        ("Outlook", ["Q4 revenue guidance $16-17M", "Break-even targeted for Q3 2027"]),
    ],
    "Acme_Product_Roadmap": [
        ("Product roadmap", ["Acme Robotics product team", "H1 and H2 2027"]),
        ("Gripper v3", ["Handles deformable bags and pouches", "Pick rate up 35%"]),
        ("Cold-storage robot", ["Operates at minus 25 degrees", "Pilot with a frozen-food grocer"]),
        ("Fleet AI scheduler", ["Plans picks across 200 robots", "Cuts travel distance 18%"]),
        ("Integrations", ["SAP EWM, Manhattan, Blue Yonder connectors", "Open REST API for custom WMS"]),
        ("Timeline", ["Q1: Gripper v3 GA", "Q2: Scheduler beta", "Q4: Cold-storage launch"]),
    ],
    "Home_Insurance_Review": [
        ("Home insurance review", ["Claire and Marc Dubois", "Annual review with Leman Assurances", "Hamid, your advisor"]),
        ("Agenda", ["Your current cover", "Premium and deductible options", "Market and competitors", "The revised offer and next steps"]),
        ("Your current cover", ["Building insured for CHF 1.2M", "Contents insured for CHF 150,000", "Water damage and natural hazards included", "Deductible CHF 200 per claim"]),
        ("Premium breakdown", ["CHF 2,140 per year in total", "Building 58%, contents 27%", "Liability 9%, home assistance 6%"]),
        ("Deductible scenarios", ["CHF 200 deductible: CHF 2,140 per year", "CHF 500 deductible: CHF 1,960 per year", "CHF 1,000 deductible: CHF 1,790 per year", "A higher deductible lowers the premium"]),
        ("What clients ask us", ["Can I lower my premium?", "What if I raise the deductible?", "How does this compare?"]),
        ("Market size", ["Swiss home insurance premiums per year", "2023: CHF 3.1bn", "2024: CHF 3.3bn", "2025: CHF 3.6bn", "2026: CHF 3.9bn, up 26% on 2023"]),
        ("Compared with other insurers", ["Same cover, CHF 500 deductible", "Insurer A: CHF 2,290 per year", "Insurer B: CHF 2,050, natural hazards extra", "Leman Assurances: CHF 1,960 per year"]),
        ("Your revised offer", ["Deductible raised to CHF 500", "Premium CHF 1,960 per year, down 8%", "Contents cover raised to CHF 180,000", "Valid until 30 April"]),
        ("Next steps", ["Choose your deductible", "We send the contract to sign", "New cover starts 1 May"]),
    ],
    "Pricing_Workshop": [
        ("Pricing workshop", ["Home and contents tariff 2027", "Actuarial, product and distribution teams"]),
        ("Goals for today", ["Agree the risk factors we rate on", "Set the target loss ratio", "Settle the discount rules"]),
        ("Risk factors", ["Building age and construction type", "Postcode flood and hail zone", "Claims in the last five years", "Sum insured for contents"]),
        ("Loss ratios", ["Home portfolio loss ratio 71% in 2025", "Water damage is 44% of claims cost", "Hail claims doubled since 2021", "Target 65% by end of 2027"]),
        ("Price elasticity", ["Each 5% increase costs 2 points of retention", "Young families and renters leave first", "Homeowners over 60 barely react"]),
        ("Pricing simulation", ["Base rate +4%, flood and hail zones +9%", "CHF 1,000 deductible lowers the premium 12%", "Portfolio premium +CHF 6.8M a year", "Retention down 1.5 points"]),
        ("Discounts", ["Home and motor bundle: 10%", "Five claims-free years: 8%", "Certified water leak sensor: 5%", "All discounts capped at 20%"]),
        ("Open questions", ["Scale the deductible discount or keep steps?", "Pass the hail surcharge to existing clients?", "Who funds the broker commission budget?"]),
    ],
    "Steering_Committee": [
        ("Steering committee", ["Tarif 2027 programme, Leman Assurances", "Monthly steering with the board"]),
        ("Agenda", ["Status", "Budget", "Risks", "Decisions needed", "Timeline"]),
        ("Project status", ["Overall status: amber", "Pricing model rebuilt and back-tested", "Broker portal changes two weeks late", "Tariff filing date unchanged"]),
        ("Workstreams", ["Risk model: done", "Claims data clean-up: 80%", "Broker portal: in progress", "Broker training: not started"]),
        ("Budget", ["CHF 2.4M approved, CHF 1.6M spent", "Forecast CHF 2.7M at completion", "Overrun from extra actuarial contractors"]),
        ("Risks", ["Claims data from the legacy system", "Hail claims after the June storm", "Broker pushback on higher premiums", "FINMA filing window closes 30 June"]),
        ("Decisions needed", ["Release CHF 300k from contingency", "Approve a 4% average premium increase", "Move broker launch from July to September"]),
        ("Timeline", ["June: tariff filed with FINMA", "August: broker training", "1 January 2027: new premiums live"]),
    ],
}

W, H = 960, 540


def render(path: Path, slides: list[tuple[str, list[str]]]) -> None:
    c = Canvas(str(path), pagesize=(W, H))
    c.setTitle(path.stem.replace("_", " "))
    for n, (title, bullets) in enumerate(slides, 1):
        c.setFillColor(HexColor("#10161f"))
        c.rect(0, 0, W, H, fill=1, stroke=0)
        c.setFillColor(HexColor("#f2f4f7"))
        c.setFont("Helvetica-Bold", 44)
        c.drawString(64, H - 110, title)
        c.setFont("Helvetica", 26)
        for i, b in enumerate(bullets):
            c.drawString(84, H - 190 - i * 52, f"•  {b}")
        c.setFont("Helvetica", 14)
        c.setFillColor(HexColor("#8a94a6"))
        c.drawRightString(W - 40, 28, f"{n} / {len(slides)}")
        c.showPage()
    c.save()


if __name__ == "__main__":
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "library")
    out.mkdir(parents=True, exist_ok=True)
    for name, slides in DECKS.items():
        render(out / f"{name}.pdf", slides)
        print(out / f"{name}.pdf", len(slides), "slides")

#!/bin/sh
# make_pitch.sh: the pitch/ folder Molière presents from, built from mole/slides.html with headless Chrome:
# "Moliere pitch.pdf" (one slide a page, text kept as text) and slide 8's two boxes as PNGs, also copied to docs/readme/.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:-$ROOT/pitch}
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT"

# A temporary copy of the deck with its folder as base and extra CSS before </style>; the deck itself is untouched.
copy() { sed -e "s|<meta charset=\"utf-8\">|<meta charset=\"utf-8\"><base href=\"file://$ROOT/mole/\">|" -e "s|^</style>|$2</style>|" "$ROOT/mole/slides.html" > "$TMP/$1"; }
chrome() { "$CHROME" --headless --disable-gpu --hide-scrollbars --no-first-run --no-default-browser-check --user-data-dir="$TMP/profile" --virtual-time-budget=4000 "$@" 2>/dev/null; }

copy deck.html '@page { size: 1280px 720px; margin: 0 } @media print { .slide:last-child { break-after: auto } }'
chrome --no-pdf-header-footer --print-to-pdf="$OUT/Moliere pitch.pdf" "file://$TMP/deck.html"

# One box of slide 8 alone, centred on a white 16:9 page, zoomed to fill it; 1920 x 1080 at scale 1.5.
shot() { # name, box to keep, box to hide, its width in the deck, zoom
  copy "$1.html" ".s-how { display: flex; align-items: center; justify-content: center; padding: 0 } .s-how > :not(.slots), .s-how .$3 { display: none } .s-how .slots { display: block; margin: 0 } .s-how .$2 { width: $4px; zoom: $5 }"
  chrome --window-size=1280,720 --force-device-scale-factor=1.5 --screenshot="$OUT/$1.png" "file://$TMP/$1.html#8"
}
shot "Moliere architecture diagram" arch stack 725 1.55
shot "Moliere tech stack" stack arch 560 1.9

mkdir -p "$ROOT/docs/readme"
cp "$OUT/Moliere architecture diagram.png" "$ROOT/docs/readme/architecture.png"
cp "$OUT/Moliere tech stack.png" "$ROOT/docs/readme/tech-stack.png"

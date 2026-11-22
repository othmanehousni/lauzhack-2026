#!/bin/bash
# Usage: rehearsal/run.sh <scenario file> <out dir> [gap seconds] [extra app flags...]
# Feeds a scenario to the packaged app through a named pipe, against the real VM, with browser history off. Scenario
# lines: "words || expectation" (see check.py); "@N" sets the pause after the following lines; "# name" starts a
# category. Before the app starts, rehearsal/fixtures.py writes what a presenter saw lately to $FIX, and Spotlight is
# waited for; after it stops, they are removed. BUNDLE names another folder holding Moliere.app (not the binary's
# path, which the wait for other runs would find in this command line), LIBRARY another folder of decks.
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APP=${BUNDLE:-$ROOT/composeApp/build/compose/binaries/main/app}/Moliere.app/Contents/MacOS/Moliere
LIB=${LIBRARY:-$ROOT/library}  # the three Acme decks of fixtures/make_decks.py
FIX="$HOME/Documents/Moliere rehearsal fixtures"
SCEN=$1; OUT=$2; GAP=${3:-6}; shift 3 2>/dev/null
[ -x "$APP" ] && [ -d "$LIB" ] || { echo "no app at $APP or no decks in $LIB"; exit 1; }
mkdir -p "$OUT"; rm -f "$OUT"/*.png "$OUT/out.txt"
while pgrep -f "Moliere.app/Contents/MacOS" >/dev/null; do sleep 5; done

[ -e "$FIX" ] && [ ! -e "$FIX/.fixtures" ] && { echo "$FIX exists and is not ours"; exit 1; }
mkdir -p "$FIX" && touch "$FIX/.fixtures"
NAMES=$(uv run --offline --quiet --no-project --with reportlab python "$ROOT/rehearsal/fixtures.py" "$FIX") || exit 1
cleanup() { (cd "$FIX" && echo "$NAMES" | while IFS= read -r n; do rm -f "./$n"; done; rm -f .fixtures); rmdir "$FIX"; }
trap cleanup EXIT
for i in $(seq 1 20); do  # Spotlight may lag: the lines seen recently then fail as not retrieved
  [ "$(mdfind -onlyin "$FIX" 'kMDItemContentType == "com.adobe.pdf"' | wc -l)" -ge "$(echo "$NAMES" | wc -l)" ] &&
    [ "$(mdfind -onlyin "$FIX" 'kMDItemNumberOfPages >= 3' | wc -l)" -ge 2 ] && break
  sleep 1
done
{ basename -a "$LIB"/*.pdf; echo "$NAMES"; } | sed 's/\.pdf$//' > "$OUT/public.txt"  # names check.py may print

while pgrep -f "Moliere.app/Contents/MacOS" >/dev/null; do sleep 5; done  # one may have started meanwhile
FIFO=$(mktemp -u /tmp/moliere-typed.XXXXXX); mkfifo "$FIFO"
"$APP" --typed --library "$LIB" --no-history "$@" < "$FIFO" >> "$OUT/out.txt" 2>&1 &
PID=$!
exec 3> "$FIFO"
for i in $(seq 1 240); do grep -q "mole    hello" "$OUT/out.txt" && break; kill -0 $PID 2>/dev/null || break; sleep 1; done
if ! grep -q "mole    hello" "$OUT/out.txt"; then  # no meeting: the phone port was taken, or it never came up
  echo "not ready after ${i}s" >> "$OUT/out.txt"; kill -TERM $PID 2>/dev/null; wait $PID 2>/dev/null; exec 3>&-; rm -f "$FIFO"; exit 1
fi
echo "ready after ${i}s" >> "$OUT/out.txt"; sleep 3
while IFS= read -r line; do
  case "$line" in "") continue;; "#"*) echo "##$line" >> "$OUT/out.txt"; continue;; "@"*) GAP=${line#@}; continue;; esac
  say=${line%% || *}
  echo ">>> $line" >> "$OUT/out.txt"; echo "$say" >&3; sleep "$GAP"
done < "$SCEN"
sleep 5; kill -TERM $PID; wait $PID 2>/dev/null
exec 3>&-; rm -f "$FIFO"
cp ~/Library/Logs/Moliere/screens/*.png "$OUT"/ 2>/dev/null
echo "done" >> "$OUT/out.txt"

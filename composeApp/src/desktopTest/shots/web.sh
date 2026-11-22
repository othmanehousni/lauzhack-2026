#!/bin/sh
# Renders the web mole (mole/index.html) for every shot in shots.json into composeApp/build/mole-shots/web/, with
# headless Chrome through agent-browser. Then `MoleShots` (desktopTest) puts each next to its Compose render.
set -e
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../../.." && pwd)
out="$repo/composeApp/build/mole-shots/web"
mkdir -p "$out"
ab="agent-browser --session $(agent-browser session id --scope worktree --prefix mole-shots)"
node -e 'for (const s of require(process.argv[1])) console.log(s.name, s.phone ? 1 : 0, JSON.stringify(s))' "$here/shots.json" |
while read -r name phone spec; do
  if [ "$phone" = 1 ]; then $ab set viewport 284 634 2 >/dev/null; else $ab set viewport 364 280 2 >/dev/null; fi
  $ab open "file://$repo/mole/index.html" >/dev/null
  { echo "const SPEC = $spec;"; cat "$here/web.js"; } | $ab eval --stdin >/dev/null
  $ab wait 300 >/dev/null
  $ab screenshot "$out/$name.png" >/dev/null
  echo "$name"
done
$ab close >/dev/null

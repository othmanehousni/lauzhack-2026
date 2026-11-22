#!/bin/sh
# Renders the web mole (mole/index.html) for every shot in shots.json into composeApp/build/mole-shots/web/, with
# headless Chrome through agent-browser. Then `MoleShots` (desktopTest) puts each next to its Compose render.
set -e
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../../.." && pwd)
out="$repo/composeApp/build/mole-shots/web"
mkdir -p "$out"
ab="agent-browser --session $(agent-browser session id --scope worktree --prefix mole-shots)"
# Each shot twice, in light and as <name>-dark. data-theme forces the side of every light-dark() token, and the emulated
# prefers-color-scheme matches it, so this Mac's own appearance never leaks in.
node -e 'for (const s of require(process.argv[1])) for (const theme of ["light", "dark"]) console.log(s.name + (theme === "dark" ? "-dark" : ""), s.phone ? 1 : 0, theme, JSON.stringify({ ...s, theme }))' "$here/shots.json" |
while read -r name phone theme spec; do
  if [ "$phone" = 1 ]; then $ab set viewport 284 634 2 >/dev/null; else $ab set viewport 364 280 2 >/dev/null; fi
  $ab set media "$theme" >/dev/null
  $ab open "file://$repo/mole/index.html" >/dev/null
  { echo "const SPEC = $spec;"; cat "$here/web.js"; } | $ab eval --stdin >/dev/null
  $ab wait 300 >/dev/null
  $ab screenshot "$out/$name.png" >/dev/null
  echo "$name"
done
$ab close >/dev/null

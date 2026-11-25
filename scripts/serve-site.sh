#!/usr/bin/env bash
# Serve the GitHub Pages site (home page + slides) locally.
#
#   scripts/serve-site.sh            # http://localhost:8000/  (slides: /slides/)
#   scripts/serve-site.sh 9000       # another port
#   scripts/serve-site.sh --open     # and open it in the browser
#
# Same content as https://ssledz.github.io/yama/. Slides keys: s = speaker notes, f = fullscreen,
# Esc = overview, ↓ = vertical deep dives. Needs python3 (preinstalled on macOS and most Linux distros).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)/site"
port=8000
open_browser=false

for arg in "$@"; do
  case "$arg" in
    --open) open_browser=true ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *[!0-9]*|'') echo "unknown argument: $arg" >&2; exit 2 ;;
    *) port="$arg" ;;
  esac
done

command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }

url="http://localhost:$port/"
echo "yama site:  $url"
echo "slides:     ${url}slides/"
echo "Ctrl-C to stop."

if $open_browser; then
  ( sleep 1
    if command -v open >/dev/null; then open "${url}slides/"
    elif command -v xdg-open >/dev/null; then xdg-open "${url}slides/"
    fi ) &
fi

exec python3 -m http.server "$port" --bind 127.0.0.1 --directory "$root"

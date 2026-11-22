#!/usr/bin/env bash
# Polylith guard: a brick may import only its own `internal` package.
# The compiler already enforces this through private[<brick>]; this is the cheap extra check for CI.
set -euo pipefail
cd "$(dirname "$0")/.."

violations=$(
  find components bases -name '*.scala' -path '*/src/*' | while IFS= read -r file; do
    own=$(grep -m1 '^package ' "$file" | awk '{print $2}' | sed 's/\.internal$//')
    { grep -nE '^import yama\.[A-Za-z.]+\.internal' "$file" || true; } | while IFS= read -r line; do
      imported=$(sed -E 's/.*import (yama\.[A-Za-z.]+)\.internal.*/\1/' <<<"$line")
      [[ "$imported" == "$own" ]] || echo "$file:$line"
    done
  done
)

if [[ -n "$violations" ]]; then
  echo "Brick boundary violations (importing another brick's internal package):"
  echo "$violations"
  exit 1
fi
echo "brick boundaries OK"

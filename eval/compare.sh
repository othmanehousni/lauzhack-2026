#!/bin/sh
# Score routers on every eval script: sh eval/compare.sh d1 qwen3.5:2b-q4_K_M lfm3:1.7b-q4_K_M
cd "$(dirname "$0")/.." || exit 1
for router in "$@"; do
  for talk in eval/talk*.jsonl; do
    printf '%-20s %-20s ' "$router" "$(basename "$talk" .jsonl)"
    uv run copilot.py --router "$router" eval "$talk" | tail -1
  done
done

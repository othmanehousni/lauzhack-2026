#!/bin/sh
# Score decision models on every eval script: sh eval/compare.sh LiquidAI/d1-2B-GGUF:Q8_0 h2oai/h2o-lightning-4b-gguf:Q4_K_M
# Each model gets its own llama-server port, so a server already up for one model is never scored as another.
cd "$(dirname "$0")/.." || exit 1
port=8100
for router in "$@"; do
  port=$((port + 1))
  echo "$router"
  D1_HOST="http://127.0.0.1:$port" uv run copilot.py --router "$router" eval eval/*.jsonl | grep "screen correct"
done

#!/usr/bin/env bash
# Replays durable-facts.txt through one real Sophi session (isolated home, encoder telemetry on) and
# reports what memory kept. Target: no candidate dropped. Usage: evals/memory/replay.sh
# Env: SOPHI_JAR, SOPHI_FLAGS (same defaults as evals/playbook/run.sh).
set -o pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JAR="${SOPHI_JAR:-$HERE/../../sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar}"
DEFAULT_FLAGS="$(grep '^DEFAULT_FLAGS=' "$HERE/../playbook/run.sh" | cut -d'"' -f2)"
read -ra FLAGS <<< "${SOPHI_FLAGS:-$DEFAULT_FLAGS}"
D="$(mktemp -d)"; mkdir -p "$D/home/.sophi" "$D/cwd"
(cd "$D/cwd" && SOPHI_MEMORY_ENCODER_TELEMETRY=true java -Duser.home="$D/home" -jar "$JAR" "${FLAGS[@]}" \
  < "$HERE/durable-facts.txt" > "$D/transcript.txt" 2>&1)
LOG="$D/home/.sophi/memory/encoder.jsonl"
if [[ ! -s "$LOG" ]]; then
  echo "no encoder telemetry at $LOG: memory was off or the session failed; see $D/transcript.txt" >&2; exit 1
fi
python3 - "$LOG" "$(grep -c . "$HERE/durable-facts.txt")" <<'PY'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1])]
cands = [r for r in rows if not r["outcome"].startswith("proposed")]
for r in cands:
    print(f'{r["outcome"]:<24} alpha={r.get("alpha", 0):.2f} dur={r.get("dur", "-")} fb={r.get("durFallback", "-")} {r.get("text", "")[:80]}')
# Counted per candidate, not per statement: the encoder can propose one statement twice (stored, then
# merged), so "stored + merged" can exceed the statement count. The honest bar is: nothing dropped.
count = lambda pred: sum(pred(r["outcome"]) for r in cands)
stored, merged, dropped = count(lambda o: o == "stored"), count(lambda o: o == "merged"), count(lambda o: o.startswith("dropped"))
print(f"\n{sys.argv[2]} statements -> candidates: stored {stored}, merged {merged}, dropped {dropped}")
sys.exit(0 if dropped == 0 else 1)
PY
rc=$?; echo "run dir: $D"; exit $rc

#!/usr/bin/env bash
# Replays durable-facts.txt through one real Sophi session (isolated home, encoder telemetry on) and
# reports what memory kept. Target: every line stored. Usage: evals/memory/replay.sh
# Env: SOPHI_JAR, SOPHI_FLAGS (same defaults as evals/playbook/run.sh).
set -o pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JAR="${SOPHI_JAR:-$HERE/../../sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar}"
DEFAULT_FLAGS="$(grep '^DEFAULT_FLAGS=' "$HERE/../playbook/run.sh" | cut -d'"' -f2)"
read -ra FLAGS <<< "${SOPHI_FLAGS:-$DEFAULT_FLAGS}"
D="$(mktemp -d)"; mkdir -p "$D/home/.sophi" "$D/cwd"
(cd "$D/cwd" && SOPHI_MEMORY_ENCODER_TELEMETRY=true java -Duser.home="$D/home" -jar "$JAR" "${FLAGS[@]}" \
  < "$HERE/durable-facts.txt" > "$D/transcript.txt" 2>&1)
python3 - "$D/home/.sophi/memory/encoder.jsonl" "$(grep -c . "$HERE/durable-facts.txt")" <<'PY'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1])]
cands = [r for r in rows if not r["outcome"].startswith("proposed")]
for r in cands:
    print(f'{r["outcome"]:<24} alpha={r.get("alpha", 0):.2f} dur={r.get("dur", "-")} fb={r.get("durFallback", "-")} {r.get("text", "")[:80]}')
stored = sum(r["outcome"] in ("stored", "merged") for r in cands)
print(f"\nstored {stored} of {sys.argv[2]} statements")
sys.exit(0 if stored >= int(sys.argv[2]) else 1)
PY
rc=$?; echo "run dir: $D"; exit $rc

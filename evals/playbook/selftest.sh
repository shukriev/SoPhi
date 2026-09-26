#!/usr/bin/env bash
# No-LLM check of every case: after setup the check must FAIL, after the reference solution it
# must PASS. A check that can't fail, or can't pass, is a case-bug.
# Usage: evals/playbook/selftest.sh [case-id ...]
set -o pipefail
export PB="$(cd "$(dirname "$0")" && pwd)"
source "$PB/cases.sh" && source "$PB/refs.sh" || exit 2
if [ $# -gt 0 ]; then ids=("$@"); else ids=("${CASES[@]}"); fi
[ ${#ids[@]} -gt 0 ] || { echo "no cases defined" >&2; exit 2; }
fails=0
for id in "${ids[@]}"; do
  fn="${id//-/_}"
  W="$(mktemp -d)"; cp -R "$PB/fixtures/ledger/." "$W/"
  ( cd "$W" && "setup_$fn" ) >/dev/null; rc=$?
  if [ $rc -eq 77 ]; then echo "skip $id (prerequisite missing)"; continue; fi
  if [ $rc -ne 0 ]; then echo "FAIL $id: setup failed ($W)"; fails=$((fails+1)); continue; fi
  if ( cd "$W" && "check_$fn" ) >/dev/null 2>&1; then
    echo "FAIL $id: check passes before any work ($W)"; fails=$((fails+1)); continue
  fi
  if ! ( cd "$W" && "ref_$fn" && "check_$fn" ) >/dev/null 2>&1; then
    echo "FAIL $id: check fails on the reference solution ($W)"; fails=$((fails+1)); continue
  fi
  rm -rf "$W"; echo "ok   $id"
done
echo "$fails failure(s)"; exit $fails

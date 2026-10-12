#!/usr/bin/env bash
# Mine labelled queries from git history (spec §9): commit subject -> files it changed.
# Usage: mine-commits.sh <repo-dir> <project-name> [max-commits]
# Skips merges and commits touching more than 20 or zero files; strips "type(scope): " prefixes.
set -euo pipefail
repo="$1"; name="$2"; max="${3:-300}"
git -C "$repo" log --no-merges -n "$max" --pretty=format:'%x01%s' --name-only |
awk -v name="$name" -v RS='\001' -F'\n' 'NF>1 {
  subj=$1; sub(/^[a-z]+(\([^)]*\))?!?: */, "", subj); gsub(/\t/, " ", subj)
  files=""; n=0
  for (i=2; i<=NF; i++) if ($i != "") { files = files (n ? "," : "") name "/" $i; n++ }
  if (n > 0 && n <= 20 && length(subj) >= 12) print subj "\t" files
}'

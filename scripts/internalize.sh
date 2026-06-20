#!/usr/bin/env bash
# Prepends `internal` to top-level public declarations (column 0, no existing
# visibility modifier) in the given Kotlin files. Idempotent. The Kotlin
# compiler is the safety net for any over-reach — internalize, then let the
# build's visibility errors tell you exactly what to revert.
#   usage: internalize.sh <file.kt> [file.kt ...]
set -euo pipefail
for f in "$@"; do
  perl -i -pe '
    s/^(?!\s)(?!(?:public|private|internal|protected)\b)((?:(?:expect|actual|sealed|data|open|abstract|enum|value|inline|annotation|const)\s+)*(?:class|interface|object|fun|val|var)\b)/internal $1/;
  ' "$f"
done
echo "internalized: $# file(s)"

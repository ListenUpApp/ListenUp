#!/usr/bin/env bash
# Fails if any iOS Swift code `await`s a Kotlin suspend returning AppResult across the Swift Export
# bridge — that traps at runtime (`__createProtocolWrapper(...) as! any AppResult` cast failure →
# frozen UI). Fix: use a Kotlin-side plain-typed `*OrNull` accessor (unwrap AppResult in Kotlin).
# See app/iosApp/CLAUDE.md.
#
# Heuristic (static, name-based) with two false-positive guards:
#   * only watches names that are *uniquely* AppResult-returning in Shared.swift — a name with both
#     an AppResult and a non-AppResult overload is skipped (grep can't disambiguate it);
#   * matches only an `await` whose direct receiver chain reaches `.<fn>(` — so enum-case
#     comparisons and nested `await otherFn { … .fn() }` don't false-trigger.
# Residual gap (accepted): a real trap on an overloaded name slips through. Live examples today —
#   `prepare` (PlaybackPreparer→PreparedPlayback? vs PlaybackService→AppResult) and
#   `resumeIncompleteDownloads` (DownloadService→Unit vs DownloadRepository→AppResult) — are safe
#   only because Swift targets the non-AppResult variant; awaiting the AppResult overload would pass.
# bash 3.2-safe (no mapfile).
#
# Usage: check-no-appresult-await.sh [path-to-Shared.swift]   (auto-discovers if omitted)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SHARED="${1:-}"
if [[ -z "$SHARED" || ! -f "$SHARED" ]]; then
  SHARED="$(find "$REPO_ROOT/app/sharedLogic/build" -name 'Shared.swift' -path '*SPMPackage*' 2>/dev/null | head -1)"
  [[ -z "$SHARED" ]] && SHARED="$(find "$REPO_ROOT/app/sharedLogic/build" -name 'Shared.swift' 2>/dev/null | head -1)"
fi
if [[ -z "$SHARED" || ! -f "$SHARED" ]]; then
  echo "check-no-appresult-await: Shared.swift not found (run after a Swift Export build on macOS)." >&2
  exit 2
fi

# Names of exported funcs that are UNIQUELY AppResult-returning (every `public func NAME(...)`
# returns `any …api.result.AppResult`; drop any name with a non-AppResult overload).
watch="$(perl -0777 -ne '
  my %ar; my %other;
  while (/public func ([A-Za-z_]\w*)\s*\(([^{]*?)\{/sg) {
    my ($n,$sig) = ($1,$2);
    if ($sig =~ /->\s*any\s+[\w.]*api\.result\.AppResult\b/s) { $ar{$n}=1 } else { $other{$n}=1 }
  }
  for (sort keys %ar) { print "$_\n" unless $other{$_} }
' "$SHARED")"

if [[ -z "$watch" ]]; then
  # Fail closed. AppResult is the canonical fallible-suspend contract, so an empty watch list
  # never means "nothing to guard" — it means the perl signature matcher above stopped matching
  # the generated formatting (e.g. after a Swift-Export/Kotlin bump). The old `exit 0` turned
  # that into a silently-disarmed gate. Same floor-the-harvest reasoning as the sealed-subtype
  # drift check in app/sharedLogic/build.gradle.kts.
  echo "check-no-appresult-await: no uniquely-AppResult exported funcs harvested from '$SHARED'." >&2
  echo "  The signature matcher is broken (Swift Export output format changed?), not the surface." >&2
  exit 2
fi

count="$(printf '%s\n' "$watch" | grep -c .)"
hits=0
while IFS= read -r fn; do
  [[ -z "$fn" ]] && continue
  while IFS= read -r line; do
    echo "VIOLATION: $line"
    hits=1
  done < <(grep -rnE "(try[?]?[[:space:]]+)?await[[:space:]]+[A-Za-z0-9_.()?]*[.]${fn}[(]" "$REPO_ROOT/app/iosApp" --include='*.swift' 2>/dev/null)
done <<< "$watch"

if [[ "$hits" -eq 1 ]]; then
  cat >&2 <<'MSG'

✗ iOS Swift must not `await` an AppResult-returning Kotlin suspend — it traps in the Swift Export
  bridge (`as! any AppResult` cast failure → frozen UI). Add/use a Kotlin-side plain-typed `*OrNull`
  accessor (unwrap AppResult in Kotlin). See app/iosApp/CLAUDE.md.
MSG
  exit 1
fi
echo "check-no-appresult-await: clean (${count} uniquely-AppResult fns, 0 awaited from Swift)."
exit 0

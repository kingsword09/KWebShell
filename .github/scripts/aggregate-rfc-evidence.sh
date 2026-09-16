#!/usr/bin/env bash
# Records the hosted RFC 0001-0003 evidence records for all three targets from
# the retained artifacts of this run's verification jobs. Runs on the
# aggregation job's LF checkout: each record binds the committed LF contract
# bytes and one target's retained artifact, and the final manifest lands where
# the check-in commit expects it.
set -euo pipefail

cd "$(dirname "$0")/../.."

downloaded="build/rfc-evidence/downloaded"
input="docs/rfcs/evidence/manifest.json"

record() {
  local target="$1" rfc="$2" artifact="$3" file="$4" input="$5" output="$6"
  if [ -z "$file" ] || [ ! -f "$file" ]; then
    echo "Missing $artifact evidence file for $target" >&2
    exit 1
  fi
  ./gradlew --no-daemon :kweb-rfc-governance:rfcEvidenceRecord \
    -PrfcEvidenceArguments="record $input $output --catalog docs/rfcs --runtime runtime/cef-runtime.json --contracts docs/rfcs/evidence/contracts.json --repository-root . --rfc $rfc --provider governance.hosted --electron-major 44 --target $target --artifact $artifact=$file"
}

for target in macos-arm64 windows-x64 linux-x64; do
  chain="$input"
  specs=(
    "0001|governance-report|status.json|rfc-governance"
    "0002|provider-lifecycle-report|provider-lifecycle-report.json|provider-lifecycle"
    "0003|dialogs-consent|consent-status.json|native-dialogs"
  )
  count=0
  for spec in "${specs[@]}"; do
    IFS='|' read -r rfc artifact name family <<< "$spec"
    file=$(find "$downloaded" -type f -name "$name" -path "*${family}-${target}-*" | head -1)
    count=$((count + 1))
    if [ "$count" -eq 3 ]; then
      output="$input"
    else
      output="build/rfc-evidence/chain-${target}-${count}.json"
    fi
    record "$target" "$rfc" "$artifact" "$file" "$chain" "$output"
    chain="$output"
  done
done

echo "Hosted RFC evidence recorded into $input"

#!/usr/bin/env bash
# Records the hosted RFC evidence records for all three targets from
# the retained artifacts of this run's verification jobs. Runs on the
# aggregation job's LF checkout: each record binds the committed LF contract
# bytes and one target's retained artifact, and the final manifest lands where
# the check-in commit expects it.
set -euo pipefail

cd "$(dirname "$0")/../.."

downloaded="build/rfc-evidence/downloaded"
input="docs/rfcs/evidence/manifest.json"

# The recorder refuses a non-Implemented catalog: normalize the statuses in
# this workspace before recording. The checked-in flip lands in the same commit
# as the checked-in records.
for rfc in 0001 0002 0003 0004 0006 0007 0008 0030; do
  file=$(ls docs/rfcs/${rfc}-*.md)
  sed 's/^- Status: .*$/- Status: Implemented/' "$file" > "$file.recorded"
  mv "$file.recorded" "$file"
done

record() {
  local target="$1" rfc="$2" provider="$3" artifact="$4" file="$5" input="$6" output="$7" extra_name="${8:-}" extra_file="${9:-}"
  if [ -z "$file" ] || [ ! -f "$file" ]; then
    echo "Missing $artifact evidence file for $target" >&2
    exit 1
  fi
  local arguments="record $input $output --catalog docs/rfcs --runtime runtime/cef-runtime.json --contracts docs/rfcs/evidence/contracts.json --repository-root . --rfc $rfc --provider $provider --electron-major 44 --target $target --artifact $artifact=$file"
  if [ -n "$extra_name" ]; then
    if [ -z "$extra_file" ] || [ ! -f "$extra_file" ]; then
      echo "Missing $extra_name evidence file for $target" >&2
      exit 1
    fi
    arguments="$arguments --artifact $extra_name=$extra_file"
  fi
  if [ "$rfc" = "0008" ]; then
    arguments="$arguments --matrix-row web-contents-reload"
    arguments="$arguments --matrix-row web-contents-before-unload"
    arguments="$arguments --matrix-row window-open-handler"
    arguments="$arguments --matrix-row renderer-process-state"
  fi
  ./gradlew --no-daemon :kweb-rfc-governance:rfcEvidenceRecord \
    -PrfcEvidenceArguments="$arguments"
}

for target in macos-arm64 windows-x64 linux-x64; do
  chain="$input"
  specs=(
    "0001|governance.hosted|governance-contract-tests|TEST-io.github.kingsword09.kwebshell.rfc.KWebRfcGovernanceCheckerTest.xml|rfc-governance"
    "0002|governance.hosted|provider-lifecycle-report|provider-lifecycle-report.json|provider-lifecycle"
    "0003|governance.hosted|dialogs-consent|consent-status.json|native-dialogs"
    "0004|governance.hosted|stream-conformance|stream-conformance.json|engine-integration"
    "0030|packaging.hosted|application-package|application-package-report.json|application-package"
    "0006|application.lifecycle.hosted|application-lifecycle|application-lifecycle-report.json|application-lifecycle|application-shutdown|application-shutdown.json|engine-integration"
    "0008|page.lifecycle.hosted|page-lifecycle|page-lifecycle-evidence.json|engine-integration|renderer-lifecycle|renderer-lifecycle-evidence.json|engine-integration"
    "0007|window-controls.hosted|window-controls-report|window-controls-report.json|provider-lifecycle"
  )
  count=0
  total=${#specs[@]}
  for spec in "${specs[@]}"; do
    IFS='|' read -r rfc provider artifact name family extra_name extra_file_name extra_family <<< "$spec"
    file=$(find "$downloaded" -type f -name "$name" -path "*${family}-${target}-*" | head -1)
    extra_file=""
    if [ -n "${extra_name:-}" ]; then
      extra_file=$(find "$downloaded" -type f -name "$extra_file_name" -path "*${extra_family}-${target}-*" | head -1)
    fi
    count=$((count + 1))
    if [ "$count" -eq "$total" ]; then
      output="$input"
    else
      output="build/rfc-evidence/chain-${target}-${count}.json"
    fi
    record "$target" "$rfc" "$provider" "$artifact" "$file" "$chain" "$output" "${extra_name:-}" "${extra_file:-}"
    chain="$output"
  done
done

echo "Hosted RFC evidence recorded into $input"

#!/usr/bin/env bash
# Records the three hosted RFC evidence records (0001 governance, 0002 provider
# SDK, 0003 permission policy) for the current runner target. Must run after the
# full verification passed: every record retains an artifact produced by a
# passing hosted task. The catalog status flip is scripted and workspace-only;
# the checked-in status flip lands in the same commit as the checked-in records
# because the recorder refuses to record against a non-Implemented catalog.
set -euo pipefail

cd "$(dirname "$0")/../.."

output="build/rfc-evidence/manifest"
case "${RUNNER_OS}:${RUNNER_ARCH}" in
  macOS:ARM64) target="macos-arm64" ;;
  Windows:X64) target="windows-x64" ;;
  Linux:X64) target="linux-x64" ;;
  *) echo "Unsupported runner ${RUNNER_OS}:${RUNNER_ARCH}" >&2; exit 1 ;;
esac

# Windows runners check out CRLF working trees; the contract digests hash the
# committed LF bytes, so re-materialize the tree from the index before recording.
if [ "$RUNNER_OS" = "Windows" ]; then
  # A plain checkout never rewrites CRLF working files: the clean filter makes
  # them look unchanged against the LF index. Remove the tracked files and
  # re-materialize the whole tree so the smudge filter writes the committed LF
  # bytes the contract digests hash.
  git ls-files -z | xargs -0 rm -f
  git -c core.autocrlf=false -c core.eol=lf checkout HEAD -- .
  file docs/rfcs/0001-program-governance.md || true
fi

for rfc in 0001 0002 0003; do
  file=$(ls docs/rfcs/${rfc}-*.md)
  sed 's/^- Status: .*$/- Status: Implemented/' "$file" > "$file.recorded"
  mv "$file.recorded" "$file"
done

record() {
  local rfc="$1" artifact="$2" input="$3"
  ./gradlew --no-daemon :kweb-rfc-governance:rfcEvidenceRecord \
    -PrfcEvidenceArguments="record ${input} ${output}-${target}.json --catalog docs/rfcs --runtime runtime/cef-runtime.json --contracts docs/rfcs/evidence/contracts.json --repository-root . --rfc ${rfc} --provider governance.hosted --electron-major 44 --artifact ${artifact}"
}

record 0001 \
  "governance-report=kweb-rfc-governance/build/reports/rfc-governance/status.json" \
  "docs/rfcs/evidence/manifest.json"
record 0002 \
  "provider-lifecycle-report=kweb-service-window-controls/build/window-controls-integration/provider-lifecycle-report.json" \
  "${output}-${target}.json"
record 0003 \
  "dialogs-consent=kweb-service-dialogs/build/dialogs-integration/consent-status.json" \
  "${output}-${target}.json"

echo "Recorded hosted RFC evidence for ${target}: ${output}-${target}.json"
